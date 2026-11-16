package oracle.bitacora.service;

import oracle.bitacora.repository.AnaliticaIncidenciaRepository;
import oracle.bitacora.repository.ReferenciasIncidenciaRepository;
import oracle.bitacora.response.AnaliticaBitacoraResponse;
import oracle.bitacora.response.AnaliticaBitacoraResponse.Abiertas;
import oracle.bitacora.response.AnaliticaBitacoraResponse.FilaEjecutiva;
import oracle.bitacora.response.AnaliticaBitacoraResponse.Grupo;
import oracle.bitacora.response.AnaliticaBitacoraResponse.Kpis;
import oracle.bitacora.response.AnaliticaBitacoraResponse.Reincidencia;
import oracle.bitacora.response.AnaliticaBitacoraResponse.Tipo;
import oracle.bitacora.response.CasoLargoResponse;
import oracle.common.response.CeldaMatriz;
import oracle.common.config.ZonaNegocio;
import oracle.bitacora.repository.CondicionesIncidencia;
import oracle.bitacora.repository.CondicionesIncidencia.Condicion;
import oracle.bitacora.repository.CondicionesIncidencia.Desde;
import oracle.bitacora.repository.CondicionesIncidencia.Estado;
import oracle.bitacora.repository.CondicionesIncidencia.Hasta;
import oracle.bitacora.repository.CondicionesIncidencia.Igual;
import oracle.bitacora.repository.CondicionesIncidencia.Texto;
import oracle.common.support.CursorRecientes;
import oracle.common.support.EjecutorAnalitica;

import oracle.common.support.ActorActual;
import oracle.common.support.Auditoria;
import oracle.common.support.ExportacionJson;
import oracle.common.support.Paginacion;
import oracle.common.support.Trazas;

import jakarta.persistence.criteria.Predicate;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import oracle.bitacora.request.CerrarIncidenciaRequest;
import oracle.common.exception.ConflictoException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.Function;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.bitacora.model.Incidencia;
import oracle.bitacora.repository.IncidenciaRepository;
import oracle.bitacora.request.EstadoIncidencia;
import oracle.bitacora.request.FiltroIncidencia;
import oracle.bitacora.request.IncidenciaActualizarRequest;
import oracle.bitacora.request.IncidenciaCrearRequest;
import oracle.common.response.ApiResponse;
import oracle.bitacora.response.IncidenciaResponse;
import oracle.common.response.PaginaResponse;
import oracle.common.config.CacheRespuestas;
import oracle.common.config.Transacciones;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import lombok.extern.slf4j.Slf4j;

/**
 * Registro (bitácora) de incidencias. Valida las FK compuestas del esquema,
 * registra creado_en con la fecha indicada + hora de inicio (instante UTC) y
 * calcula el tiempo de solución en segundos.
 *
 * El listado se pagina y filtra en el servidor: solo se devuelve la página
 * solicitada y los agregados del dashboard se calculan con SQL (GROUP BY),
 * de modo que el cliente nunca descarga la tabla completa.
 */
@Slf4j
@Service
public class IncidenciaService {

    /**
     * Espacio de CacheRespuestas de Bitácora (analítica y conteos del listado):
     * lo invalidan las escrituras de incidencias y de catálogos.
     */
    static final String ESPACIO_CACHE = "bitacora";

    /** Tope de filas por página que acepta el servidor. */
    private static final int MAX_TAMANO_PAGINA = 200;

    /** Combinaciones centro + tipo que devuelve el bloque de reincidencias. */
    private static final int MAX_REPETIDOS = 5;

    /** Tope de casos por sistema en el detalle del cuadro ejecutivo. */
    static final int MAX_CASOS_LARGOS = 10;

    private final IncidenciaRepository incidenciaRepository;
    private final ReferenciasIncidenciaRepository referenciasRepository;
    private final CatalogoBitacora catalogo;
    private final CacheRespuestas cacheRespuestas;
    private final Transacciones transacciones;
    private final ObjectMapper json;
    private final AnaliticaIncidenciaRepository analiticaRepository;
    private final EjecutorAnalitica ejecutor;
    private final ZonaNegocio zonaNegocio;

    public IncidenciaService(IncidenciaRepository incidenciaRepository,
            ReferenciasIncidenciaRepository referenciasRepository,
            CatalogoBitacora catalogo,
            CacheRespuestas cacheRespuestas,
            Transacciones transacciones,
            ObjectMapper json,
            AnaliticaIncidenciaRepository analiticaRepository,
            EjecutorAnalitica ejecutor,
            ZonaNegocio zonaNegocio) {
        this.incidenciaRepository = incidenciaRepository;
        this.referenciasRepository = referenciasRepository;
        this.catalogo = catalogo;
        this.cacheRespuestas = cacheRespuestas;
        this.transacciones = transacciones;
        this.json = json;
        this.analiticaRepository = analiticaRepository;
        this.ejecutor = ejecutor;
        this.zonaNegocio = zonaNegocio;
    }

    public ApiResponse<PaginaResponse<IncidenciaResponse>> listar(FiltroIncidencia filtro, int pagina,
            int tamano) {
        PageRequest pageable = Paginacion.pagina(pagina, tamano, MAX_TAMANO_PAGINA, Paginacion.RECIENTES);
        Specification<Incidencia> spec = especificacion(filtro);
        return Trazas.medir(log, "IncidenciaService.listar", () -> {
            // La página se lee siempre; el total (COUNT sobre todo el filtro, lo
            // caro al paginar) se cachea por filtro hasta la próxima escritura.
            List<Incidencia> filas = incidenciaRepository.findBy(spec, q -> q.slice(pageable)).getContent();
            long total = cacheRespuestas.obtener(ESPACIO_CACHE + ":conteo:" + filtro,
                    () -> incidenciaRepository.count(spec));
            return ApiResponse.ok("Incidencias listadas.", PaginaResponse.of(resolverLista(filas), total,
                    pageable.getPageNumber() + 1, pageable.getPageSize()));
        });
    }

    /**
     * Exporta el filtro completo escribiendo el JSON directamente al flujo de
     * respuesta: lee por lotes de {@value ExportacionJson#TAMANO_LOTE} filas
     * (por cursor, sin OFFSET ni COUNT) y nunca materializa la lista completa,
     * así el pico de memoria queda acotado aunque el filtro devuelva cientos de
     * miles de filas.
     *
     * Produce exactamente el mismo envoltorio que
     * {@code ApiResponse&lt;List&lt;IncidenciaResponse&gt;&gt;} (exito, mensaje,
     * datos, timestamp), así el contrato con el frontend no cambia.
     */
    /**
     * Tope de filas de una exportacion: mas que eso es un archivo que el navegador
     * no puede abrir y una conexion retenida por minutos. Se pide acotar el filtro.
     */
    public static final int MAX_EXPORTACION = 50_000;

    public StreamingResponseBody exportarEnFlujo(FiltroIncidencia filtro) {
        Specification<Incidencia> spec = especificacion(filtro);
        long total = incidenciaRepository.count(spec);
        if (total > MAX_EXPORTACION) {
            throw new SolicitudInvalidaException("La exportacion admite hasta " + MAX_EXPORTACION
                    + " registros y el filtro tiene " + total + ". Acote el rango de fechas u otros filtros.");
        }
        return ExportacionJson.enLotes(json, "Incidencias exportadas.",
                (ultima, tamano) -> incidenciaRepository.findBy(siguientes(spec, ultima),
                        q -> q.sortBy(Paginacion.RECIENTES).limit(tamano).all()),
                this::conversor, log, "incidencias");
    }

    /**
     * Analitica completa del dashboard: 7 agregaciones en paralelo sobre el
     * ejecutor analitico, cacheadas por filtro (single-flight por clave).
     */
    public ApiResponse<AnaliticaBitacoraResponse> analitica(FiltroIncidencia filtro) {
        String clave = ESPACIO_CACHE + ":analitica:" + filtro;
        // Lo agregado se cachea por filtro; las abiertas dependen de la hora: siempre frescas.
        AnaliticaBitacoraResponse datos = Trazas.medir(log, "IncidenciaService.analitica", () ->
                cacheRespuestas.obtener(clave, () -> calcularAnalitica(filtro))
                        .conAbiertas(analiticaRepository.abiertas(filtro)));
        return ApiResponse.ok("Analitica calculada.", datos);
    }

    private AnaliticaBitacoraResponse calcularAnalitica(FiltroIncidencia filtro) {
        try (EjecutorAnalitica.Lote lote = ejecutor.lote()) {
            Future<Kpis> kpis = lote.consulta(() -> analiticaRepository.kpis(filtro));
            Future<List<Grupo>> centros = lote.consulta(() -> analiticaRepository.porCentro(filtro));
            Future<List<Grupo>> areas = lote.consulta(() -> analiticaRepository.porArea(filtro));
            Future<List<Tipo>> tipos = lote.consulta(() -> analiticaRepository.porTipo(filtro));
            Future<List<CeldaMatriz>> centroSistema = lote.consulta(() -> analiticaRepository.centroPorSistema(filtro));
            Future<List<FilaEjecutiva>> ejecutivo = lote.consulta(() -> analiticaRepository.ejecutivoPorSistema(filtro));
            Future<Reincidencia> reincidencia = lote.consulta(
                    () -> analiticaRepository.reincidencias(filtro, MAX_REPETIDOS));
            List<FilaEjecutiva> filas = lote.esperar(ejecutivo);
            return new AnaliticaBitacoraResponse(
                    lote.esperar(kpis), gruposDeSistemas(filas), lote.esperar(centros),
                    lote.esperar(areas), lote.esperar(tipos), lote.esperar(centroSistema),
                    filas, lote.esperar(reincidencia), Abiertas.NINGUNA);
        }
    }

    /**
     * Agregado por sistema (volumen y MTTR) a partir del cuadro ejecutivo, que ya
     * agrupa por sistema: evita repetir la misma agregacion. Orden de porSistema.
     */
    private static List<Grupo> gruposDeSistemas(List<FilaEjecutiva> filas) {
        return filas.stream()
                .sorted(Comparator.comparingLong(FilaEjecutiva::total).reversed()
                        .thenComparing(FilaEjecutiva::etiqueta))
                .map(f -> new Grupo(f.etiqueta(), f.total(), f.mttrSegundos()))
                .toList();
    }

    /**
     * Casos resueltos mas largos de un sistema dentro del filtro del dashboard
     * (detalle del cuadro ejecutivo). El sistema de la fila manda sobre el del filtro.
     */
    public ApiResponse<List<CasoLargoResponse>> casosMasLargos(FiltroIncidencia filtro, long sistema, int limite) {
        if (limite < 1 || limite > MAX_CASOS_LARGOS) {
            throw new SolicitudInvalidaException("El limite debe estar entre 1 y " + MAX_CASOS_LARGOS + ".");
        }
        FiltroIncidencia delSistema = new FiltroIncidencia(filtro.centro(), sistema, filtro.area(),
                filtro.responsable(), filtro.tipo(), filtro.desde(), filtro.hasta(), filtro.texto(), filtro.estado());
        String clave = ESPACIO_CACHE + ":casos-largos:" + limite + ":" + delSistema;
        List<CasoLargoResponse> casos = Trazas.medir(log, "IncidenciaService.casosMasLargos", () ->
                cacheRespuestas.obtener(clave, () -> analiticaRepository.casosMasLargos(delSistema, limite)));
        return ApiResponse.ok("Casos obtenidos.", casos);
    }

    public ApiResponse<IncidenciaResponse> crear(IncidenciaCrearRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "IncidenciaService.crear ticket=" + peticion.ticket(), () -> transacciones.ejecutar(() -> {
            validarReferencias(peticion.idDataCentro(), peticion.idDataSistema(), peticion.idDataIncidencia(),
                    peticion.idDataArea(), peticion.idDataResponsable(), peticion.ticket(), null);

            Incidencia incidencia = new Incidencia();
            aplicarCampos(incidencia, peticion.idDataCentro(), peticion.idDataSistema(), peticion.idDataIncidencia(),
                    peticion.ticket(), peticion.idDataArea(), peticion.idDataResponsable(), peticion.descripcion());
            Auditoria.alCrear(incidencia, actor);
            fijarVersion(incidencia);
            aplicarFechas(incidencia, peticion.fecha(), peticion.horaInicio(), peticion.fechaFin(), peticion.horaFin());
            incidenciaRepository.save(incidencia);
            invalidarCacheAlConfirmar();

            return ApiResponse.ok("Incidencia registrada.", resolver(incidencia));
        }));
    }

    public ApiResponse<IncidenciaResponse> actualizar(Long id, IncidenciaActualizarRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "IncidenciaService.actualizar id=" + id, () -> transacciones.ejecutar(() -> {
            // Bloquea la fila: una edicion simultanea espera y luego ve la version nueva.
            Incidencia incidencia = bloquearIncidencia(id);
            exigirVersion(incidencia, peticion.version());
            validarReferencias(peticion.idDataCentro(), peticion.idDataSistema(), peticion.idDataIncidencia(),
                    peticion.idDataArea(), peticion.idDataResponsable(), peticion.ticket(), id);

            aplicarCampos(incidencia, peticion.idDataCentro(), peticion.idDataSistema(), peticion.idDataIncidencia(),
                    peticion.ticket(), peticion.idDataArea(), peticion.idDataResponsable(), peticion.descripcion());
            auditarCambio(incidencia, actor);
            aplicarFechas(incidencia, peticion.fecha(), peticion.horaInicio(), peticion.fechaFin(), peticion.horaFin());
            incidenciaRepository.save(incidencia);
            invalidarCacheAlConfirmar();

            return ApiResponse.ok("Incidencia actualizada.", resolver(incidencia));
        }));
    }

    /**
     * Cierra una incidencia abierta con la fecha y hora de fin indicadas (o
     * ahora, en hora del negocio, si no se indican).
     */
    public ApiResponse<IncidenciaResponse> cerrar(Long id, CerrarIncidenciaRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "IncidenciaService.cerrar id=" + id, () -> transacciones.ejecutar(() -> {
            // Bloquea la fila: de dos cierres simultaneos, el segundo espera y ve que ya esta cerrada.
            Incidencia incidencia = bloquearIncidencia(id);
            if (incidencia.getCerradoEn() != null) {
                throw new ConflictoException("La incidencia ya está cerrada.");
            }
            LocalDate fechaFin = peticion == null ? null : peticion.fechaFin();
            LocalTime horaFin = peticion == null ? null : peticion.horaFin();
            if (fechaFin == null && horaFin == null) {
                LocalDateTime ahora = zonaNegocio.enNegocio(Instant.now()).truncatedTo(ChronoUnit.MINUTES);
                fechaFin = ahora.toLocalDate();
                horaFin = ahora.toLocalTime();
            }
            aplicarFechas(incidencia, zonaNegocio.dia(incidencia.getCreadoEn()), incidencia.getHoraInicio(),
                    fechaFin, horaFin);
            auditarCambio(incidencia, actor);
            incidenciaRepository.save(incidencia);
            invalidarCacheAlConfirmar();
            return ApiResponse.ok("Incidencia cerrada.", resolver(incidencia));
        }));
    }

    public ApiResponse<Void> eliminar(Long id) {
        Trazas.medir(log, "IncidenciaService.eliminar id=" + id, () -> transacciones.ejecutar(() -> {
            Incidencia incidencia = obtenerIncidencia(id);
            incidenciaRepository.delete(incidencia);
            invalidarCacheAlConfirmar();
            return null;
        }));
        return ApiResponse.ok("Incidencia eliminada.", null);
    }

    /** Tras el commit, descarta solo la caché de Bitácora (no la de otros paneles). */
    private void invalidarCacheAlConfirmar() {
        transacciones.alConfirmar(() -> cacheRespuestas.invalidarEspacio(ESPACIO_CACHE));
    }

    /**
     * Construye el WHERE dinámico del listado. Solo añade las condiciones
     * informadas y respeta el orden de los índices (id_data_* antes de
     * creado_en) para que PostgreSQL los aproveche. Los días del filtro son días
     * del negocio.
     */
    private Specification<Incidencia> especificacion(FiltroIncidencia filtro) {
        // Mismas condiciones que el WHERE SQL de la analitica (CondicionesIncidencia).
        List<Condicion> lista = CondicionesIncidencia.de(filtro, zonaNegocio);
        return (root, query, cb) -> {
            List<Predicate> condiciones = new ArrayList<>();
            for (Condicion c : lista) {
                condiciones.add(switch (c) {
                    case Igual igual -> cb.equal(root.get(igual.atributo()), igual.valor());
                    case Desde desde -> cb.greaterThanOrEqualTo(root.get("creadoEn"), desde.instante());
                    case Hasta hasta -> cb.lessThan(root.get("creadoEn"), hasta.instante());
                    case Texto texto -> cb.or(
                            cb.like(cb.lower(root.get("ticket")), texto.patron(), '\\'),
                            cb.like(cb.lower(root.get("descripcion")), texto.patron(), '\\'));
                    case Estado estado -> estado.estado() == EstadoIncidencia.ABIERTA
                            ? cb.isNull(root.get("cerradoEn")) : cb.isNotNull(root.get("cerradoEn"));
                });
            }
            return condiciones.isEmpty() ? cb.conjunction() : cb.and(condiciones.toArray(new Predicate[0]));
        };
    }

    /** Filtro del export restringido a las filas posteriores a la última ya escrita. */
    private static Specification<Incidencia> siguientes(Specification<Incidencia> spec, Incidencia ultima) {
        if (ultima == null) {
            return spec;
        }
        Specification<Incidencia> cursor = CursorRecientes.despuesDe(ultima.getCreadoEn(), ultima.getId());
        return spec.and(cursor);
    }

    /** Resuelve los nombres de catálogo de una página en un solo lote de consultas. */
    private List<IncidenciaResponse> resolverLista(List<Incidencia> incidencias) {
        if (incidencias.isEmpty()) {
            return List.of();
        }
        return incidencias.stream().map(conversor()).toList();
    }

    /** Convierte incidencias a respuesta con un mismo snapshot de nombres de catálogo. */
    private Function<Incidencia, IncidenciaResponse> conversor() {
        Map<Long, String> centros = catalogo.centros();
        Map<Long, String> sistemas = catalogo.sistemas();
        Map<Long, String> tipos = catalogo.tipos();
        Map<Long, String> areas = catalogo.areas();
        Map<Long, String> responsables = catalogo.responsables();
        return i -> IncidenciaResponse.of(i,
                centros.get(i.getIdDataCentro()),
                sistemas.get(i.getIdDataSistema()),
                tipos.get(i.getIdDataIncidencia()),
                areas.get(i.getIdDataArea()),
                responsables.get(i.getIdDataResponsable()));
    }

    private Incidencia obtenerIncidencia(Long id) {
        return incidenciaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe la incidencia con id " + id + "."));
    }

    /** Lee la incidencia con SELECT ... FOR UPDATE (dentro de la transaccion). */
    private Incidencia bloquearIncidencia(Long id) {
        return incidenciaRepository.bloquear(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe la incidencia con id " + id + "."));
    }

    /**
     * Concurrencia optimista sobre actualizado_en (la ficha {@code version} de la
     * respuesta): si otra persona guardo despues de que el cliente leyo, 409.
     */
    private static void exigirVersion(Incidencia incidencia, Instant version) {
        if (!incidencia.getActualizadoEn().equals(version)) {
            throw new ConflictoException("Otra persona modificó esta incidencia mientras la editaba. "
                    + "Recargue la lista para ver sus cambios y vuelva a editarla.");
        }
    }

    private static void auditarCambio(Incidencia incidencia, Long actor) {
        Auditoria.alActualizar(incidencia, actor);
        fijarVersion(incidencia);
    }

    /**
     * PostgreSQL guarda microsegundos (redondeando): se trunca antes de guardar
     * para que la ficha que recibe el cliente sea identica a la guardada.
     */
    private static void fijarVersion(Incidencia incidencia) {
        incidencia.setActualizadoEn(incidencia.getActualizadoEn().truncatedTo(ChronoUnit.MICROS));
    }

    private void aplicarCampos(Incidencia incidencia, Long idCentro, Long idSistema, Long idTipo, String ticket,
            Long idArea, Long idResponsable, String descripcion) {
        incidencia.setIdDataCentro(idCentro);
        incidencia.setIdDataSistema(idSistema);
        incidencia.setIdDataIncidencia(idTipo);
        incidencia.setTicket(ticket.trim());
        incidencia.setIdDataArea(idArea);
        incidencia.setIdDataResponsable(idResponsable);
        incidencia.setDescripcion(descripcion.trim());
    }

    /**
     * Inicio y fin en hora del negocio -> instantes UTC, duracion y estado.
     * Sin hora de fin la incidencia queda abierta (fin, hora de fin y duracion nulos).
     * Debe ir despues de Auditoria (fija creado_en = inicio).
     */
    private void aplicarFechas(Incidencia incidencia, LocalDate fecha, LocalTime horaInicio,
            LocalDate fechaFin, LocalTime horaFin) {
        Instant inicio = zonaNegocio.instante(fecha, horaInicio);
        LocalDate diaFin = CierreIncidencia.fechaFin(fecha, horaInicio, fechaFin, horaFin);
        Instant fin = diaFin == null ? null : zonaNegocio.instante(diaFin, horaFin);
        CierreIncidencia.Cierre cierre = CierreIncidencia.calcular(inicio, fin, Instant.now());
        incidencia.setHoraInicio(horaInicio);
        incidencia.setHoraFin(cierre.abierta() ? null : horaFin);
        incidencia.setCreadoEn(inicio);
        incidencia.setCerradoEn(cierre.cerradoEn());
        incidencia.setTiempoSolucionSeg(cierre.segundos());
    }

    /**
     * Verifica en una sola consulta que las referencias existen, que las FK
     * compuestas del esquema son coherentes (tipo pertenece al sistema,
     * responsable pertenece al área) y que el ticket no lo usa otra incidencia.
     *
     * @param idEditada incidencia en edición (null al crear).
     */
    private void validarReferencias(Long idCentro, Long idSistema, Long idTipo, Long idArea, Long idResponsable,
            String ticket, Long idEditada) {
        ValidacionIncidencia.validar(idCentro, idSistema, idTipo, idArea, idResponsable, ticket, idEditada != null,
                referenciasRepository.consultar(idCentro, idSistema, idArea, idTipo, idResponsable, ticket.trim(),
                        idEditada == null ? 0L : idEditada));
    }

    /** Resuelve una incidencia con los nombres del catalogo cacheado (sin consultas extra). */
    private IncidenciaResponse resolver(Incidencia incidencia) {
        return resolverLista(List.of(incidencia)).get(0);
    }
}
