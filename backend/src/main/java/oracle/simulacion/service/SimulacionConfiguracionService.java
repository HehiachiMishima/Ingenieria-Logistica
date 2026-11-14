package oracle.simulacion.service;

import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import oracle.common.config.CacheRespuestas;
import oracle.common.config.Transacciones;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import oracle.common.response.PaginaResponse;
import oracle.common.support.ActorActual;
import oracle.common.support.Auditoria;
import oracle.common.support.Filtros;
import oracle.common.support.Trazas;
import oracle.simulacion.model.ArchivoExcel;
import oracle.simulacion.model.Balanceo;
import oracle.simulacion.model.Chute;
import oracle.simulacion.model.Cluster;
import oracle.simulacion.model.HistorialBalanceo;
import oracle.simulacion.model.LanzamientoBalanceo;
import oracle.simulacion.model.OperarioOla;
import oracle.simulacion.model.OperarioOlaId;
import oracle.simulacion.model.ParametroChute;
import oracle.simulacion.model.ParametroChuteId;
import oracle.simulacion.model.ParametroCluster;
import oracle.simulacion.model.RutaProyeccion;
import oracle.simulacion.model.SimulacionConfig;
import oracle.simulacion.model.TipoRuta;
import oracle.simulacion.repository.ArchivoExcelRepository;
import oracle.simulacion.repository.CambioClusterRutas;
import oracle.simulacion.repository.CargaGrupo;
import oracle.simulacion.repository.CargaRutas;
import oracle.simulacion.repository.ChuteRepository;
import oracle.simulacion.repository.ClusterRepository;
import oracle.simulacion.repository.GrupoGuardado;
import oracle.simulacion.repository.HistorialBalanceoRepository;
import oracle.simulacion.repository.LanzamientoBalanceoRepository;
import oracle.simulacion.repository.MovimientoRutas;
import oracle.simulacion.repository.RutasMovidasCluster;
import oracle.simulacion.repository.OperarioOlaRepository;
import oracle.simulacion.repository.CambioParaDeshacer;
import oracle.simulacion.repository.ParametroChuteRepository;
import oracle.simulacion.repository.ParametroClusterRepository;
import oracle.simulacion.repository.ResumenRutasFiltro;
import oracle.simulacion.repository.RutaProyeccionRepository;
import oracle.simulacion.repository.SimulacionConfigRepository;
import oracle.simulacion.repository.SimulacionLotesRepository;
import oracle.simulacion.request.BalanceoRequest;
import oracle.simulacion.request.CambiarClusterRequest;
import oracle.simulacion.request.ChuteOperariosRequest;
import oracle.simulacion.request.ClusterLimiteRequest;
import oracle.simulacion.request.ConfiguracionActualizarRequest;
import oracle.simulacion.request.FaseBalanceo;
import oracle.simulacion.request.LadoChute;
import oracle.simulacion.request.Limites;
import oracle.simulacion.request.MoverRutaRequest;
import oracle.simulacion.request.MoverRutasRequest;
import oracle.simulacion.response.BalanceoResponse;
import oracle.simulacion.response.CambioClusterResponse;
import oracle.simulacion.response.CargaSimulacionResponse;
import oracle.simulacion.response.GrupoClusterResponse;
import oracle.simulacion.response.TramoGrupoResponse;
import oracle.simulacion.response.VistaBalanceoResponse;
import oracle.simulacion.response.PaginaRutasResponse;
import oracle.simulacion.response.ChuteComparativoResponse;
import oracle.simulacion.response.ChuteParametroResponse;
import oracle.simulacion.response.ClusterComparativoResponse;
import oracle.simulacion.response.ClusterParametroResponse;
import oracle.simulacion.response.ConfiguracionSimulacionResponse;
import oracle.simulacion.response.DashboardSimulacionResponse;
import oracle.simulacion.response.DotacionChuteResponse;
import oracle.simulacion.response.FlujoChuteResponse;
import oracle.simulacion.response.FlujoOperariosResponse;
import oracle.simulacion.response.MovimientoChuteResponse;
import oracle.simulacion.response.OlaSimulacionResponse;
import oracle.simulacion.response.ResumenRutasDashboardResponse;
import oracle.simulacion.response.RutaSimulacionResponse;
import oracle.simulacion.response.RutasDashboardResponse;
import oracle.simulacion.response.SimulacionKpisResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * Configuración de límites/operarios, rutas, balanceo y comparativos de
 * simulación. Cada cluster (ola) se lanza por separado: paso 1 (rutas) y
 * paso 2 (operarios de su ola); al final se cierra la simulación completa.
 *
 * Toda escritura empieza bloqueando el registro (SELECT … FOR UPDATE), así
 * que dos usuarios sobre el mismo registro se serializan. Las lecturas
 * trabajan con agregados por (cluster, chute); solo el balanceo carga todas
 * las rutas, porque las mueve una a una.
 */
@Slf4j
@Service
public class SimulacionConfiguracionService {

  private static final BigDecimal TOLERANCIA = new BigDecimal("0.01");
  /**
   * Marca interna para un chute con carga pero sin operarios. No es una hora
   * real: se traduce a {@code null} en las respuestas para que la interfaz
   * muestre «sin operarios» en lugar de un tiempo inventado.
   */
  private static final BigDecimal SIN_OPERARIOS = new BigDecimal("1E+30");
  private static final int MAX_ITERACIONES = 1000;
  private static final int MAX_TAMANO_PAGINA = 100;
  /**
   * Página de la planilla de rutas (keyset por id): la pide entera de corrido, así
   * que páginas grandes ahorran viajes en serie (5 000 rutas: 5 peticiones, no 50).
   */
  private static final int MAX_TAMANO_RUTAS = 1_000;
  /** Lote interno al leer todas las rutas: menos idas a la base que con la página pública. */
  private static final int LOTE_TODAS_RUTAS = 5_000;
  /** Página máxima de los listados paginados con OFFSET (detalle del dashboard e historial). */
  private static final int MAX_PAGINA = 2_000;
  /** Tope total de la transacción de una lectura: corta la consulta en el servidor. */
  private static final int TOPE_TRANSACCION_LECTURA_S = 18;
  private static final int ESCALA = 4;
  /** Ordena CHUTE 2 antes que CHUTE 10 (comparación natural, no lexicográfica). */
  private static final Comparator<String> ORDEN_NATURAL =
      SimulacionConfiguracionService::compararNatural;

  private final ArchivoExcelRepository archivoRepo;
  private final SimulacionConfigRepository configRepo;
  private final ClusterRepository clusterRepo;
  private final ChuteRepository chuteRepo;
  private final ParametroClusterRepository parametroClusterRepo;
  private final ParametroChuteRepository parametroChuteRepo;
  private final RutaProyeccionRepository rutaRepo;
  private final HistorialBalanceoRepository historialRepo;
  private final OperarioOlaRepository operarioOlaRepo;
  private final LanzamientoBalanceoRepository lanzamientoRepo;
  /** Chutes 1–33 que existen siempre y los bloqueados (19, 20, 21). */
  private final PlanoChutes plano;
  private final SimulacionLotesRepository lotes;
  private final Transacciones transacciones;
  private final CacheRespuestas cache;

  public SimulacionConfiguracionService(
      ArchivoExcelRepository archivoRepo,
      SimulacionConfigRepository configRepo,
      ClusterRepository clusterRepo,
      ChuteRepository chuteRepo,
      ParametroClusterRepository parametroClusterRepo,
      ParametroChuteRepository parametroChuteRepo,
      RutaProyeccionRepository rutaRepo,
      HistorialBalanceoRepository historialRepo,
      OperarioOlaRepository operarioOlaRepo,
      LanzamientoBalanceoRepository lanzamientoRepo,
      SimulacionLotesRepository lotes,
      Transacciones transacciones,
      CacheRespuestas cache,
      PlanoChutes plano) {
    this.plano = plano;
    this.archivoRepo = archivoRepo;
    this.configRepo = configRepo;
    this.clusterRepo = clusterRepo;
    this.chuteRepo = chuteRepo;
    this.parametroClusterRepo = parametroClusterRepo;
    this.parametroChuteRepo = parametroChuteRepo;
    this.rutaRepo = rutaRepo;
    this.historialRepo = historialRepo;
    this.operarioOlaRepo = operarioOlaRepo;
    this.lanzamientoRepo = lanzamientoRepo;
    this.lotes = lotes;
    this.transacciones = transacciones;
    this.cache = cache;
  }

  public ApiResponse<ConfiguracionSimulacionResponse> obtener(Long archivoId) {
    return obtener(archivoId, Balanceo.LXV);
  }

  /** Nombre del registro (para el archivo exportado), sin calcular nada más. */
  public String nombreRegistro(Long archivoId) {
    return transacciones.consultar(
        () ->
            archivoRepo
                .findById(archivoId)
                .orElseThrow(
                    () ->
                        new RecursoNoEncontradoException(
                            "No existe el registro con id " + archivoId + "."))
                .getNombreRegistro(),
        TOPE_TRANSACCION_LECTURA_S);
  }

  /** Configuración de un balanceo: sus clusters, su carga y los chutes (con su buff). */
  public ApiResponse<ConfiguracionSimulacionResponse> obtener(Long archivoId, Balanceo balanceo) {
    return leer(
        "obtener id=" + archivoId + " balanceo=" + balanceo,
        () -> cacheado(archivoId, balanceo, "configuracion", this::aConfiguracion),
        "Configuración de simulación.");
  }

  /**
   * Rutas de la planilla por keyset: `despues` es el id de la última ruta recibida (null en la
   * primera página), y la respuesta trae `siguiente` mientras queden. Con idx_rutas_archivo_id
   * cada página sale del índice desde ese id, sin recorrer las anteriores como OFFSET. El total
   * solo se cuenta en la primera página (para la barra de progreso).
   */
  public ApiResponse<PaginaRutasResponse> rutas(
      Long archivoId,
      Long despues,
      int tamano,
      String texto,
      List<Long> clusterIds,
      List<Long> chuteIds) {
    int t = Math.min(Math.max(tamano, 1), MAX_TAMANO_RUTAS);
    String termino = Filtros.normalizar(texto);
    // La búsqueda es por prefijo (no "contiene"): la resuelve el índice único
    // (archivo, código, tipo).
    Specification<RutaProyeccion> filtros =
        (root, query, cb) -> {
          List<Predicate> condiciones = new ArrayList<>();
          condiciones.add(cb.equal(root.get("archivoId"), archivoId));
          if (clusterIds != null && !clusterIds.isEmpty()) {
            condiciones.add(root.get("clusterId").in(clusterIds));
          }
          if (chuteIds != null && !chuteIds.isEmpty()) {
            condiciones.add(root.get("chuteId").in(chuteIds));
          }
          if (termino != null) {
            condiciones.add(cb.like(root.get("codigoRuta"), Filtros.escaparLike(termino) + "%", '\\'));
          }
          return cb.and(condiciones.toArray(Predicate[]::new));
        };
    Specification<RutaProyeccion> pagina =
        despues == null
            ? filtros
            : filtros.and((root, query, cb) -> cb.greaterThan(root.get("id"), despues));
    return leer(
        "rutas id=" + archivoId,
        () -> {
          Contexto ctx = contexto(archivoId, false);
          List<RutaProyeccion> filas =
              rutaRepo.findBy(pagina, (q) -> q.sortBy(Sort.by("id").ascending()).limit(t).all());
          List<RutaSimulacionResponse> lista = filas.stream().map((ruta) -> aRuta(ctx, ruta)).toList();
          Long siguiente = filas.size() < t ? null : filas.getLast().getId();
          Long total = despues == null ? rutaRepo.count(filtros) : null;
          return new PaginaRutasResponse(lista, total, siguiente);
        },
        "Rutas del registro.");
  }

  /**
   * Todas las rutas del registro en una sola lectura (reporte, PDF y Excel): el contexto se arma
   * una vez y las rutas se leen por lotes de 5 000 con keyset, sin el conteo de la
   * paginación. Antes, cada página de 1 000 volvía a armar el contexto (unas 5 consultas).
   */
  public ApiResponse<List<RutaSimulacionResponse>> todasLasRutas(Long archivoId) {
    return leer(
        "todas las rutas id=" + archivoId,
        () -> {
          Contexto ctx = contexto(archivoId, false);
          List<RutaSimulacionResponse> todas = new ArrayList<>();
          Long despues = null;
          while (true) {
            Long desde = despues;
            Specification<RutaProyeccion> lote =
                (root, query, cb) ->
                    desde == null
                        ? cb.equal(root.get("archivoId"), archivoId)
                        : cb.and(cb.equal(root.get("archivoId"), archivoId), cb.greaterThan(root.get("id"), desde));
            List<RutaProyeccion> filas =
                rutaRepo.findBy(lote, (q) -> q.sortBy(Sort.by("id").ascending()).limit(LOTE_TODAS_RUTAS).all());
            filas.forEach((ruta) -> todas.add(aRuta(ctx, ruta)));
            if (filas.size() < LOTE_TODAS_RUTAS) {
              return todas;
            }
            despues = filas.getLast().getId();
          }
        },
        "Rutas del registro.");
  }

  /**
   * Un lado del detalle de rutas del dashboard: las rutas que los chutes
   * elegidos tenían al cargar el Excel (ANTES) o las que tienen hoy (DESPUES),
   * con los totales del conjunto filtrado (no solo de la página). Se ordenan
   * por cluster y código para que las rutas de cada cluster salgan agrupadas.
   */
  public ApiResponse<RutasDashboardResponse> rutasDashboard(
      Long archivoId,
      int pagina,
      int tamano,
      String texto,
      TipoRuta tipoRuta,
      List<Long> chuteIds,
      LadoChute lado,
      Long clusterId,
      boolean soloCambios) {
    return rutasDashboard(
        archivoId, Balanceo.LXV, pagina, tamano, texto, tipoRuta, chuteIds, lado, clusterId, soloCambios);
  }

  /** Igual, solo con las rutas del balanceo (las suyas y las diarias). */
  public ApiResponse<RutasDashboardResponse> rutasDashboard(
      Long archivoId,
      Balanceo balanceo,
      int pagina,
      int tamano,
      String texto,
      TipoRuta tipoRuta,
      List<Long> chuteIds,
      LadoChute lado,
      Long clusterId,
      boolean soloCambios) {
    List<String> frecuencias = List.of(balanceo.name(), Balanceo.DIARIA);
    // Tope de página: un OFFSET enorme recorrería todo el índice del registro.
    int p = Math.min(Math.max(pagina, 1), MAX_PAGINA) - 1;
    int t = Math.min(Math.max(tamano, 1), MAX_TAMANO_PAGINA);
    boolean antes = lado == LadoChute.ANTES;
    // Cada lado agrupa por el cluster que la ruta tenía entonces.
    PageRequest pageable =
        PageRequest.of(
            p,
            t,
            Sort.by(antes ? "clusterOriginalId" : "clusterId", "codigoRuta", "id").ascending());
    String termino = Filtros.normalizar(texto);
    String patron = termino == null ? null : Filtros.escaparLike(termino) + "%";
    Specification<RutaProyeccion> spec =
        (root, query, cb) -> {
          List<Predicate> condiciones = new ArrayList<>();
          condiciones.add(cb.equal(root.get("archivoId"), archivoId));
          if (patron != null) {
            condiciones.add(cb.like(root.get("codigoRuta"), patron, '\\'));
          }
          if (tipoRuta != null) {
            condiciones.add(cb.equal(root.get("tipo"), tipoRuta));
          }
          condiciones.add(root.get(antes ? "chuteOriginalId" : "chuteId").in(chuteIds));
          condiciones.add(root.get("frecuencia").in(frecuencias));
          // Cada lado con el cluster que la ruta tenía entonces: antes, el original.
          if (clusterId != null) {
            condiciones.add(
                cb.equal(root.get(antes ? "clusterOriginalId" : "clusterId"), clusterId));
          }
          if (soloCambios) {
            condiciones.add(
                cb.or(cb.isTrue(root.get("movida")), cb.isTrue(root.get("clusterCambiado"))));
          }
          return cb.and(condiciones.toArray(Predicate[]::new));
        };
    return leer(
        "rutas dashboard id=" + archivoId,
        () -> {
          Contexto ctx = contexto(archivoId, balanceo, false);
          Page<RutaProyeccion> page = rutaRepo.findAll(spec, pageable);
          List<RutaSimulacionResponse> lista =
              page.getContent().stream().map((ruta) -> aRuta(ctx, ruta)).toList();
          ResumenRutasFiltro fila =
              rutaRepo.resumenFiltrado(
                  archivoId,
                  patron,
                  tipoRuta == null ? null : tipoRuta.name(),
                  chuteIds,
                  antes,
                  clusterId,
                  soloCambios,
                  frecuencias);
          ResumenRutasDashboardResponse resumen =
              new ResumenRutasDashboardResponse(
                  fila == null || fila.getRutas() == null ? 0 : fila.getRutas(),
                  fila == null || fila.getRutasB2c() == null ? 0 : fila.getRutasB2c(),
                  fila == null || fila.getRutasB2b() == null ? 0 : fila.getRutasB2b(),
                  fila == null || fila.getCambios() == null ? 0 : fila.getCambios(),
                  fila == null || fila.getBultos() == null ? BigDecimal.ZERO : fila.getBultos(),
                  fila == null || fila.getUnidades() == null
                      ? BigDecimal.ZERO
                      : fila.getUnidades(),
                  fila == null || fila.getLineas() == null ? BigDecimal.ZERO : fila.getLineas(),
                  fila == null || fila.getCod() == null ? 0 : fila.getCod().longValue());
          return new RutasDashboardResponse(resumen, PaginaResponse.de(page, lista));
        },
        "Rutas del dashboard.");
  }

  private static RutaSimulacionResponse aRuta(Contexto ctx, RutaProyeccion ruta) {
    return new RutaSimulacionResponse(
        ruta.getId(),
        ruta.getChuteId(),
        ctx.nombreChute(ruta.getChuteId()),
        ruta.getChuteOriginalId(),
        ctx.nombreChute(ruta.getChuteOriginalId()),
        ruta.getClusterId(),
        ctx.nombreCluster(ruta.getClusterId()),
        ruta.getClusterOriginalId(),
        ctx.nombreCluster(ruta.getClusterOriginalId()),
        rutaEditable(ctx, ruta),
        ruta.getCodigoRuta(),
        ruta.getTipo() == null ? null : ruta.getTipo().name(),
        ruta.getTotalBultos(),
        ruta.getTotalUnidades(),
        ruta.getTotalLineas(),
        ruta.getConteoCod(),
        grupo(ruta),
        frecuencia(ruta),
        Balanceo.diaria(ruta.getFrecuencia()) && diariasFijas(ctx, ruta.getClusterId()),
        ruta.getDias());
  }

  /** DIARIA, LXV o MJS; las rutas sin dato (anteriores a las frecuencias) son diarias. */
  private static String frecuencia(RutaProyeccion ruta) {
    return ruta.getFrecuencia() == null ? Balanceo.DIARIA : ruta.getFrecuencia();
  }

  /**
   * La ruta puede cambiar de cluster en cada balanceo donde aparece (las diarias, en los tres): su
   * cluster y al menos otro siguen sin cerrar.
   */
  private static boolean rutaEditable(Contexto ctx, RutaProyeccion ruta) {
    for (Balanceo b : Balanceo.values()) {
      if (b.incluye(ruta.getFrecuencia())
          && !clusterEditable(ctx.parametrosDe(b), ruta.getClusterId())) {
        return false;
      }
    }
    return true;
  }

  /** Grupo de la ruta; «» si no tiene (no ocurre con rutas del Excel). */
  private static String grupo(RutaProyeccion ruta) {
    return ruta.getGrupo() == null ? "" : ruta.getGrupo();
  }

  public ApiResponse<ConfiguracionSimulacionResponse> guardar(
      Long archivoId, ConfiguracionActualizarRequest req) {
    return guardar(archivoId, Balanceo.LXV, req);
  }

  /**
   * Velocidad, máximo por chute, buff y el límite de cada cluster valen para los tres balanceos
   * (un solo límite por cluster).
   */
  public ApiResponse<ConfiguracionSimulacionResponse> guardar(
      Long archivoId, Balanceo balanceo, ConfiguracionActualizarRequest req) {
    Long actor = ActorActual.id();
    return escribir(
        "guardar id=" + archivoId + " balanceo=" + balanceo,
        () -> {
          Contexto ctx = contexto(archivoId, balanceo, true);
          SimulacionConfig config = ctx.config();
          exigirPredeterminadosAbiertos(ctx, req);
          if (config.getCreadoEn() == null) {
            Auditoria.alCrear(config, actor);
          } else {
            Auditoria.alActualizar(config, actor);
          }
          // La velocidad y el máximo por chute valen para todas las olas: si cambian,
          // ninguna asignación sigue al día. Lo que ya usó un cluster cerrado no se
          // puede cambiar sin reabrirlo. La cantidad de operarios es de cada ola
          // (paso 2): ya no hay un equipo del registro y `operariosMaximos` se ignora.
          boolean cambiaRitmo =
              config.getVelocidadBase() == null
                  || config.getVelocidadBase().compareTo(req.velocidadBase()) != 0
                  // 0 y nulo son "sin tope".
                  || !Objects.equals(sinTope(config.getOperariosPorChute()), sinTope(req.operariosPorChute()));
          // Velocidad y máximo por chute valen para todos los balanceos: un cluster cerrado
          // en cualquiera de ellos ya los usó.
          Set<Long> cerradosAmbos = new LinkedHashSet<>();
          for (Long id : orden(ctx)) {
            for (Balanceo b : Balanceo.values()) {
              ParametroCluster p = ctx.parametro(b, id);
              if (p != null && ParametroCluster.ESTADO_CERRADO.equals(p.getEstado())) {
                cerradosAmbos.add(id);
              }
            }
          }
          String cerrados = cambiaRitmo ? nombres(ctx, cerradosAmbos) : "";
          if (!cerrados.isEmpty()) {
            throw new ConflictoException(
                "El equipo ya trabajó en "
                    + cerrados
                    + ": reabre el primero de esos clusters para cambiarlo.");
          }
          config.setVelocidadBase(req.velocidadBase());
          // Nulo = reparto parejo: el usuario puede volver a dejarlo vacío.
          config.setOperariosPorChute(req.operariosPorChute());
          configRepo.save(config);

          Set<Long> limiteCambiado = new HashSet<>();
          if (req.clusters() != null) {
            for (ClusterLimiteRequest limite : req.clusters()) {
              if (!ctx.parametrosCluster().containsKey(limite.clusterId())) {
                throw new SolicitudInvalidaException(
                    "El cluster " + limite.clusterId() + " no pertenece al registro.");
              }
              // Un solo límite por cluster: vale para los tres balanceos.
              for (Balanceo b : Balanceo.values()) {
                ParametroCluster parametro = ctx.parametro(b, limite.clusterId());
                if (parametro == null
                    || (parametro.getLimiteHoras() != null
                        && parametro.getLimiteHoras().compareTo(limite.limiteHoras()) == 0)) {
                  continue;
                }
                if (ParametroCluster.ESTADO_CERRADO.equals(parametro.getEstado())) {
                  throw new ConflictoException(
                      ctx.nombreCluster(limite.clusterId())
                          + " está cerrado: reábrelo para cambiar su límite.");
                }
                parametro.setLimiteHoras(limite.limiteHoras());
                limiteCambiado.add(limite.clusterId());
              }
            }
          }
          if (req.chutes() != null) {
            for (ChuteOperariosRequest operarios : req.chutes()) {
              ParametroChute parametro = ctx.parametrosChute().get(operarios.chuteId());
              if (parametro == null) {
                throw new SolicitudInvalidaException(
                    "El chute " + operarios.chuteId() + " no pertenece al registro.");
              }
              // La base solo es el «antes» y lo que usa una ola sin asignar:
              // cambiarla no invalida las olas que ya tienen operarios.
              parametro.setOperarios(operarios.operarios());
              if (operarios.buff() != null && operarios.buff() != parametro.isBuff()) {
                // El buff decide dónde van las rutas más pesadas: se define al inicio.
                if (buffBloqueado(ctx)) {
                  throw new ConflictoException(
                      "El buff de los chutes se define antes de balancear: restablece los"
                          + " clusters ya balanceados para cambiarlo.");
                }
                if (bloqueado(ctx, operarios.chuteId())) {
                  throw new SolicitudInvalidaException(
                      "El " + etiquetaChute(ctx, operarios.chuteId()) + " está bloqueado: no puede tener buff.");
                }
                parametro.setBuff(operarios.buff());
                parametroChuteRepo.save(parametro);
              }
              if (ctx.chutesAgregados().contains(operarios.chuteId())) {
                // Chute del plano que el registro no tenía guardado: se persiste
                // (si no, el cambio se perdería sin aviso).
                parametroChuteRepo.save(parametro);
              }
            }
          }
          validarOperarios(ctx);
          // Un cluster LISTO cuyo cálculo cambió (límite, velocidad o máximo por
          // chute) vuelve a «Asignar operarios»; sus rutas del paso 1 siguen valiendo.
          Set<Long> invalidar = new HashSet<>(limiteCambiado);
          if (cambiaRitmo) {
            invalidar.addAll(ctx.parametrosCluster().keySet());
          }
          invalidarOperariosEn(ctx, Balanceo.DIAS, invalidar);
          tocar(ctx.archivo(), actor);
          return aConfiguracion(ctx);
        },
        "Parámetros guardados.");
  }

  /**
   * Con los predeterminados bloqueados, ningún registro cambia su velocidad ni su buff: hay que
   * desbloquearlos (para todos) antes. Lo demás (límites, máximo por chute) sí se guarda.
   */
  private void exigirPredeterminadosAbiertos(Contexto ctx, ConfiguracionActualizarRequest req) {
    if (!SimulacionPredeterminadosService.actuales(lotes).bloqueado()) {
      return;
    }
    BigDecimal velocidad = ctx.config().getVelocidadBase();
    boolean cambiaVelocidad = velocidad == null || velocidad.compareTo(req.velocidadBase()) != 0;
    boolean cambiaBuff =
        req.chutes() != null
            && req.chutes().stream()
                .anyMatch(
                    (c) -> {
                      ParametroChute actual = ctx.parametrosChute().get(c.chuteId());
                      return c.buff() != null && actual != null && c.buff() != actual.isBuff();
                    });
    if (cambiaVelocidad || cambiaBuff) {
      throw new ConflictoException(
          "La velocidad y los chutes con buff están bloqueados para todos los registros:"
              + " desbloquéalos para cambiarlos.");
    }
  }

  /**
   * La base de cada chute (el «antes», la proyección del Excel) no puede pasar
   * del máximo por chute. Ya no hay un equipo del registro contra el que medir
   * cada ola: la cantidad la elige cada cluster en su paso 2, que ya respeta el
   * máximo por chute.
   */
  private void validarOperarios(Contexto ctx) {
    Integer porChute = ctx.config().getOperariosPorChute();
    if (porChute != null && porChute > 0) {
      ctx.parametrosChute()
          .forEach(
              (chuteId, p) -> {
                if (base(p) > porChute) {
                  throw new ConflictoException(
                      ctx.nombreChute(chuteId)
                          + " tiene "
                          + base(p)
                          + " operario(s) y el máximo por chute es "
                          + porChute
                          + ". Ajusta ese chute o sube el máximo.");
                }
              });
    }
  }

  public ApiResponse<ConfiguracionSimulacionResponse> mover(
      Long archivoId, Long rutaId, MoverRutaRequest req) {
    return mover(archivoId, Balanceo.LXV, rutaId, req);
  }

  /**
   * Mueve a mano una ruta que se ve en el balanceo. A mano todo se puede mover, también las
   * diarias fijas; como una diaria tiene un solo chute, moverla la cambia en los dos días. El
   * cambio queda en el historial de su dueño (Diarias, para una diaria) y los días donde aparece
   * vuelven al paso de operarios.
   */
  public ApiResponse<ConfiguracionSimulacionResponse> mover(
      Long archivoId, Balanceo balanceo, Long rutaId, MoverRutaRequest req) {
    Long actor = ActorActual.id();
    return escribir(
        "mover archivo=" + archivoId + " balanceo=" + balanceo + " ruta=" + rutaId,
        () -> {
          Contexto ctx = contexto(archivoId, balanceo, true);
          RutaProyeccion ruta = rutaDelBalanceo(ctx, rutaId);
          List<Balanceo> afectados = balanceosDe(ruta);
          if (!ctx.parametrosChute().containsKey(req.chuteId())) {
            throw new SolicitudInvalidaException(
                "El chute " + req.chuteId() + " no pertenece al registro.");
          }
          exigirNoBloqueado(ctx, req.chuteId());
          exigirAbiertoEn(ctx, afectados, ruta.getClusterId(), "mover sus rutas");
          if (ruta.getChuteId().equals(req.chuteId())) {
            throw new ConflictoException(
                "La ruta ya pertenece al chute " + ctx.nombreChute(req.chuteId()) + ".");
          }
          Long origen = ruta.getChuteId();
          ruta.setChuteId(req.chuteId());
          HistorialBalanceo manual =
              movimiento(
                  archivoId,
                  null,
                  new Traslado(ruta, origen, req.chuteId()),
                  HistorialBalanceo.MOTIVO_MANUAL,
                  actor,
                  Instant.now());
          manual.setBalanceo(Balanceo.deRuta(ruta.getFrecuencia()).name());
          // Trabajo del cluster en curso: restablecerlo (o reabrir uno anterior) lo deshace.
          manual.setClusterTrabajoId(enCurso(ctx));
          historialRepo.save(manual);
          // Mover una ruta cambia la carga sobre la que se asignaron los operarios de su ola:
          // ese cluster vuelve al paso de operarios en cada día donde aparece la ruta.
          invalidarOperariosEn(ctx, afectados, List.of(ruta.getClusterId()));
          tocar(ctx.archivo(), actor);
          return aConfiguracion(ctx);
        },
        "Ruta movida.");
  }

  /** La ruta existe y aparece en el balanceo del contexto. */
  private RutaProyeccion rutaDelBalanceo(Contexto ctx, Long rutaId) {
    RutaProyeccion ruta =
        rutaRepo
            .findByIdAndArchivoId(rutaId, ctx.archivo().getId())
            .orElseThrow(() -> new RecursoNoEncontradoException("No existe la ruta " + rutaId + "."));
    if (!visible(ctx, ruta.getFrecuencia())) {
      String dias =
          ruta.getDias() != null && !ruta.getDias().isBlank()
              ? ruta.getDias()
              : SimulacionExcel.textoFrecuencia(ruta.getFrecuencia());
      throw new ConflictoException(
          "La ruta "
              + ruta.getCodigoRuta()
              + " es de "
              + dias
              + ": no aparece en "
              + ctx.balanceo().etiqueta()
              + ".");
    }
    return ruta;
  }

  /**
   * Pasa una ruta a otro cluster, sobrescribiendo el suyo (el original queda
   * guardado). Vale antes y después de balancear, mientras ninguno de los dos
   * esté cerrado; los que ya tenían operarios vuelven al paso 2 porque su carga
   * cambió. La ruta se queda en su chute.
   */
  public ApiResponse<ConfiguracionSimulacionResponse> cambiarCluster(
      Long archivoId, Long rutaId, CambiarClusterRequest req) {
    return cambiarCluster(archivoId, Balanceo.LXV, rutaId, req);
  }

  /** Igual, desde un balanceo: una ruta diaria cambia de cluster en los dos días. */
  public ApiResponse<ConfiguracionSimulacionResponse> cambiarCluster(
      Long archivoId, Balanceo balanceo, Long rutaId, CambiarClusterRequest req) {
    Long actor = ActorActual.id();
    return escribir(
        "cambiar cluster archivo=" + archivoId + " balanceo=" + balanceo + " ruta=" + rutaId,
        () -> {
          Contexto ctx = contexto(archivoId, balanceo, true);
          RutaProyeccion ruta = rutaDelBalanceo(ctx, rutaId);
          List<Balanceo> afectados = balanceosDe(ruta);
          Long origen = ruta.getClusterId();
          Long destino = req.clusterId();
          if (!ctx.parametrosCluster().containsKey(destino)) {
            throw new SolicitudInvalidaException(
                "El cluster " + destino + " no pertenece al registro.");
          }
          if (destino.equals(origen)) {
            throw new ConflictoException(
                "La ruta " + ruta.getCodigoRuta() + " ya pertenece a " + ctx.nombreCluster(origen) + ".");
          }
          exigirAbiertoEn(ctx, afectados, origen, "cambiar rutas de cluster");
          exigirAbiertoEn(ctx, afectados, destino, "cambiar rutas de cluster");
          if (!rutaRepo.existsByArchivoIdAndClusterIdAndIdNot(archivoId, origen, rutaId)) {
            throw new ConflictoException(
                "La ruta "
                    + ruta.getCodigoRuta()
                    + " es la única de "
                    + ctx.nombreCluster(origen)
                    + ": un cluster no puede quedarse sin rutas.");
          }
          // Chute final: el de la petición (cambio de cluster y de chute en un solo
          // paso, sin estado intermedio) o el suyo.
          Long chuteAntes = ruta.getChuteId();
          Long chuteFinal = req.chuteId() == null ? chuteAntes : req.chuteId();
          if (!ctx.parametrosChute().containsKey(chuteFinal)) {
            throw new SolicitudInvalidaException(
                "El chute " + chuteFinal + " no pertenece al registro.");
          }
          if (!chuteFinal.equals(chuteAntes)) {
            // Quedarse donde está se permite (registros anteriores al bloqueo);
            // llevarla a un chute bloqueado, no.
            exigirNoBloqueado(ctx, chuteFinal);
          }
          String dueno = Balanceo.deRuta(ruta.getFrecuencia()).name();
          ruta.setClusterId(destino);
          HistorialBalanceo log =
              nuevoLog(
                  archivoId,
                  HistorialBalanceo.ACCION_CAMBIAR_CLUSTER,
                  HistorialBalanceo.MOTIVO_MANUAL,
                  actor,
                  Instant.now());
          log.setRutaId(rutaId);
          log.setChuteOrigenId(ruta.getChuteId());
          log.setChuteDestinoId(ruta.getChuteId());
          log.setClusterOrigenId(origen);
          log.setClusterDestinoId(destino);
          log.setBalanceo(dueno);
          log.setClusterTrabajoId(enCurso(ctx));
          historialRepo.save(log);
          if (!chuteFinal.equals(chuteAntes)) {
            // El cambio de chute va después en el historial: deshacerlo devuelve
            // primero el chute y luego el cluster.
            ruta.setChuteId(chuteFinal);
            HistorialBalanceo movimiento =
                movimiento(
                    archivoId,
                    null,
                    new Traslado(ruta, chuteAntes, chuteFinal),
                    HistorialBalanceo.MOTIVO_MANUAL,
                    actor,
                    Instant.now());
            movimiento.setBalanceo(dueno);
            movimiento.setClusterTrabajoId(enCurso(ctx));
            historialRepo.save(movimiento);
          }
          // Cambia la carga de los dos clusters: si ya tenían operarios, vuelven al paso 2.
          invalidarOperariosEn(ctx, afectados, List.of(origen, destino));
          tocar(ctx.archivo(), actor);
          return aConfiguracion(ctx);
        },
        "Ruta cambiada de cluster.");
  }

  /**
   * Mueve varias rutas a una misma celda (cluster y chute) en una sola transacción, con las reglas
   * de mover y cambiar de cluster una por una: si una no puede, no se mueve ninguna y el mensaje
   * dice cuál y por qué. Cada ruta deja su historial en su dueño (Diarias, para una diaria) y los
   * operarios se invalidan solo en los días y clusters que cambiaron. La respuesta es la
   * configuración del balanceo indicado.
   */
  public ApiResponse<ConfiguracionSimulacionResponse> moverVarias(
      Long archivoId, Balanceo balanceo, MoverRutasRequest req) {
    Long actor = ActorActual.id();
    List<Long> ids = req.rutaIds().stream().distinct().toList();
    return escribir(
        "mover varias archivo=" + archivoId + " balanceo=" + balanceo + " rutas=" + ids.size(),
        () -> {
          Contexto ctx = contexto(archivoId, balanceo, true);
          Long clusterDestino = req.clusterId();
          Long chuteDestino = req.chuteId();
          if (!ctx.parametrosCluster().containsKey(clusterDestino)) {
            throw new SolicitudInvalidaException(
                "El cluster " + clusterDestino + " no pertenece al registro.");
          }
          if (!ctx.parametrosChute().containsKey(chuteDestino)) {
            throw new SolicitudInvalidaException(
                "El chute " + chuteDestino + " no pertenece al registro.");
          }
          Map<Long, RutaProyeccion> porId = new HashMap<>();
          for (RutaProyeccion ruta : rutaRepo.findByArchivoIdAndIdIn(archivoId, ids)) {
            porId.put(ruta.getId(), ruta);
          }
          List<RutaProyeccion> cambian = new ArrayList<>();
          Map<Long, Integer> salenDe = new HashMap<>();
          for (Long id : ids) {
            RutaProyeccion ruta = porId.get(id);
            if (ruta == null) {
              throw new RecursoNoEncontradoException("No existe la ruta " + id + ".");
            }
            boolean otroCluster = !ruta.getClusterId().equals(clusterDestino);
            boolean otroChute = !ruta.getChuteId().equals(chuteDestino);
            if (!otroCluster && !otroChute) {
              continue;
            }
            List<Balanceo> afectados = balanceosDe(ruta);
            String accion = otroCluster ? "cambiar rutas de cluster" : "mover sus rutas";
            try {
              exigirAbiertoEn(ctx, afectados, ruta.getClusterId(), accion);
              if (otroCluster) {
                exigirAbiertoEn(ctx, afectados, clusterDestino, accion);
              }
              if (otroChute) {
                exigirNoBloqueado(ctx, chuteDestino);
              }
            } catch (ConflictoException e) {
              throw new ConflictoException("Ruta " + ruta.getCodigoRuta() + ": " + e.getMessage());
            }
            if (otroCluster) {
              salenDe.merge(ruta.getClusterId(), 1, Integer::sum);
            }
            cambian.add(ruta);
          }
          if (cambian.isEmpty()) {
            throw new ConflictoException(
                "Las rutas ya están en "
                    + ctx.nombreCluster(clusterDestino)
                    + " · "
                    + etiquetaChute(ctx, chuteDestino)
                    + ".");
          }
          // Un cluster no puede quedarse sin rutas: las que salen no pueden ser todas las suyas.
          for (Map.Entry<Long, Integer> sale : salenDe.entrySet()) {
            if (rutaRepo.countByArchivoIdAndClusterId(archivoId, sale.getKey()) <= sale.getValue()) {
              throw new ConflictoException(
                  "Se llevarían todas las rutas de "
                      + ctx.nombreCluster(sale.getKey())
                      + ": un cluster no puede quedarse sin rutas.");
            }
          }
          Long trabajo = enCurso(ctx);
          Map<Balanceo, Set<Long>> invalidar = new EnumMap<>(Balanceo.class);
          for (RutaProyeccion ruta : cambian) {
            String dueno = Balanceo.deRuta(ruta.getFrecuencia()).name();
            Long clusterAntes = ruta.getClusterId();
            Long chuteAntes = ruta.getChuteId();
            if (!clusterAntes.equals(clusterDestino)) {
              ruta.setClusterId(clusterDestino);
              HistorialBalanceo log =
                  nuevoLog(
                      archivoId,
                      HistorialBalanceo.ACCION_CAMBIAR_CLUSTER,
                      HistorialBalanceo.MOTIVO_MANUAL,
                      actor,
                      Instant.now());
              log.setRutaId(ruta.getId());
              log.setChuteOrigenId(chuteAntes);
              log.setChuteDestinoId(chuteAntes);
              log.setClusterOrigenId(clusterAntes);
              log.setClusterDestinoId(clusterDestino);
              log.setBalanceo(dueno);
              log.setClusterTrabajoId(trabajo);
              historialRepo.save(log);
            }
            if (!chuteAntes.equals(chuteDestino)) {
              // Como al cambiar una sola: primero el cluster y después el chute, así deshacer
              // devuelve primero el chute.
              ruta.setChuteId(chuteDestino);
              HistorialBalanceo movimiento =
                  movimiento(
                      archivoId,
                      null,
                      new Traslado(ruta, chuteAntes, chuteDestino),
                      HistorialBalanceo.MOTIVO_MANUAL,
                      actor,
                      Instant.now());
              movimiento.setBalanceo(dueno);
              movimiento.setClusterTrabajoId(trabajo);
              historialRepo.save(movimiento);
            }
            for (Balanceo b : balanceosDe(ruta)) {
              Set<Long> clusters = invalidar.computeIfAbsent(b, (k) -> new HashSet<>());
              clusters.add(clusterAntes);
              clusters.add(clusterDestino);
            }
          }
          // Cada día vuelve al paso de operarios solo en los clusters cuya carga cambió.
          invalidar.forEach((b, clusters) -> invalidarOperariosEn(ctx, List.of(b), clusters));
          tocar(ctx.archivo(), actor);
          return aConfiguracion(ctx);
        },
        "Rutas movidas.");
  }

  /**
   * Lanza un cluster por separado: RUTAS reparte las rutas de su ola para
   * igualar la carga de sus chutes; OPERARIOS asigna los operarios de su ola
   * sobre esa carga (y puede repetirse para recalcularlos).
   */
  public ApiResponse<BalanceoResponse> balancear(
      Long archivoId, Long clusterId, FaseBalanceo fase) {
    return balancear(archivoId, clusterId, fase, null);
  }

  /**
   * Igual que {@link #balancear(Long, Long, FaseBalanceo)} con los parámetros
   * del paso, en L-X-V.
   */
  public ApiResponse<BalanceoResponse> balancear(
      Long archivoId, Long clusterId, FaseBalanceo fase, BalanceoRequest pedido) {
    return balancear(archivoId, Balanceo.LXV, clusterId, fase, pedido);
  }

  /**
   * Un paso de un cluster. RUTAS en Diarias: reparte las diarias y define el rango y los tramos
   * de los grupos (sin rango, el último elegido o, si nunca se eligió, los chutes que ya usa). RUTAS
   * en un día: reparte las rutas de ese día dentro del rango y los tramos de las diarias.
   * OPERARIOS: la misma cantidad para los dos días (sin cantidad, la última usada).
   */
  public ApiResponse<BalanceoResponse> balancear(
      Long archivoId,
      Balanceo balanceo,
      Long clusterId,
      FaseBalanceo fase,
      BalanceoRequest pedido) {
    Long actor = ActorActual.id();
    BalanceoResponse datos =
        Trazas.medir(
            log,
            "SimulacionConfiguracionService.balancear id="
                + archivoId
                + " balanceo="
                + balanceo
                + " cluster="
                + clusterId
                + " fase="
                + fase,
            () ->
                transacciones.ejecutar(
                    () ->
                        fase == FaseBalanceo.RUTAS
                            ? balancearRutas(archivoId, balanceo, clusterId, pedido, actor)
                            : operariosAmbos(
                                archivoId,
                                balanceo,
                                clusterId,
                                pedido == null ? null : pedido.operarios(),
                                actor)));
    return ApiResponse.ok(datos.mensaje(), datos);
  }

  /**
   * Paso de operarios: la misma cantidad para el cluster en L-X-V y en M-J-S, en una sola
   * transacción (si un día falla, no queda ninguno a medias). Cada día la reparte según su propia
   * carga. Exige los chutes balanceados en los dos días. La configuración de la respuesta se arma
   * una sola vez, al final.
   */
  private BalanceoResponse operariosAmbos(
      Long archivoId, Balanceo balanceo, Long clusterId, Integer pedidos, Long actor) {
    Contexto ctx = contexto(archivoId, balanceo, true);
    String nombre = ctx.nombreCluster(clusterId);
    parametroCluster(ctx, clusterId);
    if (pendiente(ctx.parametro(Balanceo.DIA, clusterId))) {
      throw new ConflictoException(
          "Primero balancea las diarias de " + nombre + ": ahí se elige el rango de chutes.");
    }
    // Participa cada día con fila y con carga (rutas propias o diarias). Un día sin rutas propias
    // no se balancea: queda listo para operarios tal como lo dejaron las diarias.
    Map<Balanceo, Long> propias = rutasPropias(archivoId, clusterId);
    List<Balanceo> dias =
        Balanceo.DIAS.stream()
            .filter((dia) -> ctx.parametro(dia, clusterId) != null)
            .filter((dia) -> participa(propias, dia))
            .toList();
    for (Balanceo dia : dias) {
      ParametroCluster parametro = ctx.parametro(dia, clusterId);
      if (!pendiente(parametro)) {
        continue;
      }
      if (propias.get(dia) > 0) {
        throw new ConflictoException(
            "Primero balancea los chutes de " + nombre + " en " + dia.etiqueta() + ".");
      }
      parametro.setEstado(ParametroCluster.ESTADO_RUTAS);
    }
    // Sin cantidad pedida, la última usada en cualquiera de los dos días.
    Integer cantidad = pedidos;
    if (cantidad == null) {
      cantidad =
          dias.stream()
              .map((dia) -> ctx.parametro(dia, clusterId).getOperariosOla())
              .filter(Objects::nonNull)
              .findFirst()
              .orElse(null);
    }
    List<String> mensajes = new ArrayList<>();
    int asignados = 0;
    for (Balanceo dia : dias) {
      Asignados hecho;
      try {
        hecho = asignarOperarios(ctx.en(dia), clusterId, cantidad, actor);
      } catch (ConflictoException error) {
        throw new ConflictoException(dia.etiqueta() + ": " + error.getMessage());
      } catch (SolicitudInvalidaException error) {
        throw new SolicitudInvalidaException(dia.etiqueta() + ": " + error.getMessage());
      }
      mensajes.add(dia.etiqueta() + " · " + hecho.mensaje());
      // El total que se informa: el del día pedido o, si no participa (o son las diarias), el primero.
      if (dias.contains(balanceo) ? dia == balanceo : dia == dias.getFirst()) {
        asignados = hecho.total();
      }
    }
    actualizarEstado(ctx);
    tocar(ctx.archivo(), actor);
    return new BalanceoResponse(
        clusterId,
        nombre,
        FaseBalanceo.OPERARIOS.name(),
        ctx.parametro(balanceo, clusterId).getEstado(),
        0,
        asignados,
        String.join(" ", mensajes),
        aConfiguracion(ctx));
  }

  /**
   * Todo el registro vuelve a la proyección en los tres balanceos y en una sola transacción:
   * rutas, operarios, lanzamientos, historial y tramos.
   */
  public ApiResponse<Void> restablecer(Long archivoId) {
    Long actor = ActorActual.id();
    Trazas.medir(
        log,
        "SimulacionConfiguracionService.restablecer id=" + archivoId,
        () ->
            transacciones.<Void>ejecutar(
                () -> {
                  restablecerTodo(contexto(archivoId, Balanceo.LXV, true), actor);
                  return null;
                }));
    return ApiResponse.ok("Datos restablecidos.", null);
  }

  /**
   * El cluster en curso vuelve a como estaba antes de trabajarlo, en los tres balanceos: se
   * deshacen sus balanceos y lo que se cambió a mano mientras estuvo en curso (movimientos y
   * rutas que entraron o salieron de él), sin operarios de ola. Las diarias vuelven a su chute y
   * quedan libres. Los clusters cerrados no cambian.
   */
  public ApiResponse<ConfiguracionSimulacionResponse> restablecerCluster(
      Long archivoId, Long clusterId) {
    return restablecerCluster(archivoId, Balanceo.LXV, clusterId);
  }

  public ApiResponse<ConfiguracionSimulacionResponse> restablecerCluster(
      Long archivoId, Balanceo balanceo, Long clusterId) {
    Long actor = ActorActual.id();
    return escribir(
        "restablecer id=" + archivoId + " balanceo=" + balanceo + " cluster=" + clusterId,
        () -> {
          Contexto ctx = contexto(archivoId, balanceo, true);
          exigirEnCurso(ctx, clusterId);
          reiniciarTodos(ctx, List.of(clusterId));
          actualizarEstado(ctx);
          tocar(ctx.archivo(), actor);
          return aConfiguracion(ctx);
        },
        "Cluster restablecido.");
  }

  /**
   * Cierra el cluster en curso en los tres balanceos (diarias balanceadas y operarios asignados en
   * los dos días). El equipo pasa al siguiente cluster con esta dotación; al cerrar el último, la
   * simulación termina.
   */
  public ApiResponse<ConfiguracionSimulacionResponse> cerrar(
      Long archivoId, Long clusterId) {
    return cerrar(archivoId, Balanceo.LXV, clusterId);
  }

  public ApiResponse<ConfiguracionSimulacionResponse> cerrar(
      Long archivoId, Balanceo balanceo, Long clusterId) {
    Long actor = ActorActual.id();
    return escribir(
        "cerrar id=" + archivoId + " balanceo=" + balanceo + " cluster=" + clusterId,
        () -> {
          Contexto ctx = contexto(archivoId, balanceo, true);
          exigirEnCurso(ctx, clusterId);
          String nombre = ctx.nombreCluster(clusterId);
          // Los balanceos con fila para el cluster (todos, salvo registros incompletos).
          List<Balanceo> suyos =
              Arrays.stream(Balanceo.values())
                  .filter((b) -> ctx.parametro(b, clusterId) != null)
                  .toList();
          // Un día sin carga (ni rutas propias ni diarias) no tiene operarios ni cierre: no frena.
          Map<Balanceo, Long> propias = rutasPropias(archivoId, clusterId);
          boolean listo =
              suyos.stream()
                  .allMatch(
                      (b) ->
                          b.esDia()
                              ? !participa(propias, b)
                                  || ParametroCluster.ESTADO_LISTO.equals(ctx.parametro(b, clusterId).getEstado())
                              : !pendiente(ctx.parametro(b, clusterId)));
          if (!listo) {
            throw new ConflictoException(
                "Balancea las diarias, L-X-V y M-J-S y asigna los operarios de "
                    + nombre
                    + " antes de cerrarlo.");
          }
          for (Balanceo b : suyos) {
            if (!clusterId.equals(enCursoEn(ctx, b))) {
              throw new ConflictoException(nombre + " no está en curso en " + b.etiqueta() + ".");
            }
          }
          for (Balanceo b : suyos) {
            ctx.parametro(b, clusterId).setEstado(ParametroCluster.ESTADO_CERRADO);
          }
          actualizarEstado(ctx);
          tocar(ctx.archivo(), actor);
          return aConfiguracion(ctx);
        },
        "Cluster cerrado.");
  }

  /**
   * Reabre un cluster cerrado para cambiarlo (en los tres balanceos). Los clusters siguientes
   * partían de su dotación, así que vuelven a la proyección (rutas, operarios y lanzamientos); el
   * reabierto conserva sus rutas y operarios.
   */
  public ApiResponse<ConfiguracionSimulacionResponse> reabrir(
      Long archivoId, Long clusterId) {
    return reabrir(archivoId, Balanceo.LXV, clusterId);
  }

  public ApiResponse<ConfiguracionSimulacionResponse> reabrir(
      Long archivoId, Balanceo balanceo, Long clusterId) {
    Long actor = ActorActual.id();
    return escribir(
        "reabrir id=" + archivoId + " balanceo=" + balanceo + " cluster=" + clusterId,
        () -> {
          Contexto ctx = contexto(archivoId, balanceo, true);
          ParametroCluster parametro = parametroCluster(ctx, clusterId);
          if (!ParametroCluster.ESTADO_CERRADO.equals(parametro.getEstado())) {
            throw new ConflictoException(ctx.nombreCluster(clusterId) + " no está cerrado.");
          }
          List<Long> orden = orden(ctx);
          // Los siguientes partían de su dotación (en cualquier balanceo): vuelven a la proyección.
          List<Long> siguientes =
              orden.subList(orden.indexOf(clusterId) + 1, orden.size()).stream()
                  .filter(
                      (id) ->
                          Arrays.stream(Balanceo.values())
                              .anyMatch((b) -> !pendiente(ctx.parametro(b, id))))
                  .toList();
          reiniciarTodos(ctx, siguientes);
          // Vuelve al paso anterior a cerrar: diarias balanceadas y los días con operarios.
          for (Balanceo b : Balanceo.values()) {
            ParametroCluster p = ctx.parametro(b, clusterId);
            if (p != null && ParametroCluster.ESTADO_CERRADO.equals(p.getEstado())) {
              p.setEstado(b.esDia() ? ParametroCluster.ESTADO_LISTO : ParametroCluster.ESTADO_RUTAS);
            }
          }
          actualizarEstado(ctx);
          tocar(ctx.archivo(), actor);
          return aConfiguracion(ctx);
        },
        "Cluster reabierto.");
  }

  /** Vista final (clusterId nulo) o la vista del lanzamiento de un cluster. */
  public ApiResponse<DashboardSimulacionResponse> dashboard(Long archivoId, Long clusterId) {
    return dashboard(archivoId, Balanceo.LXV, clusterId);
  }

  public ApiResponse<DashboardSimulacionResponse> dashboard(
      Long archivoId, Balanceo balanceo, Long clusterId) {
    return leer(
        "dashboard id=" + archivoId + " balanceo=" + balanceo + " cluster=" + clusterId,
        () ->
            cacheado(
                archivoId,
                balanceo,
                "dashboard:" + clusterId,
                (ctx) -> {
                  if (clusterId != null) {
                    parametroCluster(ctx, clusterId);
                  }
                  return calcularDashboard(ctx, clusterId);
                }),
        "Dashboard de simulación.");
  }

  // ------------------------------------------------------------------
  // Balanceo
  // ------------------------------------------------------------------

  // ---------------- Paso 1: rutas ----------------

  /**
   * Paso 1 del cluster en curso: reparte sus rutas hasta que su carga (bultos)
   * quede pareja, grupo por grupo. Las rutas de un grupo nunca se mezclan con las
   * de otro: con varios grupos, el rango es obligatorio y se divide en tramos de
   * chutes seguidos, uno por grupo en el orden de su prioridad, del tamaño que
   * le toca por sus bultos ({@link TramosGrupos}). Luego cada grupo se balancea
   * solo dentro de su tramo: primero entran sus rutas que están fuera (también
   * las movidas a mano a otro tramo; la más pesada primero, al chute del tramo
   * con menos carga) y después se iguala la carga entre sus chutes. Con un solo
   * grupo es el balanceo de siempre: con rango, todo el rango; sin rango, los
   * chutes que ya usa. No toca operarios.
   *
   * <p>Diarias va primero: reparte las diarias y define el rango y los tramos (solo mientras los
   * dos días sigan pendientes; si no, hay que restablecer el cluster). Cada día después mueve solo
   * sus rutas dentro de ese rango y esos tramos, con las diarias como carga fija.
   */
  private BalanceoResponse balancearRutas(
      Long archivoId, Balanceo balanceo, Long clusterId, BalanceoRequest pedido, Long actor) {
    Contexto ctx = contexto(archivoId, balanceo, true);
    ParametroCluster parametro = exigirEnCurso(ctx, clusterId);
    String nombre = ctx.nombreCluster(clusterId);
    ParametroCluster diarias = ctx.parametro(Balanceo.DIA, clusterId);
    if (balanceo == Balanceo.DIA) {
      for (Balanceo dia : Balanceo.DIAS) {
        if (!pendiente(ctx.parametro(dia, clusterId))) {
          throw new ConflictoException(
              nombre
                  + " ya tiene balanceado "
                  + dia.etiqueta()
                  + " sobre estas diarias: restablece el cluster para volver a balancearlas.");
        }
      }
    } else if (pendiente(diarias)) {
      throw new ConflictoException(
          "Primero balancea las diarias de " + nombre + ": ahí se elige el rango de chutes.");
    } else if (rutasPropias(archivoId, clusterId).get(balanceo) == 0) {
      // Sin rutas propias no hay nada que mover: el día no se balancea (no frena los operarios).
      throw new ConflictoException(
          nombre + " no tiene rutas " + balanceo.etiqueta() + ": no hay nada que balancear.");
    }
    // Un día usa el rango y los tramos que definieron las diarias.
    boolean heredado = balanceo.esDia();
    List<Long> rango =
        heredado ? rangoGuardado(ctx, diarias) : rangoDeBalanceo(ctx, parametro, pedido);
    List<RutaProyeccion> todasCluster =
        rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(archivoId, clusterId);
    List<RutaProyeccion> rutasCluster =
        todasCluster.stream().filter((r) -> visible(ctx, r.getFrecuencia())).toList();
    // En un día las diarias ya están en su sitio: cuentan como carga pero no se mueven.
    Set<Long> inmoviles =
        rutasCluster.stream()
            .filter((r) -> !balanceo.mueve(r.getFrecuencia()))
            .map(RutaProyeccion::getId)
            .collect(Collectors.toSet());
    Map<String, List<RutaProyeccion>> porGrupo = new LinkedHashMap<>();
    rutasCluster.forEach(
        (ruta) -> porGrupo.computeIfAbsent(grupo(ruta), (g) -> new ArrayList<>()).add(ruta));
    // Los tramos son del cluster (valen para los dos balanceos): se reparten con
    // todas sus rutas, de las tres frecuencias.
    Map<String, List<RutaProyeccion>> porGrupoTodas = new LinkedHashMap<>();
    todasCluster.forEach(
        (ruta) -> porGrupoTodas.computeIfAbsent(grupo(ruta), (g) -> new ArrayList<>()).add(ruta));
    List<GrupoGuardado> guardados = gruposGuardados(archivoId, clusterId);
    List<TramosGrupos.Tramo> tramos;
    int sinUsar = 0;
    if (heredado) {
      tramos = tramosGuardados(ctx, rango, porGrupoTodas, guardados);
      // Un grupo que llegó después (p. ej. una ruta cambiada de cluster) no tiene tramo: sin él
      // se mezclaría con otros grupos.
      List<String> sinTramo =
          tramos.stream().filter((t) -> t.chutes() == null).map(TramosGrupos.Tramo::grupo).toList();
      if (porGrupoTodas.size() > 1 && !sinTramo.isEmpty()) {
        throw new ConflictoException(
            nombre
                + ": "
                + (sinTramo.size() == 1 ? "el grupo " : "los grupos ")
                + String.join(", ", sinTramo)
                + " no tiene(n) tramo de chutes (llegaron después de balancear las diarias)."
                + " Restablece el cluster para repartir de nuevo sus grupos.");
      }
    } else {
      // Sin prioridad: los grupos van en orden natural (1, 2, 3…), cada uno en su tramo.
      List<String> orden = ordenNatural(porGrupoTodas.keySet());
      if (porGrupoTodas.size() > 1) {
        if (rango == null) {
          throw new SolicitudInvalidaException(
              nombre
                  + " tiene "
                  + porGrupoTodas.size()
                  + " grupos: elige el chute inicial y el final del rango para repartirlos.");
        }
        exigirChutesParaGrupos(nombre, rango.size(), porGrupoTodas.size());
        TramosGrupos.Reparto reparto =
            TramosGrupos.repartir(rango, cargaDelDia(orden, porGrupoTodas));
        tramos = reparto.tramos();
        sinUsar = reparto.sinUsar();
      } else {
        // Un solo grupo (o ninguno): el balanceo de siempre sobre todo el rango.
        tramos =
            cargaDelDia(orden, porGrupoTodas).stream()
                .map((g) -> new TramosGrupos.Tramo(g.nombre(), 1, g.rutas(), g.bultos(), rango))
                .toList();
      }
    }
    Set<Long> chutesBuff =
        ctx.parametrosChute().entrySet().stream()
            .filter((e) -> e.getValue().isBuff() && !bloqueado(ctx, e.getKey()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    // Volver a balancear un cluster ya balanceado (p. ej. tras pasarle rutas de
    // otro cluster) reparte desde como está hoy; si ya tenía operarios, su
    // carga cambia y vuelve al paso 2.
    invalidarOperarios(ctx, List.of(clusterId));
    Integer dispersionAntes = dispersionPorChute(rutasCluster);
    List<Traslado> nivelaciones = new ArrayList<>();
    Map<Long, Long> chutesNuevos = new LinkedHashMap<>();
    int fueraDelRango = 0;
    int ancladas = 0;
    int libres = 0;
    boolean tope = false;
    for (TramosGrupos.Tramo tramo : tramos) {
      List<RutaProyeccion> rutasGrupo = porGrupo.getOrDefault(tramo.grupo(), List.of());
      // Cada grupo se compara solo consigo mismo: su propio reparto. Las diarias
      // fijas cuentan como carga pero no se mueven.
      Reparto reparto = new Reparto(rutasGrupo, inmoviles);
      int traidas = 0;
      Traslado traslado;
      if (tramo.chutes() != null) {
        reparto.limitar(clusterId, tramo.chutes());
      }
      // 1. El buff primero: los chutes con buff del tramo toman las rutas más pesadas del grupo
      // (estén donde estén), hasta la carga promedio, y quedan fijas ahí. Siempre deja una ruta
      // para cada chute sin buff que el día pueda llenar.
      if (!chutesBuff.isEmpty()) {
        nivelaciones.addAll(reparto.anclar(clusterId, chutesBuff));
        ancladas += reparto.ancladas();
        traidas += reparto.deFuera();
      }
      if (tramo.chutes() != null) {
        // 2. Con lo que quedó, el tramo usa solo los chutes que puede llenar, en un bloque continuo
        // alrededor del buff: los demás quedan libres, sin huecos entre los usados.
        reparto.ajustarRango(clusterId);
        libres += reparto.libres();
        // 3. Lo que está fuera entra al chute con menos carga. Cada ruta entra una sola vez.
        while ((traslado = reparto.traerAlRango(clusterId)) != null) {
          nivelaciones.add(traslado);
          traidas++;
        }
      }
      fueraDelRango += traidas;
      // 4. Ningún chute del tramo sin rutas: primero se llenan los vacíos, luego se empareja todo
      // en torno al buff (sus rutas fijas cuentan como carga).
      List<Traslado> llenados = reparto.llenarVacios(clusterId);
      nivelaciones.addAll(llenados);
      // Cada movimiento baja la suma de cuadrados de la carga, así que el
      // proceso termina solo; el tope es una red de seguridad.
      int maximo = traidas + Math.max(MAX_ITERACIONES, 4 * reparto.rutasDe(clusterId));
      int pasos = traidas;
      while (pasos < maximo) {
        traslado = reparto.nivelar(clusterId);
        if (traslado != null) {
          nivelaciones.add(traslado);
        } else {
          // Ninguna ruta sola mejora: se prueba cambiar una ruta por otra entre dos chutes.
          List<Traslado> cambio = reparto.intercambiar(clusterId);
          if (cambio.isEmpty()) {
            break;
          }
          nivelaciones.addAll(cambio);
        }
        pasos++;
      }
      tope |= pasos >= maximo;
      chutesNuevos.putAll(reparto.chutesNuevos());
    }

    int movidas =
        (int) nivelaciones.stream().map((t) -> t.ruta().getId()).distinct().count();
    StringBuilder mensaje = new StringBuilder(nombre);
    if (rango != null) {
      mensaje
          .append(" (")
          .append(ctx.nombreChute(rango.getFirst()))
          .append(" → ")
          .append(ctx.nombreChute(rango.getLast()))
          .append(")");
    }
    mensaje.append(": ").append(movidas).append(" ruta(s) movida(s)");
    if (fueraDelRango > 0) {
      mensaje
          .append(", ")
          .append(fueraDelRango)
          .append(tramos.size() > 1 ? " desde chutes fuera de su tramo" : " desde chutes fuera del rango");
    }
    mensaje.append(".");
    if (!inmoviles.isEmpty()) {
      mensaje
          .append(" ")
          .append(inmoviles.size())
          .append(" ruta(s) diaria(s) ya fijas: no se movieron.");
    }
    if (ancladas > 0) {
      mensaje.append(" ").append(ancladas).append(" ruta(s) pesada(s) a chutes con buff.");
    }
    if (libres > 0) {
      long rutasDia = rutasCluster.stream().filter((r) -> balanceo.mueve(r.getFrecuencia())).count();
      mensaje
          .append(" ")
          .append(balanceo.etiqueta())
          .append(": ")
          .append(rutasDia)
          .append(" ruta(s) para su tramo; ")
          .append(libres)
          .append(" chute(s) quedan libres ese día; los usados quedan juntos, sin huecos.");
    }
    if (heredado) {
      mensaje.append(" Rango y tramos: los de las diarias.");
    }
    if (tramos.size() > 1) {
      mensaje.append(" Tramos de grupos: ");
      for (int i = 0; i < tramos.size(); i++) {
        TramosGrupos.Tramo tramo = tramos.get(i);
        if (tramo.chutes() == null || tramo.chutes().isEmpty()) {
          mensaje.append(i == 0 ? "" : "; ").append(tramo.grupo()).append(" sin tramo");
          continue;
        }
        mensaje
            .append(i == 0 ? "" : "; ")
            .append(tramo.grupo())
            .append(" ")
            .append(ctx.nombreChute(tramo.chutes().getFirst()));
        if (tramo.chutes().size() > 1) {
          mensaje.append(" → ").append(ctx.nombreChute(tramo.chutes().getLast()));
        }
        mensaje.append(" (").append(tramo.chutes().size()).append(" chute(s))");
      }
      mensaje.append(".");
      if (sinUsar > 0) {
        mensaje
            .append(" ")
            .append(sinUsar)
            .append(" chute(s) del rango quedan libres: los grupos tienen menos rutas que chutes.");
      }
    }
    Integer dispersionDespues = dispersionPorChute(rutasCluster);
    if (dispersionAntes != null) {
      mensaje
          .append(" Diferencia de carga entre sus chutes: ")
          .append(dispersionAntes)
          .append(" % → ")
          .append(dispersionDespues == null ? 0 : dispersionDespues)
          .append(" %.");
    }
    if (tope) {
      mensaje.append(" Se alcanzó el máximo de movimientos por ejecución.");
    }

    // Las rutas se leyeron en solo lectura: los chutes nuevos se guardan en lote.
    lotes.moverRutas(chutesNuevos);
    if (rango != null) {
      parametro.setChuteDesdeId(rango.getFirst());
      parametro.setChuteHastaId(rango.getLast());
    }
    // Prioridad y tramo de cada grupo: el modal los propone la próxima vez y la
    // configuración muestra dónde quedó cada grupo. Si los definió el otro
    // balanceo, se quedan como están (son del cluster).
    if (!heredado) {
      lotes.guardarGrupos(
          archivoId,
          clusterId,
          tramos.stream()
              .map(
                  (t) ->
                      new GrupoGuardado(
                          clusterId,
                          t.grupo(),
                          t.prioridad(),
                          t.chutes() == null ? null : t.chutes().getFirst(),
                          t.chutes() == null ? null : t.chutes().getLast()))
              .toList());
    }
    Instant momento = Instant.now();
    LanzamientoBalanceo lanzamiento =
        lanzar(ctx, clusterId, FaseBalanceo.RUTAS, mensaje.toString(), actor, momento);
    lanzamiento.setRutas(nivelaciones.size());
    Long lanzamientoId = lanzamiento.getId();
    List<HistorialBalanceo> logs = new ArrayList<>();
    nivelaciones.forEach(
        (t) ->
            logs.add(
                movimiento(
                    archivoId,
                    lanzamientoId,
                    t,
                    HistorialBalanceo.MOTIVO_BALANCEO,
                    actor,
                    momento)));
    logs.forEach((l) -> l.setBalanceo(ctx.balanceo().name()));
    lotes.insertarHistorial(logs);
    parametro.setEstado(ParametroCluster.ESTADO_RUTAS);
    actualizarEstado(ctx);
    tocar(ctx.archivo(), actor);
    return new BalanceoResponse(
        clusterId,
        nombre,
        FaseBalanceo.RUTAS.name(),
        parametro.getEstado(),
        movidas,
        0,
        mensaje.toString(),
        aConfiguracion(ctx));
  }

  /**
   * Vista previa del paso de rutas: los grupos del cluster en orden natural y el tramo que
   * recibiría cada uno. En Diarias, con el rango pedido (o el último); en un día, el rango y los
   * tramos que ya definieron las diarias. «Fuera» cuenta solo las rutas que este balanceo movería
   * (en un día, las diarias ya están fijas). No cambia nada; si así no se podría balancear, lo dice
   * en {@code aviso}.
   */
  public ApiResponse<VistaBalanceoResponse> vistaBalanceo(
      Long archivoId, Long clusterId, BalanceoRequest pedido) {
    return vistaBalanceo(archivoId, Balanceo.LXV, clusterId, pedido);
  }

  public ApiResponse<VistaBalanceoResponse> vistaBalanceo(
      Long archivoId, Balanceo balanceo, Long clusterId, BalanceoRequest pedido) {
    return leer(
        "vista balanceo id=" + archivoId + " balanceo=" + balanceo + " cluster=" + clusterId,
        () -> {
          Contexto ctx = contexto(archivoId, balanceo, false);
          ParametroCluster parametro = parametroCluster(ctx, clusterId);
          String nombre = ctx.nombreCluster(clusterId);
          // Rutas que este balanceo movería, por grupo y chute (para contar las que quedan fuera).
          Map<String, Map<Long, Long>> rutasPorChute = new LinkedHashMap<>();
          // Carga de cada grupo en su día más exigente (diarias + el mayor de L-X-V y M-J-S).
          Map<String, CargaDia> porDia = new LinkedHashMap<>();
          for (CargaGrupo fila : rutaRepo.cargaPorGrupo(archivoId)) {
            if (!clusterId.equals(fila.getClusterId()) || fila.getRutas() == null || fila.getRutas() == 0) {
              continue;
            }
            String g = fila.getGrupo() == null ? "" : fila.getGrupo();
            if (balanceo.mueve(fila.getFrecuencia())) {
              rutasPorChute
                  .computeIfAbsent(g, (k) -> new HashMap<>())
                  .merge(fila.getChuteId(), fila.getRutas(), Long::sum);
            }
            porDia
                .computeIfAbsent(g, (k) -> new CargaDia())
                .sumar(fila.getFrecuencia(), fila.getRutas(), ceroSiNulo(fila.getBultos()));
          }
          Map<String, TramosGrupos.Grupo> grupos = new LinkedHashMap<>();
          porDia.forEach((g, carga) -> grupos.put(g, carga.grupo(g)));
          List<TramosGrupos.Grupo> enOrden = ordenNatural(grupos.keySet()).stream().map(grupos::get).toList();
          String aviso = null;
          List<Long> rango = null;
          List<TramosGrupos.Tramo> tramos = new ArrayList<>();
          int sinUsar = 0;
          boolean heredado = balanceo.esDia();
          if (heredado) {
            ParametroCluster diarias = ctx.parametro(Balanceo.DIA, clusterId);
            if (pendiente(diarias)) {
              aviso = "Primero balancea las diarias de " + nombre + ": ahí se elige el rango de chutes.";
            } else {
              rango = rangoGuardado(ctx, diarias);
            }
            Map<String, GrupoGuardado> porClave = new HashMap<>();
            gruposGuardados(archivoId, clusterId)
                .forEach((g) -> porClave.putIfAbsent(SimulacionExcel.clave(g.grupo()), g));
            for (TramosGrupos.Grupo g : enOrden) {
              GrupoGuardado guardado = porClave.get(SimulacionExcel.clave(g.nombre()));
              List<Long> chutes =
                  aviso != null
                      ? null
                      : grupos.size() == 1
                          ? rango
                          : guardado == null
                              ? null
                              : chutesEntre(ctx, guardado.chuteDesdeId(), guardado.chuteHastaId());
              if (aviso == null && chutes == null && grupos.size() > 1) {
                aviso =
                    "El grupo "
                        + g.nombre()
                        + " no tiene tramo de chutes (llegó después de balancear las diarias):"
                        + " restablece el cluster para repartir de nuevo sus grupos.";
              }
              tramos.add(new TramosGrupos.Tramo(g.nombre(), tramos.size() + 1, g.rutas(), g.bultos(), chutes));
            }
          } else {
            try {
              rango = rangoDeBalanceo(ctx, parametro, pedido);
              if (rango == null && grupos.size() > 1) {
                aviso = "Elige el chute inicial y el final del rango para repartir sus " + grupos.size() + " grupos.";
              } else if (rango != null && grupos.size() > 1) {
                exigirChutesParaGrupos(nombre, rango.size(), grupos.size());
              }
            } catch (SolicitudInvalidaException error) {
              aviso = error.getMessage();
            }
            if (aviso == null && rango != null && grupos.size() > 1) {
              TramosGrupos.Reparto reparto = TramosGrupos.repartir(rango, enOrden);
              tramos = reparto.tramos();
              sinUsar = reparto.sinUsar();
            } else {
              List<Long> todo = aviso == null && grupos.size() == 1 ? rango : null;
              for (TramosGrupos.Grupo g : enOrden) {
                tramos.add(new TramosGrupos.Tramo(g.nombre(), tramos.size() + 1, g.rutas(), g.bultos(), todo));
              }
            }
          }
          List<TramoGrupoResponse> respuesta =
              tramos.stream()
                  .map(
                      (t) -> {
                        List<Long> chutes = t.chutes();
                        long fuera = 0;
                        if (chutes != null) {
                          Set<Long> dentro = new HashSet<>(chutes);
                          for (Map.Entry<Long, Long> e :
                              rutasPorChute.getOrDefault(t.grupo(), Map.of()).entrySet()) {
                            if (!dentro.contains(e.getKey())) {
                              fuera += e.getValue();
                            }
                          }
                        }
                        return new TramoGrupoResponse(
                            t.grupo(),
                            t.prioridad(),
                            t.rutas(),
                            t.bultos(),
                            chutes == null ? 0 : chutes.size(),
                            chutes == null ? null : chutes.getFirst(),
                            chutes == null ? null : ctx.nombreChute(chutes.getFirst()),
                            chutes == null ? null : chutes.getLast(),
                            chutes == null ? null : ctx.nombreChute(chutes.getLast()),
                            fuera);
                      })
                  .toList();
          return new VistaBalanceoResponse(
              clusterId,
              nombre,
              rango == null ? null : rango.size(),
              sinUsar,
              aviso,
              respuesta,
              heredado ? Balanceo.DIA.name() : null);
        },
        "Vista previa del balanceo.");
  }

  /** Cada grupo necesita al menos un chute del rango. */
  private static void exigirChutesParaGrupos(String nombre, int chutes, int grupos) {
    if (chutes < grupos) {
      throw new SolicitudInvalidaException(
          "El rango tiene "
              + chutes
              + " chute(s) que se pueden usar y "
              + nombre
              + " tiene "
              + grupos
              + " grupos: cada grupo necesita al menos un chute. Amplía el rango.");
    }
  }

  /** Grupos guardados del cluster (prioridad y último tramo), en orden de prioridad. */
  private List<GrupoGuardado> gruposGuardados(Long archivoId, Long clusterId) {
    return lotes.grupos(archivoId).stream()
        .filter((g) -> clusterId.equals(g.clusterId()))
        .sorted(Comparator.comparingInt(GrupoGuardado::prioridad))
        .toList();
  }

  /** Grupos en orden natural (1, 2, 10…): ya no hay prioridad elegida a mano. */
  private static List<String> ordenNatural(Collection<String> grupos) {
    return grupos.stream().sorted(ORDEN_NATURAL).toList();
  }

  /**
   * Carga de cada grupo en su día más pesado, en el orden dado: un chute lleva las diarias más
   * las L-X-V unos días y las M-J-S otros, así que el tramo del grupo se dimensiona con las
   * diarias más la mayor de las dos (no con la suma, que no ocurre ningún día).
   */
  private static List<TramosGrupos.Grupo> cargaDelDia(
      List<String> orden, Map<String, List<RutaProyeccion>> porGrupo) {
    return orden.stream()
        .map(
            (g) -> {
              CargaDia carga = new CargaDia();
              porGrupo
                  .getOrDefault(g, List.of())
                  .forEach((r) -> carga.sumar(r.getFrecuencia(), 1L, ceroSiNulo(r.getTotalBultos())));
              return carga.grupo(g);
            })
        .toList();
  }

  /** Rutas y bultos de un grupo por frecuencia, para sacar su día más pesado. */
  private static final class CargaDia {
    private long rutasDiarias;
    private long rutasLxv;
    private long rutasMjs;
    private BigDecimal bultosDiarias = BigDecimal.ZERO;
    private BigDecimal bultosLxv = BigDecimal.ZERO;
    private BigDecimal bultosMjs = BigDecimal.ZERO;

    void sumar(String frecuencia, Long rutas, BigDecimal bultos) {
      long n = rutas == null ? 0 : rutas;
      if (Balanceo.LXV.name().equals(frecuencia)) {
        rutasLxv += n;
        bultosLxv = bultosLxv.add(bultos);
      } else if (Balanceo.MJS.name().equals(frecuencia)) {
        rutasMjs += n;
        bultosMjs = bultosMjs.add(bultos);
      } else {
        rutasDiarias += n;
        bultosDiarias = bultosDiarias.add(bultos);
      }
    }

    /**
     * Las diarias más el día más exigente, por separado para rutas y bultos: el tramo necesita
     * chutes para las rutas del día con más rutas y capacidad para los bultos del día con más
     * bultos (pueden ser días distintos).
     */
    TramosGrupos.Grupo grupo(String nombre) {
      return new TramosGrupos.Grupo(
          nombre,
          Math.max(1, rutasDiarias + Math.max(rutasLxv, rutasMjs)),
          bultosDiarias.add(bultosLxv.max(bultosMjs)));
    }
  }


  /** Diferencia de carga entre los chutes de esas rutas (todas de un cluster), en %. */
  private static Integer dispersionPorChute(List<RutaProyeccion> rutas) {
    Map<Long, BigDecimal> porChute = new HashMap<>();
    rutas.forEach(
        (r) ->
            porChute.merge(
                r.getChuteId(),
                r.getTotalBultos() == null ? BigDecimal.ZERO : r.getTotalBultos(),
                BigDecimal::add));
    return diferenciaBultos(porChute.values());
  }

  /**
   * Chutes del rango de balanceo en el orden natural de las columnas (CHUTE 2
   * antes que CHUTE 10), «desde» y «hasta» incluidos; invertidos se toman al
   * revés. Sin pedido, el último rango guardado del cluster si sus chutes siguen
   * en el registro; si no hay ninguno, null (se nivela entre los que ya usa).
   */
  private List<Long> rangoDeBalanceo(
      Contexto ctx, ParametroCluster parametro, BalanceoRequest solicitud) {
    // El cliente manda {} cuando no hay rango: equivale a no mandarlo.
    BalanceoRequest pedido = solicitud == null || solicitud.sinRango() ? null : solicitud;
    if (pedido != null && (pedido.chuteDesdeId() == null || pedido.chuteHastaId() == null)) {
      throw new SolicitudInvalidaException("Elige el chute inicial y el final del rango.");
    }
    Long desde = pedido != null ? pedido.chuteDesdeId() : parametro.getChuteDesdeId();
    Long hasta = pedido != null ? pedido.chuteHastaId() : parametro.getChuteHastaId();
    if (desde == null || hasta == null) {
      return null;
    }
    for (Long chute : List.of(desde, hasta)) {
      if (!ctx.parametrosChute().containsKey(chute) || bloqueado(ctx, chute)) {
        if (pedido == null) {
          // El rango guardado ya no sirve (el chute salió del registro o se
          // bloqueó): se ignora.
          return null;
        }
        if (bloqueado(ctx, chute)) {
          throw new SolicitudInvalidaException(
              "El " + etiquetaChute(ctx, chute) + " está bloqueado: elige otro chute para el rango.");
        }
        throw new SolicitudInvalidaException("El chute " + chute + " no pertenece al registro.");
      }
    }
    // Los bloqueados no cuentan: «desde 18 hasta 22» reparte en 18 y 22.
    List<Long> ordenados =
        ctx.parametrosChute().keySet().stream()
            .filter((chute) -> !bloqueado(ctx, chute))
            .sorted(Comparator.comparing(ctx::nombreChute, PlanoChutes.ORDEN))
            .toList();
    int a = ordenados.indexOf(desde);
    int b = ordenados.indexOf(hasta);
    return List.copyOf(ordenados.subList(Math.min(a, b), Math.max(a, b) + 1));
  }

  /**
   * Chutes entre «desde» y «hasta» (incluidos) en el orden de las columnas, sin los bloqueados.
   * Null si falta alguno o ya no es del registro.
   */
  private List<Long> chutesEntre(Contexto ctx, Long desde, Long hasta) {
    if (desde == null || hasta == null) {
      return null;
    }
    List<Long> ordenados =
        ctx.parametrosChute().keySet().stream()
            .filter((chute) -> !bloqueado(ctx, chute))
            .sorted(Comparator.comparing(ctx::nombreChute, PlanoChutes.ORDEN))
            .toList();
    int a = ordenados.indexOf(desde);
    int b = ordenados.indexOf(hasta);
    if (a < 0 || b < 0) {
      return null;
    }
    return List.copyOf(ordenados.subList(Math.min(a, b), Math.max(a, b) + 1));
  }

  /** Rango que ya usó un cluster (en el otro balanceo); null si no eligió ninguno. */
  private List<Long> rangoGuardado(Contexto ctx, ParametroCluster parametro) {
    return parametro == null
        ? null
        : chutesEntre(ctx, parametro.getChuteDesdeId(), parametro.getChuteHastaId());
  }

  /**
   * Tramos de los grupos tal como los dejó el primer balanceo del cluster (son del cluster). Con
   * un solo grupo, todo el rango. Un grupo sin tramo guardado se nivela entre los chutes que ya
   * usa.
   */
  private List<TramosGrupos.Tramo> tramosGuardados(
      Contexto ctx,
      List<Long> rango,
      Map<String, List<RutaProyeccion>> porGrupo,
      List<GrupoGuardado> guardados) {
    Map<String, GrupoGuardado> porClave = new HashMap<>();
    guardados.forEach((g) -> porClave.putIfAbsent(SimulacionExcel.clave(g.grupo()), g));
    List<String> orden = ordenNatural(porGrupo.keySet());
    List<TramosGrupos.Tramo> tramos = new ArrayList<>();
    for (TramosGrupos.Grupo g : cargaDelDia(orden, porGrupo)) {
      GrupoGuardado guardado = porClave.get(SimulacionExcel.clave(g.nombre()));
      List<Long> chutes =
          porGrupo.size() == 1
              ? rango
              : guardado == null
                  ? null
                  : chutesEntre(ctx, guardado.chuteDesdeId(), guardado.chuteHastaId());
      // Orden natural: la posición es el número del tramo (ya no hay prioridad elegida).
      tramos.add(new TramosGrupos.Tramo(g.nombre(), tramos.size() + 1, g.rutas(), g.bultos(), chutes));
    }
    return tramos;
  }

  /**
   * Reparto de rutas por carga de un cluster. Cada cluster es una ola que
   * corre sola, así que lo que se iguala es la carga de ese cluster en cada
   * chute que lo atiende (no la carga total del chute): solo necesita y mueve
   * las rutas del cluster lanzado. Las rutas de cada (cluster, chute) van
   * ordenadas por bultos: elegir la ruta a mover cuesta log(n), no recorrer
   * todas.
   */
  private static final class Reparto {
    /** La ola se da por pareja si la diferencia entre chutes es ≤ 10 % de su promedio. */
    private static final BigDecimal TOLERANCIA_CARGA = new BigDecimal("0.10");

    /** Bultos de cada cluster por chute (las rutas fijas también cuentan). */
    private final Map<Long, Map<Long, BigDecimal>> carga = new HashMap<>();
    /** Rutas que se pueden mover de cada (cluster, chute), agrupadas por bultos. */
    private final Map<Par, TreeMap<BigDecimal, ArrayDeque<RutaProyeccion>>> rutas = new HashMap<>();
    /** Rutas que se pueden mover en cada (cluster, chute). */
    private final Map<Par, Integer> cuenta = new HashMap<>();
    /**
     * Rutas fijas de cada (cluster, chute): diarias ya acomodadas por un balanceo anterior o
     * rutas pesadas ancladas en un chute con buff. Suman carga pero no se mueven.
     */
    private final Map<Par, Integer> fijas = new HashMap<>();
    private final Map<Long, Integer> rutasPorCluster = new HashMap<>();
    /** Chute final de cada ruta movida, para guardarlo en lote. */
    private final Map<Long, Long> chutesNuevos = new LinkedHashMap<>();
    /**
     * Rango de balanceo, en orden: sus chutes siguen en la ola aunque queden en
     * 0 (son destino válido); vacío = sin rango.
     */
    private final List<Long> rango = new ArrayList<>();
    /** Chutes del tramo que quedaron fuera porque el día tiene menos rutas que chutes. */
    private int libres;
    /** Rutas pesadas que quedaron fijas en un chute con buff (traídas o que ya estaban ahí). */
    private int ancladas;
    /** Rutas que el buff trajo desde chutes fuera del tramo. */
    private int deFuera;

    Reparto(List<RutaProyeccion> rutasCluster) {
      this(rutasCluster, Set.of());
    }

    /** {@code inmoviles}: ids de las rutas que cuentan como carga pero el balanceo no mueve. */
    Reparto(List<RutaProyeccion> rutasCluster, Set<Long> inmoviles) {
      for (RutaProyeccion ruta : rutasCluster) {
        if (inmoviles.contains(ruta.getId())) {
          fijas.merge(new Par(ruta.getClusterId(), ruta.getChuteId()), 1, Integer::sum);
          sumar(ruta.getClusterId(), ruta.getChuteId(), ruta.getTotalBultos());
        } else {
          poner(ruta, ruta.getClusterId(), ruta.getChuteId());
          rutasPorCluster.merge(ruta.getClusterId(), 1, Integer::sum);
        }
      }
    }

    Map<Long, Long> chutesNuevos() {
      return chutesNuevos;
    }

    int rutasDe(Long cluster) {
      return rutasPorCluster.getOrDefault(cluster, 0);
    }

    /**
     * Diferencia de carga de la ola: (chute más cargado − menos cargado) /
     * promedio, en %. Null si la ola tiene menos de dos chutes con carga.
     */
    Integer dispersion(Long cluster) {
      return diferenciaBultos(carga.getOrDefault(cluster, Map.of()).values());
    }

    /** Fija el rango: sus chutes entran a la ola del cluster con carga 0 si aún no la tenían. */
    void limitar(Long cluster, List<Long> chutesRango) {
      rango.addAll(chutesRango);
      Map<Long, BigDecimal> porChute = carga.computeIfAbsent(cluster, (id) -> new HashMap<>());
      chutesRango.forEach((chute) -> porChute.putIfAbsent(chute, BigDecimal.ZERO));
    }

    /**
     * Saca una ruta de un chute fuera del rango (el de menor id primero, su ruta
     * más pesada) y la lleva al chute del rango con menos carga; a igual carga,
     * el primero del rango. Las rutas fijas fuera del rango se quedan donde
     * están. Null cuando ya no quedan rutas que se puedan traer.
     */
    Traslado traerAlRango(Long cluster) {
      Map<Long, BigDecimal> porChute = carga.getOrDefault(cluster, Map.of());
      Long origen =
          porChute.keySet().stream()
              .filter((chute) -> !rango.contains(chute) && rutasEn(cluster, chute) > 0)
              .min(Comparator.naturalOrder())
              .orElse(null);
      if (origen == null) {
        return null;
      }
      Long destino = rango.getFirst();
      for (Long chute : rango) {
        if (porChute.get(chute).compareTo(porChute.get(destino)) < 0) {
          destino = chute;
        }
      }
      RutaProyeccion ruta = rutas.get(new Par(cluster, origen)).lastEntry().getValue().peekFirst();
      trasladar(ruta, cluster, origen, destino);
      return new Traslado(ruta, origen, destino);
    }

    /** Rutas que se pueden mover en ese chute. */
    private int rutasEn(Long cluster, Long chute) {
      return cuenta.getOrDefault(new Par(cluster, chute), 0);
    }

    /**
     * Chutes de la ola donde se nivela: con rango, los del rango en su orden (a igual carga gana el
     * primero del tramo, así los libres quedan al final); sin él, los que ya usa, por id.
     */
    private List<Long> chutesDeOla(Long cluster) {
      Map<Long, BigDecimal> porChute = carga.getOrDefault(cluster, Map.of());
      if (rango.isEmpty()) {
        return porChute.keySet().stream().sorted().toList();
      }
      return rango.stream().filter(porChute::containsKey).toList();
    }

    /** Chutes del tramo que este reparto deja sin rutas: el día tiene menos rutas que chutes. */
    int libres() {
      return libres;
    }

    int ancladas() {
      return ancladas;
    }

    /** Rutas que el buff trajo desde chutes fuera del tramo. */
    int deFuera() {
      return deFuera;
    }

    /**
     * Va después de anclar el buff: si las rutas movibles que quedan no alcanzan para todos los
     * chutes del tramo, lo recorta a un bloque continuo. Quedan los chutes con rutas fijas (diarias
     * o ancladas en el buff) y, de los demás, tantos como rutas movibles hay: primero los huecos
     * entre chutes con rutas fijas, luego los vecinos hacia el inicio del tramo y por último hacia el
     * final. Así no quedan chutes vacíos entre dos usados (p. ej. entre dos buff). Va antes de traer
     * rutas al rango, así lo que estaba en un chute recortado entra a los que quedan.
     */
    void ajustarRango(Long cluster) {
      if (rango.isEmpty()) {
        return;
      }
      int primero = -1;
      int ultimo = -1;
      Set<Long> quedanEnTramo = new HashSet<>();
      for (int i = 0; i < rango.size(); i++) {
        if (fijas.containsKey(new Par(cluster, rango.get(i)))) {
          quedanEnTramo.add(rango.get(i));
          primero = primero < 0 ? i : primero;
          ultimo = i;
        }
      }
      int faltan = rutasDe(cluster);
      if (faltan >= rango.size() - quedanEnTramo.size()) {
        return;
      }
      // Sin rutas fijas el bloque empieza en el primer chute del tramo.
      if (primero < 0) {
        primero = 0;
        ultimo = -1;
      }
      for (int i = primero; i <= ultimo && faltan > 0; i++) {
        faltan -= quedanEnTramo.add(rango.get(i)) ? 1 : 0;
      }
      for (int i = primero - 1; i >= 0 && faltan > 0; i--) {
        faltan -= quedanEnTramo.add(rango.get(i)) ? 1 : 0;
      }
      for (int i = ultimo + 1; i < rango.size() && faltan > 0; i++) {
        faltan -= quedanEnTramo.add(rango.get(i)) ? 1 : 0;
      }
      Set<Long> fuera = new HashSet<>(rango);
      fuera.removeAll(quedanEnTramo);
      rango.removeIf(fuera::contains);
      Map<Long, BigDecimal> porChute = carga.getOrDefault(cluster, Map.of());
      // Un chute recortado sin rutas sale de la ola (si tiene rutas, traerAlRango las saca).
      fuera.forEach((chute) -> {
        if (rutasEn(cluster, chute) == 0) {
          porChute.remove(chute);
        }
      });
      libres = fuera.size();
    }

    /**
     * Llena los chutes del rango sin carga, en el orden del tramo: cada uno recibe una ruta del
     * chute con más rutas movibles que no se quede vacío por darla (con dos o más, o con alguna
     * fija). Entre sus rutas, la más cercana a la carga promedio de la ola. Devuelve los traslados.
     */
    List<Traslado> llenarVacios(Long cluster) {
      List<Traslado> hechos = new ArrayList<>();
      if (rango.isEmpty()) {
        return hechos;
      }
      Map<Long, BigDecimal> porChute = carga.computeIfAbsent(cluster, (id) -> new HashMap<>());
      for (Long vacio : rango) {
        if (porChute.getOrDefault(vacio, BigDecimal.ZERO).signum() > 0 || rutasEn(cluster, vacio) > 0) {
          continue;
        }
        Long origen = null;
        for (Long chute : chutesDeOla(cluster)) {
          int movibles = rutasEn(cluster, chute);
          boolean puedeDar = movibles >= 2 || (movibles == 1 && fijas.containsKey(new Par(cluster, chute)));
          if (puedeDar
              && (origen == null
                  || movibles > rutasEn(cluster, origen)
                  || (movibles == rutasEn(cluster, origen)
                      && porChute.get(chute).compareTo(porChute.get(origen)) > 0))) {
            origen = chute;
          }
        }
        if (origen == null) {
          break;
        }
        List<Long> ola = chutesDeOla(cluster);
        BigDecimal total = ola.stream().map((c) -> porChute.getOrDefault(c, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal promedio = total.divide(BigDecimal.valueOf(Math.max(1, ola.size())), ESCALA, RoundingMode.HALF_UP);
        TreeMap<BigDecimal, ArrayDeque<RutaProyeccion>> candidatas = rutas.get(new Par(cluster, origen));
        Map.Entry<BigDecimal, ArrayDeque<RutaProyeccion>> abajo = candidatas.floorEntry(promedio);
        Map.Entry<BigDecimal, ArrayDeque<RutaProyeccion>> arriba = candidatas.ceilingEntry(promedio);
        Map.Entry<BigDecimal, ArrayDeque<RutaProyeccion>> elegida =
            abajo == null
                ? arriba
                : arriba == null
                    ? abajo
                    : promedio.subtract(abajo.getKey()).compareTo(arriba.getKey().subtract(promedio)) <= 0
                        ? abajo
                        : arriba;
        RutaProyeccion ruta = elegida.getValue().peekFirst();
        trasladar(ruta, cluster, origen, vacio);
        hechos.add(new Traslado(ruta, origen, vacio));
      }
      return hechos;
    }

    /**
     * Primer paso del reparto: llena los chutes con buff del tramo con las rutas más pesadas del
     * grupo, estén donde estén (dentro o fuera del tramo). De la más pesada a la más liviana, por
     * turnos entre los chutes con buff, cada una queda fija en su chute. Un chute con buff recibe
     * rutas mientras no pase la carga promedio que tendrá el tramo (más la tolerancia); la primera
     * entra aunque la pase, si el chute aún está bajo el promedio (es la más pesada). Cuando la
     * siguiente ruta no cabe, ese chute queda lleno. Nunca se lleva tantas que falte una ruta para
     * cada chute sin buff que el día puede llenar. Las diarias que ya están en el chute cuentan en
     * su carga. Devuelve los traslados hechos.
     */
    List<Traslado> anclar(Long cluster, Collection<Long> chutesBuff) {
      List<Traslado> hechos = new ArrayList<>();
      List<Long> ola = chutesDeOla(cluster);
      List<Long> base = rango.isEmpty() ? ola.stream().sorted().toList() : rango;
      List<Long> destinos = base.stream().filter(chutesBuff::contains).toList();
      if (destinos.isEmpty()) {
        return hechos;
      }
      Map<Long, BigDecimal> porChute = carga.computeIfAbsent(cluster, (id) -> new HashMap<>());
      int sueltas = rutasDe(cluster);
      // Lo que se reparte: la carga del tramo más las rutas movibles que aún están fuera de él.
      BigDecimal total = BigDecimal.ZERO;
      for (Map.Entry<Long, BigDecimal> entrada : porChute.entrySet()) {
        Long chute = entrada.getKey();
        if (base.contains(chute)) {
          total = total.add(entrada.getValue());
        } else {
          for (Map.Entry<BigDecimal, ArrayDeque<RutaProyeccion>> grupo :
              rutas.getOrDefault(new Par(cluster, chute), new TreeMap<>()).entrySet()) {
            total = total.add(grupo.getKey().multiply(BigDecimal.valueOf(grupo.getValue().size())));
          }
        }
      }
      // Chutes que usará el tramo: los que tienen rutas fijas y, de los demás, uno por ruta movible.
      long conFijas = base.stream().filter((chute) -> fijas.containsKey(new Par(cluster, chute))).count();
      long sinBuffLibres =
          base.stream()
              .filter((chute) -> !chutesBuff.contains(chute) && !fijas.containsKey(new Par(cluster, chute)))
              .count();
      long buffLibres = base.size() - conFijas - sinBuffLibres;
      long usados = conFijas + Math.min(base.size() - conFijas, sueltas);
      BigDecimal promedio = total.divide(BigDecimal.valueOf(Math.max(1, usados)), ESCALA, RoundingMode.HALF_UP);
      BigDecimal tope = promedio.add(promedio.multiply(TOLERANCIA_CARGA));
      // Cupo del buff: deja una ruta movible para cada chute sin buff que el día alcanza a llenar.
      long cupo = sueltas - Math.min(sinBuffLibres, Math.max(0, sueltas - buffLibres));
      // Candidatas: todas las rutas movibles del grupo, de la más pesada a la más liviana; a igual
      // peso, primero la que ya está en un chute con buff (no se mueve sin necesidad).
      List<RutaProyeccion> candidatas = new ArrayList<>();
      for (Map.Entry<Par, TreeMap<BigDecimal, ArrayDeque<RutaProyeccion>>> entrada : rutas.entrySet()) {
        if (entrada.getKey().clusterId().equals(cluster)) {
          entrada.getValue().values().forEach(candidatas::addAll);
        }
      }
      candidatas.sort(
          Comparator.comparing(RutaProyeccion::getTotalBultos)
              .reversed()
              .thenComparing((RutaProyeccion r) -> !chutesBuff.contains(r.getChuteId()))
              .thenComparing(RutaProyeccion::getId));
      // Carga fija de cada chute con buff (diarias y rutas ya ancladas): las movibles que hoy
      // están ahí no cuentan, si no ocupan el lugar de las pesadas (el emparejado las saca después).
      Map<Long, BigDecimal> fija = new HashMap<>();
      for (Long chute : destinos) {
        BigDecimal movibles = BigDecimal.ZERO;
        for (Map.Entry<BigDecimal, ArrayDeque<RutaProyeccion>> grupo :
            rutas.getOrDefault(new Par(cluster, chute), new TreeMap<>()).entrySet()) {
          movibles = movibles.add(grupo.getKey().multiply(BigDecimal.valueOf(grupo.getValue().size())));
        }
        fija.put(chute, porChute.getOrDefault(chute, BigDecimal.ZERO).subtract(movibles));
      }
      Set<Long> llenos = new HashSet<>();
      Set<Long> conAncla = new HashSet<>();
      int turno = 0;
      for (RutaProyeccion ruta : candidatas) {
        if (ancladas >= cupo) {
          break;
        }
        Long destino = null;
        // Por turnos: el siguiente chute con buff que aún tiene lugar para esta ruta.
        for (int intento = 0; intento < destinos.size() && destino == null; intento++) {
          Long chute = destinos.get((turno + intento) % destinos.size());
          if (llenos.contains(chute)) {
            continue;
          }
          BigDecimal actual = fija.get(chute);
          boolean cabe = actual.add(ruta.getTotalBultos()).compareTo(tope) <= 0;
          boolean primera = !conAncla.contains(chute) && actual.compareTo(promedio) < 0;
          if (cabe || primera) {
            destino = chute;
            turno = (turno + intento + 1) % destinos.size();
          } else {
            llenos.add(chute);
          }
        }
        if (destino == null) {
          break;
        }
        Long origen = ruta.getChuteId();
        if (!origen.equals(destino)) {
          if (!rango.isEmpty() && !rango.contains(origen)) {
            deFuera++;
          }
          trasladar(ruta, cluster, origen, destino);
          hechos.add(new Traslado(ruta, origen, destino));
        }
        fijar(ruta, cluster, destino);
        fija.merge(destino, ruta.getTotalBultos(), BigDecimal::add);
        conAncla.add(destino);
        ancladas++;
      }
      return hechos;
    }

    /**
     * Un movimiento para emparejar la ola. Prueba los pares del más cargado (con rutas que se pueden
     * mover) al más liviano y hace el primero que mejora: una ruta con 0 < bultos < diferencia, la
     * más cercana a la mitad. Antes solo probaba el par extremo y, si su único candidato no servía
     * (p. ej. el más cargado tenía una sola ruta), se detenía aunque otro chute pudiera llenar un
     * vacío. Cada movimiento baja la suma de cuadrados de la carga, así que el proceso termina.
     * Null si la ola ya está pareja (dentro de la tolerancia) o ningún par mejora.
     */
    Traslado nivelar(Long cluster) {
      Map<Long, BigDecimal> porChute = carga.getOrDefault(cluster, Map.of());
      List<Long> ola = chutesDeOla(cluster);
      if (ola.size() < 2) {
        return null;
      }
      BigDecimal total = BigDecimal.ZERO;
      for (Long chute : ola) {
        total = total.add(porChute.get(chute));
      }
      BigDecimal promedio = total.divide(BigDecimal.valueOf(ola.size()), ESCALA, RoundingMode.HALF_UP);
      BigDecimal tolerancia = promedio.multiply(TOLERANCIA_CARGA);
      // Orden estable: por carga y, a igual carga, por el orden del tramo.
      List<Long> livianos =
          ola.stream().sorted(Comparator.comparing(porChute::get)).toList();
      List<Long> cargados =
          ola.stream()
              .filter((chute) -> rutasEn(cluster, chute) > 0)
              .sorted(Comparator.comparing(porChute::get).reversed())
              .toList();
      for (Long cargado : cargados) {
        for (Long liviano : livianos) {
          BigDecimal diferencia = porChute.get(cargado).subtract(porChute.get(liviano));
          if (diferencia.compareTo(tolerancia) <= 0) {
            // Los demás livianos están aún más cerca de este cargado.
            break;
          }
          RutaProyeccion mejor = candidata(rutas.get(new Par(cluster, cargado)), diferencia);
          if (mejor != null) {
            trasladar(mejor, cluster, cargado, liviano);
            return new Traslado(mejor, cargado, liviano);
          }
        }
      }
      return null;
    }

    /**
     * Cuando ningún movimiento de una sola ruta mejora (p. ej. las rutas del chute más cargado
     * pesan más que la diferencia), cambia una ruta del más cargado por una más liviana del más
     * liviano. Prueba los pares en el mismo orden que {@link #nivelar} y, en el primero que mejora,
     * elige el cambio cuya diferencia de bultos (0 < a − b < diferencia) queda más cerca de la mitad
     * de la diferencia. Solo cambia rutas movibles del mismo grupo: las fijas (diarias o ancladas en
     * el buff) no se tocan. Cada cambio baja la suma de cuadrados de la carga, así que el proceso
     * termina. Devuelve los dos traslados, o vacío si ningún par mejora.
     */
    List<Traslado> intercambiar(Long cluster) {
      Map<Long, BigDecimal> porChute = carga.getOrDefault(cluster, Map.of());
      List<Long> ola = chutesDeOla(cluster).stream().filter((chute) -> rutasEn(cluster, chute) > 0).toList();
      if (ola.size() < 2) {
        return List.of();
      }
      List<Long> todos = chutesDeOla(cluster);
      BigDecimal total = BigDecimal.ZERO;
      for (Long chute : todos) {
        total = total.add(porChute.get(chute));
      }
      BigDecimal tolerancia =
          total.divide(BigDecimal.valueOf(todos.size()), ESCALA, RoundingMode.HALF_UP).multiply(TOLERANCIA_CARGA);
      List<Long> livianos = ola.stream().sorted(Comparator.comparing(porChute::get)).toList();
      List<Long> cargados = ola.stream().sorted(Comparator.comparing(porChute::get).reversed()).toList();
      for (Long cargado : cargados) {
        for (Long liviano : livianos) {
          BigDecimal diferencia = porChute.get(cargado).subtract(porChute.get(liviano));
          if (diferencia.compareTo(tolerancia) <= 0) {
            break;
          }
          BigDecimal mitad = diferencia.divide(BigDecimal.valueOf(2), ESCALA, RoundingMode.HALF_UP);
          TreeMap<BigDecimal, ArrayDeque<RutaProyeccion>> deLiviano = rutas.get(new Par(cluster, liviano));
          BigDecimal mejorA = null;
          BigDecimal mejorB = null;
          BigDecimal mejorDistancia = null;
          for (BigDecimal a : rutas.get(new Par(cluster, cargado)).keySet()) {
            // b ideal = a − mitad; vale cualquier b con a − diferencia < b < a.
            BigDecimal ideal = a.subtract(mitad);
            for (BigDecimal b : new BigDecimal[] {deLiviano.floorKey(ideal), deLiviano.ceilingKey(ideal)}) {
              if (b == null || b.compareTo(a) >= 0 || b.compareTo(a.subtract(diferencia)) <= 0) {
                continue;
              }
              BigDecimal distancia = a.subtract(b).subtract(mitad).abs();
              if (mejorDistancia == null || distancia.compareTo(mejorDistancia) < 0) {
                mejorA = a;
                mejorB = b;
                mejorDistancia = distancia;
              }
            }
          }
          if (mejorA != null) {
            RutaProyeccion pesada = rutas.get(new Par(cluster, cargado)).get(mejorA).peekFirst();
            RutaProyeccion liviana = deLiviano.get(mejorB).peekFirst();
            trasladar(pesada, cluster, cargado, liviano);
            trasladar(liviana, cluster, liviano, cargado);
            return List.of(new Traslado(pesada, cargado, liviano), new Traslado(liviana, liviano, cargado));
          }
        }
      }
      return List.of();
    }

    /** La ruta con 0 < bultos < diferencia más cercana a la mitad de la diferencia, o null. */
    private static RutaProyeccion candidata(
        TreeMap<BigDecimal, ArrayDeque<RutaProyeccion>> candidatas, BigDecimal diferencia) {
      BigDecimal mitad = diferencia.divide(BigDecimal.valueOf(2), ESCALA, RoundingMode.HALF_UP);
      Map.Entry<BigDecimal, ArrayDeque<RutaProyeccion>> abajo = candidatas.floorEntry(mitad);
      Map.Entry<BigDecimal, ArrayDeque<RutaProyeccion>> arriba = candidatas.higherEntry(mitad);
      if (abajo != null && abajo.getKey().signum() <= 0) {
        abajo = null;
      }
      if (arriba != null && arriba.getKey().compareTo(diferencia) >= 0) {
        arriba = null;
      }
      Map.Entry<BigDecimal, ArrayDeque<RutaProyeccion>> elegida =
          abajo == null
              ? arriba
              : arriba == null
                  ? abajo
                  : mitad.subtract(abajo.getKey()).compareTo(arriba.getKey().subtract(mitad)) <= 0
                      ? abajo
                      : arriba;
      return elegida == null ? null : elegida.getValue().peekFirst();
    }

    private void poner(RutaProyeccion ruta, Long cluster, Long chute) {
      Par par = new Par(cluster, chute);
      rutas
          .computeIfAbsent(par, (p) -> new TreeMap<>())
          .computeIfAbsent(ruta.getTotalBultos(), (b) -> new ArrayDeque<>())
          .addLast(ruta);
      cuenta.merge(par, 1, Integer::sum);
      sumar(cluster, chute, ruta.getTotalBultos());
    }

    /** Saca la ruta de las que se pueden mover; con {@code restar}, también su carga. */
    private void sacar(RutaProyeccion ruta, Long cluster, Long chute, boolean restar) {
      Par par = new Par(cluster, chute);
      TreeMap<BigDecimal, ArrayDeque<RutaProyeccion>> porBultos = rutas.get(par);
      ArrayDeque<RutaProyeccion> iguales = porBultos.get(ruta.getTotalBultos());
      iguales.remove(ruta);
      if (iguales.isEmpty()) {
        porBultos.remove(ruta.getTotalBultos());
      }
      if (restar) {
        sumar(cluster, chute, ruta.getTotalBultos().negate());
      }
      if (cuenta.merge(par, -1, Integer::sum) == 0) {
        cuenta.remove(par);
        rutas.remove(par);
        // Un chute del rango (o con rutas fijas) sigue en la ola aunque se vacíe.
        if (restar && !rango.contains(chute) && !fijas.containsKey(par)) {
          carga.get(cluster).remove(chute);
        }
      }
    }

    private void quitar(RutaProyeccion ruta, Long cluster, Long chute) {
      sacar(ruta, cluster, chute, true);
    }

    /** La ruta queda fija en su chute: sigue sumando carga pero ya no se mueve. */
    private void fijar(RutaProyeccion ruta, Long cluster, Long chute) {
      sacar(ruta, cluster, chute, false);
      fijas.merge(new Par(cluster, chute), 1, Integer::sum);
      // Ya no cuenta entre las movibles (el recorte del tramo depende de ellas).
      rutasPorCluster.merge(cluster, -1, Integer::sum);
    }

    private void sumar(Long cluster, Long chute, BigDecimal bultos) {
      carga.computeIfAbsent(cluster, (id) -> new HashMap<>()).merge(chute, bultos, BigDecimal::add);
    }

    private void trasladar(RutaProyeccion ruta, Long cluster, Long origen, Long destino) {
      quitar(ruta, cluster, origen);
      poner(ruta, cluster, destino);
      ruta.setChuteId(destino);
      chutesNuevos.put(ruta.getId(), destino);
    }
  }

  // ---------------- Paso 2: operarios ----------------

  /**
   * Paso 2 del cluster en curso con la cantidad de operarios elegida para su
   * ola (o la última usada; si nunca se eligió, se pide). Todos
   * trabajan y se reparten para que la ola cierre lo antes posible; el límite
   * de horas solo dice si cumple. La cantidad se guarda en el cluster cuando el
   * paso termina bien.
   * Entre repartos con el mismo cierre, se queda el que deja a más gente en el
   * chute donde terminó la ola anterior. Solo sobra gente si cada chute con
   * carga llega al máximo por chute. Puede repetirse para recalcular.
   */
  private Asignados asignarOperarios(
      Contexto ctx, Long clusterId, Integer pedidos, Long actor) {
    Long archivoId = ctx.archivo().getId();
    ParametroCluster parametro = exigirEnCurso(ctx, clusterId);
    String nombre = ctx.nombreCluster(clusterId);
    if (ParametroCluster.ESTADO_PENDIENTE.equals(parametro.getEstado())) {
      throw new ConflictoException("Primero balancea las rutas de " + nombre + " (paso 1).");
    }
    BigDecimal velocidad = ctx.config().getVelocidadBase();
    if (velocidad == null || velocidad.signum() <= 0) {
      throw new ConflictoException("La velocidad base debe ser mayor a cero.");
    }
    BigDecimal limite =
        parametro.getLimiteHoras() == null ? BigDecimal.ZERO : parametro.getLimiteHoras();
    if (limite.signum() <= 0) {
      throw new ConflictoException(
          "Define el límite de horas de " + nombre + " antes de asignar sus operarios.");
    }
    List<CargaRutas> filas = cargaVisible(ctx);
    Map<Long, BigDecimal> carga = new HashMap<>();
    for (CargaRutas fila : filas) {
      if (fila.getClusterId().equals(clusterId) && fila.getRutas() > 0) {
        carga.merge(fila.getChuteId(), fila.getBultos(), BigDecimal::add);
      }
    }
    // Cada ola define su propia cantidad: la que se envía o la última que usó.
    // Ya no hay un equipo del registro al que recurrir.
    Integer elegido = pedidos != null ? pedidos : parametro.getOperariosOla();
    if (elegido == null || elegido <= 0) {
      throw new SolicitudInvalidaException(
          "Ingresa la cantidad de operarios de la ola de " + nombre + ".");
    }
    int equipo = elegido;
    if (carga.size() > equipo) {
      // Un chute con carga y sin nadie nunca termina: la ola no podría cerrarse.
      throw new ConflictoException(
          nombre
              + " tiene rutas en "
              + carga.size()
              + " chutes y la ola tiene "
              + equipo
              + " operario(s): cada chute con carga necesita al menos uno. Asigna "
              + carga.size()
              + " o más, o vuelve a balancear sus rutas con un rango de "
              + equipo
              + " chute(s) o menos.");
    }
    int topeChute = topePorChute(ctx, equipo);
    boolean topeDefinido = sinTope(ctx.config().getOperariosPorChute()) != null;
    Long previo = anterior(ctx, clusterId);
    Map<Long, Integer> heredados = previo == null ? Map.of() : operariosDeOla(ctx, previo);
    Asignacion asignacion =
        new Asignacion(carga, velocidad, limite, equipo, topeChute, heredados);
    asignacion.asignar();

    int total = asignacion.totalAsignados();
    BigDecimal tiempo = asignacion.tiempoOla();
    StringBuilder mensaje =
        new StringBuilder(nombre)
            .append(": ")
            .append(total)
            .append(" operario(s) en ")
            .append(carga.size())
            .append(" chute(s), de ")
            .append(equipo)
            .append(" para la ola.");
    if (asignacion.sinChute() > 0) {
      mensaje
          .append(" ")
          .append(asignacion.sinChute())
          .append(" sin chute: cada chute con carga ya tiene el máximo de ")
          .append(topeChute)
          .append(".");
    }
    if (previo != null) {
      mensaje
          .append(" Desde ")
          .append(ctx.nombreCluster(previo))
          .append(", ")
          .append(asignacion.siguenEnSuChute())
          .append(" siguen en su chute.");
    }
    if (!sinOperarios(tiempo) && tiempo.compareTo(limite.add(TOLERANCIA)) <= 0) {
      mensaje
          .append(" Cumple su límite: ")
          .append(textoHoras(tiempo))
          .append(" de ")
          .append(textoHoras(limite))
          .append(".");
    } else {
      mensaje
          .append(" Sigue fuera de su límite: ")
          .append(textoHoras(tiempo))
          .append(" de ")
          .append(textoHoras(limite))
          .append("; necesita ")
          .append(asignacion.necesariosParaCumplir())
          .append(" operario(s)");
      if (topeDefinido && asignacion.necesitaMasQueTope()) {
        mensaje.append(", y algún chute más de ").append(topeChute);
      }
      mensaje.append(". La ola tiene ").append(equipo).append(" operario(s)");
      if (topeDefinido) {
        mensaje
            .append(" y el máximo por chute es ")
            .append(topeChute)
            .append(": sube alguno de los dos o revisa el límite.");
      } else {
        mensaje.append(": sube la cantidad o revisa el límite.");
      }
    }

    Instant momento = Instant.now();
    LanzamientoBalanceo lanzamiento =
        lanzar(ctx, clusterId, FaseBalanceo.OPERARIOS, mensaje.toString(), actor, momento);
    Long lanzamientoId = lanzamiento.getId();
    List<HistorialBalanceo> logs = new ArrayList<>();
    List<OperarioOla> nuevas = new ArrayList<>();
    for (Long chute : carga.keySet()) {
      Par par = new Par(clusterId, chute);
      OperarioOla ola = ctx.operariosOla().get(par);
      // Al recalcular, el «antes» es lo asignado; la primera vez, lo que el
      // chute traía de la ola anterior.
      int antes = ola == null ? heredados.getOrDefault(chute, 0) : ola.getOperarios();
      int despues = asignacion.operarios(chute);
      if (ola == null) {
        ola = new OperarioOla();
        ola.setId(new OperarioOlaId(archivoId, ctx.balanceo().name(), clusterId, chute));
        ctx.operariosOla().put(par, ola);
      }
      ola.setOperarios(despues);
      nuevas.add(ola);
      if (antes != despues) {
        logs.add(asignados(archivoId, lanzamientoId, chute, antes, despues, actor, momento));
      }
    }
    lanzamiento.setChutesOperarios(logs.size());
    operarioOlaRepo.saveAll(nuevas);
    logs.forEach((l) -> l.setBalanceo(ctx.balanceo().name()));
    lotes.insertarHistorial(logs);
    parametro.setOperariosOla(equipo);
    parametro.setEstado(ParametroCluster.ESTADO_LISTO);
    return new Asignados(mensaje.toString(), total);
  }

  /** Resultado del paso de operarios en un día: su mensaje y cuántos operarios quedaron. */
  private record Asignados(String mensaje, int total) {}

  /** Operarios de cada chute en la ola de un cluster (vacío si la ola no los tiene). */
  private static Map<Long, Integer> operariosDeOla(Contexto ctx, Long clusterId) {
    Map<Long, Integer> resultado = new HashMap<>();
    ctx.operariosOla()
        .forEach(
            (par, ola) -> {
              if (par.clusterId().equals(clusterId)) {
                resultado.put(par.chuteId(), ola.getOperarios());
              }
            });
    return resultado;
  }

  /**
   * Asignación de operarios en una ola partiendo del equipo que deja la ola
   * anterior. El tiempo de un chute en la ola es la carga de ese cluster en el
   * chute / (velocidad × operarios); la ola cierra con su chute más lento.
   */
  private static final class Asignacion {
    /** Bultos de la ola por chute. */
    private final Map<Long, BigDecimal> carga;
    private final BigDecimal velocidad;
    private final BigDecimal limite;
    private final int equipo;
    private final int topeChute;
    /** Operarios con los que cada chute terminó la ola anterior. */
    private final Map<Long, Integer> heredados;
    private final Map<Long, Integer> operarios = new HashMap<>();

    Asignacion(
        Map<Long, BigDecimal> carga,
        BigDecimal velocidad,
        BigDecimal limite,
        int equipo,
        int topeChute,
        Map<Long, Integer> heredados) {
      this.carga = carga;
      this.velocidad = velocidad;
      this.limite = limite;
      this.equipo = equipo;
      this.topeChute = topeChute;
      this.heredados = heredados;
    }

    int operarios(Long chute) {
      return operarios.getOrDefault(chute, 0);
    }

    int totalAsignados() {
      return operarios.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Operarios que no cambian de chute respecto de la ola anterior. */
    int siguenEnSuChute() {
      return carga.keySet().stream()
          .mapToInt((chute) -> Math.min(operarios(chute), heredados.getOrDefault(chute, 0)))
          .sum();
    }

    /**
     * Todo el equipo trabaja (salvo que cada chute con carga llegue al tope) y
     * se reparte para que la ola cierre lo antes posible: cada operario va al
     * chute que más tarda en ese momento. Con ese cierre fijo, se mueve lo
     * mínimo: cada chute queda con lo justo para lograrlo y el resto se reparte
     * primero dejando a la gente donde ya estaba y luego en el chute más lento.
     */
    void asignar() {
      // Cada chute con rutas parte con uno (el paso 2 ya exige equipo >= chutes):
      // así uno con rutas de 0 bultos, que no tarda nada, no queda sin nadie.
      int disponibles = Math.min(equipo, carga.size() * topeChute);
      int base = disponibles >= carga.size() ? 1 : 0;
      carga.keySet().forEach((chute) -> operarios.put(chute, base));
      for (int i = base * carga.size(); i < disponibles; i++) {
        operarios.merge(chuteMasLento(), 1, Integer::sum);
      }
      BigDecimal cierre = tiempoOla();
      if (sinOperarios(cierre)) {
        return;
      }
      int sobrantes = 0;
      for (Long chute : carga.keySet()) {
        int justos = operarios(chute);
        // Nunca baja de uno: todo chute con rutas de la ola necesita a alguien.
        while (justos > 1
            && horas(carga.get(chute), velocidad, justos - 1).compareTo(cierre) <= 0) {
          justos--;
        }
        sobrantes += operarios(chute) - justos;
        operarios.put(chute, justos);
      }
      for (Long chute : carga.keySet()) {
        int vuelven = Math.min(heredados.getOrDefault(chute, 0), topeChute) - operarios(chute);
        int quedan = Math.min(sobrantes, vuelven);
        if (quedan > 0) {
          operarios.merge(chute, quedan, Integer::sum);
          sobrantes -= quedan;
        }
      }
      for (; sobrantes > 0; sobrantes--) {
        operarios.merge(chuteMasLento(), 1, Integer::sum);
      }
    }

    /** Operarios del equipo que no caben: cada chute con carga ya tiene el tope. */
    int sinChute() {
      return equipo - totalAsignados();
    }

    private BigDecimal tiempoEn(Long chute) {
      return horas(carga.get(chute), velocidad, operarios(chute));
    }

    BigDecimal tiempoOla() {
      BigDecimal maximo = BigDecimal.ZERO;
      for (Long chute : carga.keySet()) {
        maximo = maximo.max(tiempoEn(chute));
      }
      return maximo;
    }

    /**
     * El chute con cupo que más tarda; a igual tiempo, el que aún tiene por
     * volver a alguien de la ola anterior y luego el de más bultos.
     */
    private Long chuteMasLento() {
      return carga.keySet().stream()
          .filter((chute) -> operarios(chute) < topeChute)
          .max(
              Comparator.comparing(this::tiempoEn)
                  .thenComparing(
                      (Long chute) -> heredados.getOrDefault(chute, 0) > operarios(chute))
                  .thenComparing(carga::get))
          .orElseThrow();
    }

    /** Operarios que necesitaría la ola para cumplir su límite (cada chute por su cuenta). */
    int necesariosParaCumplir() {
      return carga.values().stream().mapToInt(this::necesarios).sum();
    }

    /** Algún chute de la ola necesitaría más operarios que el tope por chute. */
    boolean necesitaMasQueTope() {
      return carga.values().stream().anyMatch((bultos) -> necesarios(bultos) > topeChute);
    }

    private int necesarios(BigDecimal bultos) {
      if (bultos.signum() <= 0) {
        return 0;
      }
      return Math.max(
          1, bultos.divide(velocidad.multiply(limite), 0, RoundingMode.CEILING).intValue());
    }
  }

  /**
   * Todo el registro vuelve a la proyección en una sola transacción y en los tres balanceos:
   * rutas a su chute y cluster originales, sin operarios, lanzamientos, historial ni tramos. La
   * simulación empieza de nuevo por el primer cluster.
   */
  private void restablecerTodo(Contexto ctx, Long actor) {
    Long archivoId = ctx.archivo().getId();
    // El chute y el cluster originales quedaron congelados al cargar el Excel, así
    // que basta un UPDATE masivo (sin reconstruir el historial movimiento a movimiento).
    rutaRepo.restaurarTodas(archivoId);
    for (Balanceo b : Balanceo.DIAS) {
      borrarOlas(ctx.en(b), ctx.parametrosDe(b).keySet());
    }
    // El historial de cada lanzamiento se borra con él (ON DELETE CASCADE); después, los manuales.
    lanzamientoRepo.borrarTodoPorArchivo(archivoId);
    historialRepo.borrarTodoPorArchivo(archivoId);
    Set<Long> clusterIds = new LinkedHashSet<>();
    ctx.parametros().values().forEach((porCluster) -> clusterIds.addAll(porCluster.keySet()));
    lotes.borrarGrupos(archivoId, clusterIds);
    // También el rango de chutes y la cantidad de operarios que cada ola había elegido
    // (si no, los modales los propondrían).
    ctx.parametros()
        .values()
        .forEach(
            (porCluster) ->
                porCluster
                    .values()
                    .forEach(
                        (p) -> {
                          p.setEstado(ParametroCluster.ESTADO_PENDIENTE);
                          p.setChuteDesdeId(null);
                          p.setChuteHastaId(null);
                          p.setOperariosOla(null);
                        }));
    actualizarEstado(ctx);
    tocar(ctx.archivo(), actor);
  }

  /**
   * Deshace el trabajo hecho mientras esos clusters estaban en curso, en los tres balanceos a la
   * vez: los movimientos de sus lanzamientos y los cambios a mano (mover o cambiar de cluster) con
   * ese cluster de trabajo, del último al primero (los ids del historial son una sola secuencia,
   * así que el orden es el real). Cada ruta vuelve exactamente al chute y cluster que tenía antes,
   * también las diarias. Ninguna ruta de un cluster cerrado se toca (mientras un cluster está en
   * curso, los anteriores están cerrados). Los clusters quedan PENDIENTE, sin operarios de ola y
   * sin tramos; conservan su rango para proponerlo la próxima vez.
   */
  private void reiniciarTodos(Contexto ctx, Collection<Long> clusterIds) {
    if (clusterIds.isEmpty()) {
      return;
    }
    Long archivoId = ctx.archivo().getId();
    Map<Long, Long> chutes = new LinkedHashMap<>();
    Map<Long, Long> clusters = new LinkedHashMap<>();
    for (CambioParaDeshacer cambio : historialRepo.trabajoParaDeshacerAmbos(archivoId, clusterIds)) {
      if (HistorialBalanceo.ACCION_CAMBIAR_CLUSTER.equals(cambio.getTipoAccion())) {
        if (cambio.getClusterOrigenId() != null) {
          clusters.put(cambio.getRutaId(), cambio.getClusterOrigenId());
        }
      } else if (cambio.getChuteOrigenId() != null) {
        chutes.put(cambio.getRutaId(), cambio.getChuteOrigenId());
      }
    }
    historialRepo.borrarManualesDeTrabajoAmbos(archivoId, clusterIds);
    lanzamientoRepo.borrarPorClusters(archivoId, clusterIds);
    for (Long clusterId : clusterIds) {
      for (Balanceo b : Balanceo.values()) {
        ParametroCluster parametro = ctx.parametro(b, clusterId);
        if (parametro != null) {
          parametro.setEstado(ParametroCluster.ESTADO_PENDIENTE);
        }
      }
    }
    lotes.moverRutas(chutes);
    lotes.cambiarClusters(clusters);
    for (Balanceo b : Balanceo.DIAS) {
      borrarOlas(ctx.en(b), clusterIds);
    }
    lotes.quitarTramos(archivoId, clusterIds);
  }

  /**
   * Diferencia de bultos entre chutes: (más cargado − menos cargado) /
   * promedio, en %. Null con menos de dos chutes con carga.
   */
  private static Integer diferenciaBultos(Collection<BigDecimal> bultos) {
    List<BigDecimal> valores = bultos.stream().filter((v) -> v.signum() > 0).toList();
    if (valores.size() < 2) {
      return null;
    }
    BigDecimal total = valores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal promedio =
        total.divide(BigDecimal.valueOf(valores.size()), ESCALA, RoundingMode.HALF_UP);
    BigDecimal rango = Collections.max(valores).subtract(Collections.min(valores));
    return rango
        .multiply(BigDecimal.valueOf(100))
        .divide(promedio, 0, RoundingMode.HALF_UP)
        .intValue();
  }

  /** Diferencia de bultos entre los chutes de cada cluster para una distribución de carga. */
  private static Map<Long, Integer> diferenciasPorCluster(Collection<CargaRutas> filas) {
    Map<Long, Map<Long, BigDecimal>> porCluster = new HashMap<>();
    for (CargaRutas fila : filas) {
      if (fila.getChuteId() != null && fila.getRutas() > 0) {
        porCluster
            .computeIfAbsent(fila.getClusterId(), (id) -> new HashMap<>())
            .merge(fila.getChuteId(), fila.getBultos(), BigDecimal::add);
      }
    }
    Map<Long, Integer> resultado = new HashMap<>();
    porCluster.forEach((id, chutes) -> resultado.put(id, diferenciaBultos(chutes.values())));
    return resultado;
  }

  /** Registra un lanzamiento con su resultado y devuelve su id para enlazar el historial. */
  private LanzamientoBalanceo lanzar(
      Contexto ctx,
      Long clusterId,
      FaseBalanceo fase,
      String mensaje,
      Long actor,
      Instant momento) {
    LanzamientoBalanceo lanzamiento = new LanzamientoBalanceo();
    lanzamiento.setArchivoId(ctx.archivo().getId());
    lanzamiento.setBalanceo(ctx.balanceo().name());
    lanzamiento.setClusterId(clusterId);
    lanzamiento.setFase(fase.name());
    lanzamiento.setMensaje(mensaje.length() > 1000 ? mensaje.substring(0, 1000) : mensaje);
    lanzamiento.setCreadoPor(actor);
    lanzamiento.setCreadoEn(momento);
    return lanzamientoRepo.save(lanzamiento);
  }

  /**
   * Un cambio manual (límite, velocidad, topes o una ruta movida) deja
   * desactualizados los operarios de esas olas: los clusters LISTO vuelven a
   * RUTAS y su ola deja de tener operarios asignados.
   */
  private void invalidarOperarios(Contexto ctx, Collection<Long> clusterIds) {
    List<Long> listos =
        clusterIds.stream()
            .filter(
                (id) -> {
                  ParametroCluster parametro = ctx.parametrosCluster().get(id);
                  return parametro != null
                      && ParametroCluster.ESTADO_LISTO.equals(parametro.getEstado());
                })
            .toList();
    listos.forEach(
        (id) -> ctx.parametrosCluster().get(id).setEstado(ParametroCluster.ESTADO_RUTAS));
    borrarOlas(ctx, listos);
  }

  /** Quita los operarios asignados a esas olas (entidades ya cargadas en el contexto). */
  private void borrarOlas(Contexto ctx, Collection<Long> clusterIds) {
    List<OperarioOla> borrar = new ArrayList<>();
    ctx.operariosOla()
        .entrySet()
        .removeIf(
            (entrada) -> {
              if (clusterIds.contains(entrada.getKey().clusterId())) {
                borrar.add(entrada.getValue());
                return true;
              }
              return false;
            });
    if (!borrar.isEmpty()) {
      operarioOlaRepo.deleteAll(borrar);
    }
  }

  /**
   * PROYECCION si ningún cluster empezó; FINALIZADO con todos cerrados; si no,
   * EN_PROCESO.
   */
  private static void actualizarEstado(Contexto ctx) {
    // El registro termina cuando los tres balanceos cerraron todos sus clusters.
    List<ParametroCluster> clusters = new ArrayList<>();
    ctx.parametros().values().forEach((porCluster) -> clusters.addAll(porCluster.values()));
    if (!clusters.isEmpty()
        && clusters.stream().allMatch((p) -> ParametroCluster.ESTADO_CERRADO.equals(p.getEstado()))) {
      ctx.archivo().setEstado(ArchivoExcel.ESTADO_FINALIZADO);
    } else if (clusters.stream()
        .anyMatch((p) -> !ParametroCluster.ESTADO_PENDIENTE.equals(p.getEstado()))) {
      ctx.archivo().setEstado(ArchivoExcel.ESTADO_EN_PROCESO);
    } else {
      ctx.archivo().setEstado(ArchivoExcel.ESTADO_PROYECCION);
    }
  }

  /** Clusters en el orden de las olas (CL1 → CL2 → …): el orden en que se trabajan. */
  private static List<Long> orden(Contexto ctx) {
    return ctx.parametrosCluster().keySet().stream()
        .sorted(Comparator.comparing(ctx::nombreCluster, ORDEN_NATURAL))
        .toList();
  }

  /** Primer cluster sin cerrar: el único que se puede trabajar. Null si todos están cerrados. */
  private static Long enCurso(Contexto ctx) {
    return orden(ctx).stream().filter((id) -> !cerrado(ctx, id)).findFirst().orElse(null);
  }

  /** Cluster anterior en el orden de las olas; null para el primero. */
  private static Long anterior(Contexto ctx, Long clusterId) {
    List<Long> orden = orden(ctx);
    int indice = orden.indexOf(clusterId);
    return indice <= 0 ? null : orden.get(indice - 1);
  }

  /**
   * Solo se trabaja el cluster en curso: los anteriores están cerrados (hay que
   * reabrirlos) y los siguientes esperan a que se cierre este.
   */
  private static ParametroCluster exigirEnCurso(Contexto ctx, Long clusterId) {
    ParametroCluster parametro = parametroCluster(ctx, clusterId);
    Long enCurso = enCurso(ctx);
    if (!clusterId.equals(enCurso)) {
      throw new ConflictoException(
          cerrado(ctx, clusterId)
              ? ctx.nombreCluster(clusterId) + " está cerrado: reábrelo para cambiarlo."
              : ctx.nombreCluster(clusterId)
                  + " se habilita cuando cierres "
                  + ctx.nombreCluster(enCurso)
                  + ".");
    }
    return parametro;
  }

  /** El chute está bloqueado en el plano (19, 20, 21): no recibe rutas. */
  private boolean bloqueado(Contexto ctx, Long chuteId) {
    return plano.bloqueado(ctx.nombreChute(chuteId));
  }

  /** «CHUTE 20» para los mensajes, aunque el catálogo lo guarde como «20». */
  private static String etiquetaChute(Contexto ctx, Long chuteId) {
    String nombre = ctx.nombreChute(chuteId);
    return nombre != null && nombre.trim().toUpperCase(java.util.Locale.ROOT).startsWith("CHUTE")
        ? nombre
        : "CHUTE " + nombre;
  }

  private void exigirNoBloqueado(Contexto ctx, Long chuteId) {
    if (bloqueado(ctx, chuteId)) {
      throw new ConflictoException(
          "El " + etiquetaChute(ctx, chuteId) + " está bloqueado: no se pueden mover rutas ahí.");
    }
  }

  /** La ruta puede cambiar de cluster: el suyo y al menos otro siguen sin cerrar. */
  private static boolean clusterEditable(Map<Long, ParametroCluster> parametros, Long clusterId) {
    ParametroCluster propio = parametros.get(clusterId);
    if (propio == null || ParametroCluster.ESTADO_CERRADO.equals(propio.getEstado())) {
      return false;
    }
    return parametros.entrySet().stream()
        .anyMatch(
            (e) ->
                !e.getKey().equals(clusterId)
                    && !ParametroCluster.ESTADO_CERRADO.equals(e.getValue().getEstado()));
  }

  private static boolean cerrado(Contexto ctx, Long clusterId) {
    ParametroCluster parametro = ctx.parametrosCluster().get(clusterId);
    return parametro != null && ParametroCluster.ESTADO_CERRADO.equals(parametro.getEstado());
  }

  private static String nombres(Contexto ctx, Collection<Long> clusterIds) {
    return clusterIds.stream().map(ctx::nombreCluster).collect(Collectors.joining(", "));
  }

  private static ParametroCluster parametroCluster(Contexto ctx, Long clusterId) {
    ParametroCluster parametro = ctx.parametrosCluster().get(clusterId);
    if (parametro == null) {
      throw new SolicitudInvalidaException(
          "El cluster " + clusterId + " no pertenece al registro.");
    }
    return parametro;
  }

  // ------------------------------------------------------------------
  // Balanceos L-X-V / M-J-S y rutas diarias
  // ------------------------------------------------------------------

  /** La ruta (o el agregado) de esa frecuencia aparece en el balanceo del contexto. */
  private static boolean visible(Contexto ctx, String frecuencia) {
    return ctx.balanceo().incluye(frecuencia);
  }

  private static boolean pendiente(ParametroCluster parametro) {
    return parametro == null || ParametroCluster.ESTADO_PENDIENTE.equals(parametro.getEstado());
  }

  /**
   * Las rutas diarias del cluster ya están fijas: su balanceo (Diarias) ya las acomodó y desde ahí
   * el automático de L-X-V y M-J-S no las mueve. A mano sí se mueven. Se liberan al restablecer el
   * cluster.
   */
  private static boolean diariasFijas(Contexto ctx, Long clusterId) {
    return !pendiente(ctx.parametro(Balanceo.DIA, clusterId));
  }

  /** Algún cluster ya se balanceó (en cualquier balanceo): el buff ya no se puede cambiar. */
  private static boolean buffBloqueado(Contexto ctx) {
    return ctx.parametros().values().stream()
        .flatMap((porCluster) -> porCluster.values().stream())
        .anyMatch((p) -> !pendiente(p));
  }

  /** Balanceos donde aparece la ruta: una diaria, en los tres; las de un día, solo en ese. */
  private static List<Balanceo> balanceosDe(RutaProyeccion ruta) {
    return Arrays.stream(Balanceo.values()).filter((b) -> b.incluye(ruta.getFrecuencia())).toList();
  }

  /**
   * Cambiar a mano una ruta exige su cluster abierto en cada balanceo donde aparece (una diaria
   * cambia también en los dos días).
   */
  private static void exigirAbiertoEn(Contexto ctx, List<Balanceo> balanceos, Long clusterId, String accion) {
    for (Balanceo b : balanceos) {
      ParametroCluster parametro = ctx.parametro(b, clusterId);
      if (parametro != null && ParametroCluster.ESTADO_CERRADO.equals(parametro.getEstado())) {
        throw new ConflictoException(
            ctx.nombreCluster(clusterId) + " está cerrado: reábrelo para " + accion + ".");
      }
    }
  }

  /** Invalida los operarios de esos clusters en los días de la lista (Diarias no tiene). */
  private void invalidarOperariosEn(
      Contexto ctx, Collection<Balanceo> balanceos, Collection<Long> clusterIds) {
    for (Balanceo b : balanceos) {
      if (b.esDia()) {
        invalidarOperarios(ctx.en(b), clusterIds);
      }
    }
  }

  /** Estado del cluster en ese balanceo; PENDIENTE si no tiene fila. */
  private static String estadoEn(Contexto ctx, Balanceo balanceo, Long clusterId) {
    ParametroCluster parametro = ctx.parametro(balanceo, clusterId);
    return parametro == null ? ParametroCluster.ESTADO_PENDIENTE : parametro.getEstado();
  }

  /** Primer cluster sin cerrar en ese balanceo (los tres avanzan juntos al cerrar). */
  private static Long enCursoEn(Contexto ctx, Balanceo balanceo) {
    return enCurso(ctx.en(balanceo));
  }

  /**
   * Rutas propias de cada cluster por frecuencia: [diarias, L-X-V, M-J-S]. Sale de la carga cruda
   * (una fila por cluster, chute y frecuencia), sin otra consulta.
   */
  private static Map<Long, long[]> rutasPorFrecuencia(List<CargaRutas> cruda) {
    Map<Long, long[]> resultado = new HashMap<>();
    for (CargaRutas fila : cruda) {
      if (fila.getRutas() == null || fila.getRutas() == 0) {
        continue;
      }
      Balanceo dueno = Balanceo.deRuta(fila.getFrecuencia());
      int i = dueno == Balanceo.DIA ? 0 : dueno == Balanceo.LXV ? 1 : 2;
      resultado.computeIfAbsent(fila.getClusterId(), (id) -> new long[3])[i] += fila.getRutas();
    }
    return resultado;
  }

  /** El día tiene carga: rutas propias o diarias (que salen los dos días). */
  private static boolean participa(Map<Balanceo, Long> propias, Balanceo dia) {
    return propias.get(dia) > 0 || propias.get(Balanceo.DIA) > 0;
  }

  /** Rutas propias del cluster en cada balanceo (las diarias son de {@link Balanceo#DIA}). */
  private Map<Balanceo, Long> rutasPropias(Long archivoId, Long clusterId) {
    long[] n =
        rutasPorFrecuencia(rutaRepo.cargaPorArchivo(archivoId))
            .getOrDefault(clusterId, new long[3]);
    if (n[0] + n[1] + n[2] == 0) {
      // Un cluster nunca queda sin rutas: sin datos de carga no se omite nada (se exige todo).
      return Map.of(Balanceo.DIA, 1L, Balanceo.LXV, 1L, Balanceo.MJS, 1L);
    }
    return Map.of(Balanceo.DIA, n[0], Balanceo.LXV, n[1], Balanceo.MJS, n[2]);
  }

  /** Carga por (cluster, chute) de las rutas del balanceo (las suyas y las diarias). */
  private List<CargaRutas> cargaVisible(Contexto ctx) {
    return sumarCarga(ctx, rutaRepo.cargaPorArchivo(ctx.archivo().getId()));
  }

  /** Carga original por (cluster original, chute original) de las rutas del balanceo. */
  private List<CargaRutas> cargaOriginalVisible(Contexto ctx) {
    return sumarCarga(ctx, rutaRepo.cargaOriginalPorArchivo(ctx.archivo().getId()));
  }

  /** Une las filas de las frecuencias visibles de un mismo (cluster, chute). */
  static List<CargaRutas> sumarCarga(Contexto ctx, List<CargaRutas> filas) {
    Map<Par, Carga> unidas = new LinkedHashMap<>();
    for (CargaRutas fila : filas) {
      if (!visible(ctx, fila.getFrecuencia())) {
        continue;
      }
      unidas.merge(
          new Par(fila.getClusterId(), fila.getChuteId()), Carga.de(fila), Carga::sumar);
    }
    return List.copyOf(unidas.values());
  }

  /**
   * Carga por (cluster, grupo, chute). Con {@code todas}, de las tres frecuencias: los tramos de
   * grupo son del cluster y valen para los dos balanceos, así que se reparten por toda su carga.
   */
  private List<CargaGrupo> cargaGrupos(Contexto ctx, boolean todas) {
    Map<String, CargaGrupoUnida> unidas = new LinkedHashMap<>();
    for (CargaGrupo fila : rutaRepo.cargaPorGrupo(ctx.archivo().getId())) {
      if (!todas && !visible(ctx, fila.getFrecuencia())) {
        continue;
      }
      String clave = fila.getClusterId() + "\u0000" + fila.getGrupo() + "\u0000" + fila.getChuteId();
      unidas.merge(clave, CargaGrupoUnida.de(fila), CargaGrupoUnida::sumar);
    }
    return List.copyOf(unidas.values());
  }

  /** Rutas movidas (chute original → actual) del balanceo. */
  private List<MovimientoRutas> movimientosVisibles(Contexto ctx, Long clusterId) {
    Map<String, MovimientoUnido> unidos = new LinkedHashMap<>();
    for (MovimientoRutas fila : rutaRepo.movimientosPorArchivo(ctx.archivo().getId(), clusterId)) {
      if (visible(ctx, fila.getFrecuencia())) {
        unidos.merge(
            fila.getOrigen() + ":" + fila.getDestino(), MovimientoUnido.de(fila), MovimientoUnido::sumar);
      }
    }
    return List.copyOf(unidos.values());
  }

  /** Rutas cambiadas de cluster (original → actual) del balanceo. */
  private List<CambioClusterRutas> cambiosClusterVisibles(Contexto ctx) {
    Map<String, CambioUnido> unidos = new LinkedHashMap<>();
    for (CambioClusterRutas fila : rutaRepo.cambiosClusterPorArchivo(ctx.archivo().getId())) {
      if (visible(ctx, fila.getFrecuencia())) {
        unidos.merge(fila.getOrigen() + ":" + fila.getDestino(), CambioUnido.de(fila), CambioUnido::sumar);
      }
    }
    return List.copyOf(unidos.values());
  }

  /** Rutas movidas de cada cluster en el balanceo. */
  private Map<Long, Integer> movidasPorCluster(Contexto ctx) {
    Map<Long, Integer> resultado = new HashMap<>();
    for (RutasMovidasCluster fila : rutaRepo.rutasMovidasPorCluster(ctx.archivo().getId())) {
      if (visible(ctx, fila.getFrecuencia())) {
        resultado.merge(fila.getClusterId(), Math.toIntExact(fila.getRutas()), Integer::sum);
      }
    }
    return resultado;
  }

  private static BigDecimal ceroSiNulo(BigDecimal valor) {
    return valor == null ? BigDecimal.ZERO : valor;
  }

  private static long ceroSiNulo(Long valor) {
    return valor == null ? 0 : valor;
  }

  /** Carga de un (cluster, chute) con las frecuencias de un balanceo ya sumadas. */
  private record Carga(
      Long clusterId,
      Long chuteId,
      long rutas,
      BigDecimal bultos,
      BigDecimal unidades,
      BigDecimal lineas,
      BigDecimal cod)
      implements CargaRutas {

    static Carga de(CargaRutas fila) {
      return new Carga(
          fila.getClusterId(),
          fila.getChuteId(),
          ceroSiNulo(fila.getRutas()),
          ceroSiNulo(fila.getBultos()),
          ceroSiNulo(fila.getUnidades()),
          ceroSiNulo(fila.getLineas()),
          ceroSiNulo(fila.getCod()));
    }

    Carga sumar(Carga otra) {
      return new Carga(
          clusterId,
          chuteId,
          rutas + otra.rutas,
          bultos.add(otra.bultos),
          unidades.add(otra.unidades),
          lineas.add(otra.lineas),
          cod.add(otra.cod));
    }

    @Override
    public Long getClusterId() {
      return clusterId;
    }

    @Override
    public Long getChuteId() {
      return chuteId;
    }

    @Override
    public Long getRutas() {
      return rutas;
    }

    @Override
    public BigDecimal getBultos() {
      return bultos;
    }

    @Override
    public BigDecimal getUnidades() {
      return unidades;
    }

    @Override
    public BigDecimal getLineas() {
      return lineas;
    }

    @Override
    public BigDecimal getCod() {
      return cod;
    }

    @Override
    public String getFrecuencia() {
      return null;
    }
  }

  private record CargaGrupoUnida(Long clusterId, String grupo, Long chuteId, long rutas, BigDecimal bultos)
      implements CargaGrupo {

    static CargaGrupoUnida de(CargaGrupo fila) {
      return new CargaGrupoUnida(
          fila.getClusterId(),
          fila.getGrupo(),
          fila.getChuteId(),
          ceroSiNulo(fila.getRutas()),
          ceroSiNulo(fila.getBultos()));
    }

    CargaGrupoUnida sumar(CargaGrupoUnida otra) {
      return new CargaGrupoUnida(clusterId, grupo, chuteId, rutas + otra.rutas, bultos.add(otra.bultos));
    }

    @Override
    public Long getClusterId() {
      return clusterId;
    }

    @Override
    public String getGrupo() {
      return grupo;
    }

    @Override
    public Long getChuteId() {
      return chuteId;
    }

    @Override
    public Long getRutas() {
      return rutas;
    }

    @Override
    public BigDecimal getBultos() {
      return bultos;
    }

    @Override
    public String getFrecuencia() {
      return null;
    }
  }

  private record MovimientoUnido(Long origen, Long destino, long rutas) implements MovimientoRutas {

    static MovimientoUnido de(MovimientoRutas fila) {
      return new MovimientoUnido(fila.getOrigen(), fila.getDestino(), ceroSiNulo(fila.getRutas()));
    }

    MovimientoUnido sumar(MovimientoUnido otro) {
      return new MovimientoUnido(origen, destino, rutas + otro.rutas);
    }

    @Override
    public Long getOrigen() {
      return origen;
    }

    @Override
    public Long getDestino() {
      return destino;
    }

    @Override
    public Long getRutas() {
      return rutas;
    }

    @Override
    public String getFrecuencia() {
      return null;
    }
  }

  private record CambioUnido(
      Long origen,
      Long destino,
      long rutas,
      BigDecimal bultos,
      BigDecimal unidades,
      BigDecimal lineas,
      BigDecimal cod)
      implements CambioClusterRutas {

    static CambioUnido de(CambioClusterRutas fila) {
      return new CambioUnido(
          fila.getOrigen(),
          fila.getDestino(),
          ceroSiNulo(fila.getRutas()),
          ceroSiNulo(fila.getBultos()),
          ceroSiNulo(fila.getUnidades()),
          ceroSiNulo(fila.getLineas()),
          ceroSiNulo(fila.getCod()));
    }

    CambioUnido sumar(CambioUnido otro) {
      return new CambioUnido(
          origen,
          destino,
          rutas + otro.rutas,
          bultos.add(otro.bultos),
          unidades.add(otro.unidades),
          lineas.add(otro.lineas),
          cod.add(otro.cod));
    }

    @Override
    public Long getOrigen() {
      return origen;
    }

    @Override
    public Long getDestino() {
      return destino;
    }

    @Override
    public Long getRutas() {
      return rutas;
    }

    @Override
    public BigDecimal getBultos() {
      return bultos;
    }

    @Override
    public BigDecimal getUnidades() {
      return unidades;
    }

    @Override
    public BigDecimal getLineas() {
      return lineas;
    }

    @Override
    public BigDecimal getCod() {
      return cod;
    }

    @Override
    public String getFrecuencia() {
      return null;
    }
  }

  // ------------------------------------------------------------------
  // Dashboard comparativo
  // ------------------------------------------------------------------

  /**
   * Comparativo antes vs. después sobre la carga agregada por (cluster,
   * chute). La carga de antes sale del chute congelado al cargar el Excel
   * ({@code chute_original_id}). Los operarios de después son los asignados a
   * cada ola; una ola con solo sus rutas repartidas aún no tiene.
   * Con clusterId solo cuenta ese cluster, y el «antes» de sus tiempos es su
   * carga ya balanceada con los operarios que recibe de la ola anterior (si
   * nadie se moviera), junto con el paso de gente desde esa ola. Sin él, el
   * resumen del registro completo con el flujo entre todas las olas.
   */
  private DashboardSimulacionResponse calcularDashboard(Contexto ctx, Long clusterId) {
    Long archivoId = ctx.archivo().getId();
    BigDecimal velocidad = ctx.config().getVelocidadBase();
    if (velocidad == null || velocidad.signum() <= 0) {
      velocidad = SimulacionConfig.VELOCIDAD_INICIAL;
    }

    List<CargaRutas> filasActuales = cargaVisible(ctx);
    Map<Par, Estadistica> cargaActual = new HashMap<>();
    for (CargaRutas fila : filasActuales) {
      if (clusterId == null || clusterId.equals(fila.getClusterId())) {
        cargaActual.put(new Par(fila.getClusterId(), fila.getChuteId()), estadistica(fila));
      }
    }
    List<CargaRutas> filasOriginales = cargaOriginalVisible(ctx);
    Map<Par, Estadistica> cargaOriginal = new HashMap<>();
    for (CargaRutas fila : filasOriginales) {
      if (fila.getChuteId() != null
          && (clusterId == null || clusterId.equals(fila.getClusterId()))) {
        cargaOriginal.put(new Par(fila.getClusterId(), fila.getChuteId()), estadistica(fila));
      }
    }

    Map<Long, Integer> salieron = new HashMap<>();
    Map<Long, Integer> llegaron = new HashMap<>();
    int rutasMovidas = 0;
    List<MovimientoChuteResponse> movimientos = new ArrayList<>();
    for (MovimientoRutas movimiento : movimientosVisibles(ctx, clusterId)) {
      int rutas = Math.toIntExact(movimiento.getRutas());
      salieron.merge(movimiento.getOrigen(), rutas, Integer::sum);
      llegaron.merge(movimiento.getDestino(), rutas, Integer::sum);
      rutasMovidas += rutas;
      movimientos.add(
          new MovimientoChuteResponse(movimiento.getOrigen(), movimiento.getDestino(), rutas));
    }

    // Rutas que cambiaron de cluster (original → actual). En la vista de un
    // cluster, solo las que entraron o salieron de él.
    Map<Long, Integer> entraron = new HashMap<>();
    Map<Long, Integer> salieronCluster = new HashMap<>();
    List<CambioClusterResponse> cambiosCluster = new ArrayList<>();
    for (CambioClusterRutas cambio : cambiosClusterVisibles(ctx)) {
      if (clusterId != null
          && !clusterId.equals(cambio.getOrigen())
          && !clusterId.equals(cambio.getDestino())) {
        continue;
      }
      int rutas = Math.toIntExact(cambio.getRutas());
      salieronCluster.merge(cambio.getOrigen(), rutas, Integer::sum);
      entraron.merge(cambio.getDestino(), rutas, Integer::sum);
      cambiosCluster.add(
          new CambioClusterResponse(
              cambio.getOrigen(),
              ctx.nombreCluster(cambio.getOrigen()),
              cambio.getDestino(),
              ctx.nombreCluster(cambio.getDestino()),
              rutas,
              cambio.getBultos(),
              cambio.getUnidades(),
              cambio.getLineas(),
              (int) Math.min(Integer.MAX_VALUE, cod(cambio.getCod()))));
    }
    cambiosCluster.sort(
        Comparator.comparing(CambioClusterResponse::origen, ORDEN_NATURAL)
            .thenComparing(CambioClusterResponse::destino, ORDEN_NATURAL));

    Long previo = clusterId == null ? null : anterior(ctx, clusterId);
    Map<Long, Integer> heredados = previo == null ? Map.of() : operariosDeOla(ctx, previo);
    ToIntFunction<Par> operariosHeredados = (par) -> heredados.getOrDefault(par.chuteId(), 0);
    ToIntFunction<Par> operariosActuales =
        (par) -> operariosOla(ctx, par.clusterId(), par.chuteId());
    Metricas actual = Metricas.de(cargaActual, operariosActuales, velocidad);
    // Totales de antes (carga del Excel) y tiempos de antes: rutas ya
    // balanceadas con el equipo tal como llega de la ola anterior.
    Metricas original = Metricas.de(cargaOriginal, operariosHeredados, velocidad);
    Metricas sinMoverGente = Metricas.de(cargaActual, operariosHeredados, velocidad);
    BigDecimal tiempoOriginal = sinMoverGente.tiempoTotal();
    BigDecimal tiempoBalanceado = actual.tiempoTotal();
    Map<Long, BigDecimal> limites = limites(ctx);

    Set<Long> clusterIds = new LinkedHashSet<>(actual.porCluster().keySet());
    clusterIds.addAll(original.porCluster().keySet());
    List<ClusterComparativoResponse> clusters =
        clusterIds.stream()
            .sorted(Comparator.comparing(ctx::nombreCluster, ORDEN_NATURAL))
            .map(
                (id) -> {
                  Estadistica antes = original.porCluster().getOrDefault(id, Estadistica.VACIA);
                  Estadistica despues = actual.porCluster().getOrDefault(id, Estadistica.VACIA);
                  BigDecimal limite = limites.getOrDefault(id, BigDecimal.ZERO);
                  BigDecimal tiempo = actual.tiempoCluster(id);
                  return new ClusterComparativoResponse(
                      id,
                      ctx.nombreCluster(id),
                      anulable(sinMoverGente.tiempoCluster(id)),
                      anulable(tiempo),
                      limite,
                      limite.signum() > 0
                          && (sinOperarios(tiempo)
                              || tiempo.compareTo(limite.add(TOLERANCIA)) > 0),
                      Math.toIntExact(antes.rutas()),
                      Math.toIntExact(despues.rutas()),
                      entraron.getOrDefault(id, 0),
                      salieronCluster.getOrDefault(id, 0),
                      antes.unidades(),
                      despues.unidades(),
                      antes.bultos(),
                      despues.bultos(),
                      antes.lineas(),
                      despues.lineas(),
                      antes.codEntero(),
                      despues.codEntero());
                })
            .toList();

    Map<Long, Set<String>> clustersPorChute = new HashMap<>();
    for (Map<Par, Estadistica> carga : List.of(cargaActual, cargaOriginal)) {
      carga.forEach(
          (par, valor) -> {
            if (valor.rutas() > 0) {
              clustersPorChute
                  .computeIfAbsent(par.chuteId(), (id) -> new TreeSet<>(ORDEN_NATURAL))
                  .add(ctx.nombreCluster(par.clusterId()));
            }
          });
    }
    Map<Long, Set<Long>> clustersOriginales = clustersPorChute(cargaOriginal);
    Map<Long, Set<Long>> clustersActuales = clustersPorChute(cargaActual);
    // Operarios del chute tras el balanceo: los de su ola en la vista de un
    // cluster; en el resumen, el máximo entre sus olas. Un chute que se quedó
    // sin carga ya no trabaja en ninguna: 0.
    Map<Long, Integer> operariosDespues = new HashMap<>();
    cargaActual.forEach(
        (par, valor) -> {
          if (valor.rutas() > 0) {
            operariosDespues.merge(
                par.chuteId(), operariosActuales.applyAsInt(par), Math::max);
          }
        });

    Set<Long> chuteIds = new LinkedHashSet<>(actual.porChute().keySet());
    chuteIds.addAll(original.porChute().keySet());
    List<ChuteComparativoResponse> chutes =
        chuteIds.stream()
            .sorted(Comparator.comparing(ctx::nombreChute, PlanoChutes.ORDEN))
            .map(
                (chuteId) -> {
                  Estadistica antes = original.porChute().getOrDefault(chuteId, Estadistica.VACIA);
                  Estadistica despues = actual.porChute().getOrDefault(chuteId, Estadistica.VACIA);
                  BigDecimal tiempoAntes = sinMoverGente.tiempoChute(chuteId);
                  BigDecimal tiempoDespues = actual.tiempoChute(chuteId);
                  String estado;
                  if (sinOperarios(tiempoAntes) && !sinOperarios(tiempoDespues)) {
                    estado = "MEJORO";
                  } else if (!sinOperarios(tiempoAntes) && sinOperarios(tiempoDespues)) {
                    estado = "AUMENTO";
                  } else if (sinOperarios(tiempoAntes)) {
                    estado = "IGUAL";
                  } else if (tiempoDespues.compareTo(tiempoAntes.subtract(TOLERANCIA)) < 0) {
                    estado = "MEJORO";
                  } else if (tiempoDespues.compareTo(tiempoAntes.add(TOLERANCIA)) > 0) {
                    estado = "AUMENTO";
                  } else {
                    estado = "IGUAL";
                  }
                  int operariosAntes = heredados.getOrDefault(chuteId, 0);
                  return new ChuteComparativoResponse(
                      chuteId,
                      ctx.nombreChute(chuteId),
                      operariosAntes,
                      operariosDespues.getOrDefault(chuteId, 0),
                      Math.toIntExact(antes.rutas()),
                      Math.toIntExact(despues.rutas()),
                      salieron.getOrDefault(chuteId, 0),
                      llegaron.getOrDefault(chuteId, 0),
                      anulable(tiempoAntes),
                      anulable(tiempoDespues),
                      estado,
                      antes.bultos(),
                      despues.bultos(),
                      antes.unidades(),
                      despues.unidades(),
                      antes.lineas(),
                      despues.lineas(),
                      antes.codEntero(),
                      despues.codEntero(),
                      List.copyOf(clustersPorChute.getOrDefault(chuteId, Set.of())),
                      nombresCluster(clustersOriginales.get(chuteId), ctx),
                      nombresCluster(clustersActuales.get(chuteId), ctx));
                })
            .toList();

    SimulacionKpisResponse kpis =
        new SimulacionKpisResponse(anulable(tiempoOriginal), anulable(tiempoBalanceado), rutasMovidas);
    List<Long> orden = orden(ctx);
    Map<Long, Integer> rutasMovidasPorCluster = movidasPorCluster(ctx);
    // Cuadro de cada ola sobre todos los clusters, aunque la vista sea de uno.
    Map<Par, Estadistica> todoActual = new HashMap<>();
    filasActuales.forEach(
        (fila) ->
            todoActual.put(new Par(fila.getClusterId(), fila.getChuteId()), estadistica(fila)));
    Metricas cuadro = Metricas.de(todoActual, operariosActuales, velocidad);
    List<OlaSimulacionResponse> olas =
        orden.stream()
            .map(
                (id) -> {
                  Estadistica carga = cuadro.porCluster().getOrDefault(id, Estadistica.VACIA);
                  return new OlaSimulacionResponse(
                      id,
                      ctx.nombreCluster(id),
                      ctx.parametrosCluster().get(id).getEstado(),
                      rutasMovidasPorCluster.getOrDefault(id, 0),
                      limites.getOrDefault(id, BigDecimal.ZERO),
                      anulable(cuadro.tiempoCluster(id)),
                      carga.bultos(),
                      carga.unidades(),
                      carga.lineas(),
                      carga.codEntero());
                })
            .toList();
    return new DashboardSimulacionResponse(
        kpis,
        clusters,
        chutes,
        movimientos,
        cambiosCluster,
        olas,
        flujoOperarios(ctx, filasActuales, orden).stream()
            .filter((paso) -> clusterId == null || paso.destinoId().equals(clusterId))
            .toList(),
        equipoDeVista(ctx, clusterId),
        ctx.config().getOperariosPorChute(),
        clusterId == null ? dotacion(ctx, orden) : List.of());
  }

  /**
   * Operarios de cada chute en cada ola, en el orden de las olas: dónde
   * estuvo el equipo a lo largo de la operación. Solo chutes con gente en
   * alguna ola.
   */
  private static List<DotacionChuteResponse> dotacion(Contexto ctx, List<Long> orden) {
    Set<Long> chutes = new HashSet<>();
    ctx.operariosOla()
        .forEach(
            (par, ola) -> {
              if (ola.getOperarios() > 0) {
                chutes.add(par.chuteId());
              }
            });
    return chutes.stream()
        .sorted(Comparator.comparing(ctx::nombreChute, PlanoChutes.ORDEN))
        .map(
            (chute) ->
                new DotacionChuteResponse(
                    chute,
                    ctx.nombreChute(chute),
                    orden.stream().map((ola) -> operariosOla(ctx, ola, chute)).toList()))
        .toList();
  }

  /**
   * Paso de operarios de cada ola a la siguiente, solo entre olas seguidas que
   * ya tienen operarios (LISTO o CERRADO). Por chute: los que tenía en una ola y en la
   * siguiente; se quedan los que coinciden, salen o llegan los que sobran o
   * faltan.
   */
  private static List<FlujoOperariosResponse> flujoOperarios(
      Contexto ctx, List<CargaRutas> filas, List<Long> orden) {
    Map<Long, Set<Long>> chutesPorOla = new HashMap<>();
    for (CargaRutas fila : filas) {
      if (fila.getRutas() > 0) {
        chutesPorOla
            .computeIfAbsent(fila.getClusterId(), (id) -> new HashSet<>())
            .add(fila.getChuteId());
      }
    }
    List<FlujoOperariosResponse> flujo = new ArrayList<>();
    for (int i = 0; i + 1 < orden.size(); i++) {
      Long origen = orden.get(i);
      Long destino = orden.get(i + 1);
      if (!tieneOperarios(ctx, origen) || !tieneOperarios(ctx, destino)) {
        continue;
      }
      Set<Long> chutes = new HashSet<>(chutesPorOla.getOrDefault(origen, Set.of()));
      chutes.addAll(chutesPorOla.getOrDefault(destino, Set.of()));
      int quedan = 0;
      int salen = 0;
      int llegan = 0;
      List<FlujoChuteResponse> detalle = new ArrayList<>();
      for (Long chute : chutes) {
        int antes = operariosOla(ctx, origen, chute);
        int despues = operariosOla(ctx, destino, chute);
        if (antes == 0 && despues == 0) {
          continue;
        }
        quedan += Math.min(antes, despues);
        salen += Math.max(0, antes - despues);
        llegan += Math.max(0, despues - antes);
        detalle.add(new FlujoChuteResponse(chute, ctx.nombreChute(chute), antes, despues));
      }
      detalle.sort(Comparator.comparing(FlujoChuteResponse::chute, PlanoChutes.ORDEN));
      flujo.add(
          new FlujoOperariosResponse(
              origen,
              ctx.nombreCluster(origen),
              destino,
              ctx.nombreCluster(destino),
              quedan,
              salen,
              llegan,
              detalle));
    }
    return flujo;
  }

  private static boolean tieneOperarios(Contexto ctx, Long clusterId) {
    ParametroCluster parametro = ctx.parametrosCluster().get(clusterId);
    return parametro != null
        && (ParametroCluster.ESTADO_LISTO.equals(parametro.getEstado())
            || ParametroCluster.ESTADO_CERRADO.equals(parametro.getEstado()));
  }

  /**
   * Operarios contra los que se lee la dotación: en la vista de un cluster, los
   * que eligió su ola; en el resumen o si nunca eligió, el equipo del registro.
   */
  private static int equipoDeVista(Contexto ctx, Long clusterId) {
    ParametroCluster parametro = clusterId == null ? null : ctx.parametrosCluster().get(clusterId);
    return parametro != null && parametro.getOperariosOla() != null
        ? parametro.getOperariosOla()
        : operariosMaximos(ctx, operariosBase(ctx));
  }

  /** Operarios asignados al chute en esa ola; 0 si el chute no trabaja en ella. */
  private static int operariosOla(Contexto ctx, Long clusterId, Long chuteId) {
    OperarioOla ola = ctx.operariosOla().get(new Par(clusterId, chuteId));
    return ola == null ? 0 : ola.getOperarios();
  }

  /** Clusters con rutas en cada chute para una distribución de carga. */
  private static Map<Long, Set<Long>> clustersPorChute(Map<Par, Estadistica> carga) {
    Map<Long, Set<Long>> resultado = new HashMap<>();
    carga.forEach(
        (par, valor) -> {
          if (valor.rutas() > 0) {
            resultado.computeIfAbsent(par.chuteId(), (id) -> new HashSet<>()).add(par.clusterId());
          }
        });
    return resultado;
  }

  private static List<String> nombresCluster(Set<Long> clusters, Contexto ctx) {
    return clusters == null
        ? List.of()
        : clusters.stream().map(ctx::nombreCluster).sorted(ORDEN_NATURAL).toList();
  }

  private record Par(Long clusterId, Long chuteId) {}

  /** Ruta que el balanceo cambió de chute y el chute del que salió. */
  private record Traslado(RutaProyeccion ruta, Long origen, Long destino) {}

  private record Estadistica(
      long rutas, BigDecimal bultos, BigDecimal unidades, BigDecimal lineas, long cod) {
    static final Estadistica VACIA =
        new Estadistica(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);

    Estadistica sumar(Estadistica otra) {
      return new Estadistica(
          rutas + otra.rutas,
          bultos.add(otra.bultos),
          unidades.add(otra.unidades),
          lineas.add(otra.lineas),
          cod + otra.cod);
    }

    int codEntero() {
      return (int) Math.min(Integer.MAX_VALUE, cod);
    }
  }

  /** Totales y tiempos por chute y cluster para una distribución de carga. */
  private record Metricas(
      Map<Long, Estadistica> porChute,
      Map<Long, Estadistica> porCluster,
      Map<Long, BigDecimal> tiemposChute,
      Map<Long, BigDecimal> tiemposCluster) {

    static Metricas de(
        Map<Par, Estadistica> carga, ToIntFunction<Par> operarios, BigDecimal velocidad) {
      Map<Long, Estadistica> porChute = new LinkedHashMap<>();
      Map<Long, Estadistica> porCluster = new LinkedHashMap<>();
      carga.forEach(
          (par, valor) -> {
            if (valor.rutas() > 0) {
              porChute.merge(par.chuteId(), valor, Estadistica::sumar);
              porCluster.merge(par.clusterId(), valor, Estadistica::sumar);
            }
          });
      // En cada ola el chute solo procesa las rutas de ese cluster con los
      // operarios de esa ola. El chute suma el tiempo de todas sus olas y la
      // ola cierra con su chute más lento.
      Map<Long, BigDecimal> tiemposChute = new HashMap<>();
      Map<Long, BigDecimal> tiemposCluster = new HashMap<>();
      carga.forEach(
          (par, valor) -> {
            if (valor.rutas() <= 0) {
              return;
            }
            BigDecimal tiempo =
                horas(valor.bultos(), velocidad, operarios.applyAsInt(par));
            tiemposChute.merge(par.chuteId(), tiempo, SimulacionConfiguracionService::sumarHoras);
            tiemposCluster.merge(par.clusterId(), tiempo, BigDecimal::max);
          });
      return new Metricas(porChute, porCluster, tiemposChute, tiemposCluster);
    }

    BigDecimal tiempoChute(Long chute) {
      return tiemposChute.getOrDefault(chute, BigDecimal.ZERO);
    }

    BigDecimal tiempoCluster(Long cluster) {
      return tiemposCluster.getOrDefault(cluster, BigDecimal.ZERO);
    }

    /**
     * Cierre de la operación: los clusters se ejecutan en secuencia, así que el
     * total es la suma de sus tiempos. Si alguna ola se queda sin operarios, el
     * total tampoco es calculable.
     */
    BigDecimal tiempoTotal() {
      BigDecimal total = BigDecimal.ZERO;
      for (BigDecimal tiempo : tiemposCluster.values()) {
        if (sinOperarios(tiempo)) {
          return SIN_OPERARIOS;
        }
        total = total.add(tiempo);
      }
      return total;
    }
  }

  // ------------------------------------------------------------------
  // Contexto y respuestas
  // ------------------------------------------------------------------

  /**
   * Registro, velocidad, parámetros, operarios por ola y nombres de catálogo
   * (sin rutas). operariosOla solo tiene las olas LISTO o CERRADO.
   */
  record Contexto(
      ArchivoExcel archivo,
      /** Balanceo en que se trabaja (L-X-V, M-J-S o Diarias). */
      Balanceo balanceo,
      SimulacionConfig config,
      /** Parámetros de cada cluster en cada balanceo (las mismas entidades en toda vista). */
      Map<Balanceo, Map<Long, ParametroCluster>> parametros,
      Map<Long, ParametroChute> parametrosChute,
      /** Operarios de las olas de cada día (Diarias no tiene). */
      Map<Balanceo, Map<Par, OperarioOla>> olas,
      Map<Long, String> clusters,
      Map<Long, String> chutes,
      /** Chutes del plano agregados en memoria (sin fila en parametros_chutes). */
      Set<Long> chutesAgregados) {

    /** El mismo registro visto desde otro balanceo (sin volver a leer nada). */
    Contexto en(Balanceo otro) {
      return otro == balanceo
          ? this
          : new Contexto(
              archivo, otro, config, parametros, parametrosChute, olas, clusters, chutes,
              chutesAgregados);
    }

    /** Parámetros de los clusters en este balanceo. */
    Map<Long, ParametroCluster> parametrosCluster() {
      return parametros.get(balanceo);
    }

    Map<Long, ParametroCluster> parametrosDe(Balanceo b) {
      return parametros.get(b);
    }

    /** Fila del cluster en ese balanceo; null si no existe (se trata como pendiente). */
    ParametroCluster parametro(Balanceo b, Long clusterId) {
      return parametros.get(b).get(clusterId);
    }

    /** Operarios de las olas de este balanceo. */
    Map<Par, OperarioOla> operariosOla() {
      return olas.get(balanceo);
    }

    String nombreCluster(Long id) {
      return clusters.getOrDefault(id, "Cluster " + id);
    }

    String nombreChute(Long id) {
      return id == null ? null : chutes.getOrDefault(id, "Chute " + id);
    }
  }

  /**
   * Carga el contexto del registro. Con bloquear = true el registro queda
   * bloqueado (FOR UPDATE) hasta el fin de la transacción.
   */
  private Contexto contexto(Long archivoId, boolean bloquear) {
    return contexto(archivoId, Balanceo.LXV, bloquear);
  }

  Contexto contexto(Long archivoId, Balanceo balanceo, boolean bloquear) {
    ArchivoExcel archivo =
        (bloquear ? archivoRepo.bloquear(archivoId) : archivoRepo.findById(archivoId))
            .orElseThrow(
                () ->
                    new RecursoNoEncontradoException(
                        "No existe el registro con id " + archivoId + "."));
    SimulacionConfig config =
        configRepo
            .findById(archivoId)
            .orElseGet(
                () -> {
                  SimulacionConfig inicial = new SimulacionConfig();
                  inicial.setArchivoId(archivoId);
                  inicial.setVelocidadBase(SimulacionConfig.VELOCIDAD_INICIAL);
                  return inicial;
                });
    // Las filas de los tres balanceos, por cluster.
    Map<Balanceo, Map<Long, ParametroCluster>> parametros = new EnumMap<>(Balanceo.class);
    for (Balanceo b : Balanceo.values()) {
      parametros.put(b, new LinkedHashMap<>());
    }
    for (ParametroCluster p : parametroClusterRepo.findByIdArchivoId(archivoId)) {
      parametros
          .get(balanceoGuardado(p.getId().getBalanceo()))
          .putIfAbsent(p.getId().getClusterId(), p);
    }
    Map<Long, ParametroChute> parametrosChute =
        parametroChuteRepo.findByIdArchivoId(archivoId).stream()
            .collect(
                Collectors.toMap(
                    (p) -> p.getId().getChuteId(), Function.identity(), (a, b) -> a, LinkedHashMap::new));
    // Nombres de los chutes que el registro ya tiene (los del Excel).
    Map<Long, String> chutes =
        new HashMap<>(
            chuteRepo.findAllById(parametrosChute.keySet()).stream()
                .collect(Collectors.toMap(Chute::getId, Chute::getNombre)));
    // Los chutes del plano (1–33 sin bloqueados) existen en todo registro aunque su
    // Excel no los traiga: los números que falten se agregan sin carga y sin
    // guardarse (registros antiguos). Se compara por número, no por id: si el
    // registro ya tiene «CHUTE 5», no se agrega otro chute 5 con el nombre «5».
    Set<Integer> presentes =
        chutes.values().stream()
            .map(PlanoChutes::numero)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Set<Long> agregados = new LinkedHashSet<>();
    plano
        .idsVisiblesPorNumero()
        .forEach(
            (numero, chuteId) -> {
              if (presentes.contains(numero) || parametrosChute.containsKey(chuteId)) {
                return;
              }
              ParametroChute vacio = new ParametroChute();
              vacio.setId(new ParametroChuteId(archivoId, chuteId));
              vacio.setOperarios(ParametroChute.OPERARIOS_INICIALES);
              parametrosChute.put(chuteId, vacio);
              chutes.put(chuteId, plano.nombreCatalogo(numero));
              agregados.add(chuteId);
            });
    Map<Balanceo, Map<Par, OperarioOla>> olas = new EnumMap<>(Balanceo.class);
    for (Balanceo b : Balanceo.values()) {
      olas.put(b, new HashMap<>());
    }
    operarioOlaRepo
        .findByIdArchivoId(archivoId)
        .forEach(
            (ola) ->
                olas.get(balanceoGuardado(ola.getId().getBalanceo()))
                    .put(new Par(ola.getId().getClusterId(), ola.getId().getChuteId()), ola));
    Set<Long> clusterIds = new LinkedHashSet<>();
    parametros.values().forEach((porCluster) -> clusterIds.addAll(porCluster.keySet()));
    Map<Long, String> clusters =
        clusterRepo.findAllById(clusterIds).stream()
            .collect(Collectors.toMap(Cluster::getId, Cluster::getNombre));
    return new Contexto(
        archivo, balanceo, config, parametros, parametrosChute, olas, clusters, chutes,
        Set.copyOf(agregados));
  }

  /** Balanceo de una fila guardada; sin dato (filas anteriores a las frecuencias), L-X-V. */
  private static Balanceo balanceoGuardado(String guardado) {
    return guardado == null ? Balanceo.LXV : Balanceo.de(guardado);
  }

  /**
   * Grupos de cada cluster en orden natural, con su carga en este balanceo (las rutas que se ven
   * en él: así cuadran con la hoja de rutas y los totales) y el tramo de chutes que les dieron las
   * diarias. Un grupo sin rutas en este balanceo sigue en la lista (su tramo es del cluster), con
   * cero.
   */
  private Map<Long, List<GrupoClusterResponse>> gruposPorCluster(Contexto ctx) {
    Long archivoId = ctx.archivo().getId();
    Map<Long, Map<String, long[]>> rutas = new HashMap<>();
    Map<Long, Map<String, BigDecimal>> bultos = new HashMap<>();
    for (CargaGrupo fila : cargaGrupos(ctx, true)) {
      if (fila.getRutas() == null || fila.getRutas() == 0) {
        continue;
      }
      String g = fila.getGrupo() == null ? "" : fila.getGrupo();
      boolean suma = visible(ctx, fila.getFrecuencia());
      rutas
          .computeIfAbsent(fila.getClusterId(), (id) -> new HashMap<>())
          .computeIfAbsent(g, (k) -> new long[1])[0] += suma ? fila.getRutas() : 0;
      bultos
          .computeIfAbsent(fila.getClusterId(), (id) -> new HashMap<>())
          .merge(
              g,
              !suma || fila.getBultos() == null ? BigDecimal.ZERO : fila.getBultos(),
              BigDecimal::add);
    }
    Map<Long, Map<String, GrupoGuardado>> guardados = new HashMap<>();
    lotes
        .grupos(archivoId)
        .forEach(
            (g) ->
                guardados
                    .computeIfAbsent(g.clusterId(), (id) -> new HashMap<>())
                    .put(SimulacionExcel.clave(g.grupo()), g));
    Map<Long, List<GrupoClusterResponse>> resultado = new HashMap<>();
    rutas.forEach(
        (clusterId, porGrupo) -> {
          Map<String, GrupoGuardado> suyos = guardados.getOrDefault(clusterId, Map.of());
          List<String> orden = ordenNatural(porGrupo.keySet());
          List<GrupoClusterResponse> lista = new ArrayList<>();
          for (int i = 0; i < orden.size(); i++) {
            String g = orden.get(i);
            GrupoGuardado guardado = suyos.get(SimulacionExcel.clave(g));
            Long desde = guardado == null ? null : guardado.chuteDesdeId();
            Long hasta = guardado == null ? null : guardado.chuteHastaId();
            lista.add(
                new GrupoClusterResponse(
                    g,
                    i + 1,
                    porGrupo.get(g)[0],
                    bultos.get(clusterId).get(g),
                    desde,
                    ctx.nombreChute(desde),
                    hasta,
                    ctx.nombreChute(hasta)));
          }
          resultado.put(clusterId, lista);
        });
    return resultado;
  }

  private ConfiguracionSimulacionResponse aConfiguracion(Contexto ctx) {
    // Una sola lectura de la carga: la del balanceo y las rutas por frecuencia salen de ahí.
    List<CargaRutas> cruda = rutaRepo.cargaPorArchivo(ctx.archivo().getId());
    List<CargaRutas> carga = sumarCarga(ctx, cruda);
    Map<Long, long[]> porFrecuencia = rutasPorFrecuencia(cruda);
    Map<Long, BigDecimal> limites = limites(ctx);
    Map<Long, Integer> operarios = operariosBase(ctx);
    Map<Long, Integer> diferenciasOriginales =
        diferenciasPorCluster(cargaOriginalVisible(ctx));
    Map<Long, List<GrupoClusterResponse>> grupos = gruposPorCluster(ctx);
    List<ClusterParametroResponse> clusters =
        ctx.parametrosCluster().keySet().stream()
            .map(
                (id) ->
                    new ClusterParametroResponse(
                        id,
                        ctx.nombreCluster(id),
                        limites.getOrDefault(id, BigDecimal.ZERO),
                        ctx.parametrosCluster().get(id).getEstado(),
                        diferenciasOriginales.get(id),
                        ctx.parametrosCluster().get(id).getChuteDesdeId(),
                        ctx.parametrosCluster().get(id).getChuteHastaId(),
                        ctx.parametrosCluster().get(id).getOperariosOla(),
                        grupos.getOrDefault(id, List.of()),
                        ctx.balanceo().name(),
                        new ClusterParametroResponse.AvanceClusterResponse(
                            estadoEn(ctx, Balanceo.DIA, id),
                            estadoEn(ctx, Balanceo.LXV, id),
                            estadoEn(ctx, Balanceo.MJS, id),
                            porFrecuencia.getOrDefault(id, new long[3])[0],
                            porFrecuencia.getOrDefault(id, new long[3])[1],
                            porFrecuencia.getOrDefault(id, new long[3])[2]),
                        diariasFijas(ctx, id)))
            .sorted(Comparator.comparing(ClusterParametroResponse::nombre, ORDEN_NATURAL))
            .toList();
    Map<Long, List<Long>> clustersEnChute = new HashMap<>();
    carga.forEach(
        (fila) -> {
          if (fila.getRutas() > 0) {
            clustersEnChute
                .computeIfAbsent(fila.getChuteId(), (id) -> new ArrayList<>())
                .add(fila.getClusterId());
          }
        });
    // Los bloqueados no se muestran. Solo si un registro anterior al bloqueo aún
    // tiene rutas en uno, va marcado (no recibe rutas; las suyas se pueden sacar).
    List<ChuteParametroResponse> chutes =
        ctx.parametrosChute().entrySet().stream()
            .filter((entrada) -> !bloqueado(ctx, entrada.getKey()) || clustersEnChute.containsKey(entrada.getKey()))
            .map(
                (entrada) ->
                    new ChuteParametroResponse(
                        entrada.getKey(),
                        ctx.nombreChute(entrada.getKey()),
                        operarios.get(entrada.getKey()),
                        clustersEnChute.getOrDefault(entrada.getKey(), List.of()),
                        bloqueado(ctx, entrada.getKey()),
                        entrada.getValue().isBuff()))
            .sorted(Comparator.comparing(ChuteParametroResponse::nombre, PlanoChutes.ORDEN))
            .toList();
    List<CargaSimulacionResponse> cargaResponse =
        carga.stream()
            .map(
                (fila) ->
                    new CargaSimulacionResponse(
                        fila.getClusterId(),
                        fila.getChuteId(),
                        fila.getRutas(),
                        fila.getBultos(),
                        fila.getUnidades(),
                        fila.getLineas(),
                        cod(fila.getCod()),
                        Optional.ofNullable(
                                ctx.operariosOla()
                                    .get(new Par(fila.getClusterId(), fila.getChuteId())))
                            .map(OperarioOla::getOperarios)
                            .orElse(null)))
            .toList();
    return new ConfiguracionSimulacionResponse(
        SimulacionRespuestas.archivo(ctx.archivo()),
        ctx.config().getVelocidadBase(),
        operariosMaximos(ctx, operarios),
        ctx.config().getOperariosPorChute(),
        topePorChute(ctx, operarios),
        clusters,
        chutes,
        cargaResponse,
        plano.nombresBloqueados(),
        ctx.balanceo().name(),
        buffBloqueado(ctx));
  }

  private static Map<Long, BigDecimal> limites(Contexto ctx) {
    Map<Long, BigDecimal> limites = new HashMap<>();
    ctx.parametrosCluster()
        .forEach(
            (id, p) -> limites.put(id, p.getLimiteHoras() == null ? BigDecimal.ZERO : p.getLimiteHoras()));
    return limites;
  }

  /** Base de cada chute: los operarios que fijó el usuario. */
  private static Map<Long, Integer> operariosBase(Contexto ctx) {
    Map<Long, Integer> operarios = new HashMap<>();
    ctx.parametrosChute().forEach((id, p) -> operarios.put(id, base(p)));
    return operarios;
  }

  private static int base(ParametroChute p) {
    return p.getOperarios() == null ? ParametroChute.OPERARIOS_INICIALES : p.getOperarios();
  }

  /**
   * Máximo de operarios en un chute que aplica el balanceo: el que definió el
   * usuario o, si no hay, todo el equipo (la ola reparte a su gente entre sus
   * chutes según la carga).
   */
  private static int topePorChute(Contexto ctx, Map<Long, Integer> operarios) {
    return topePorChute(ctx, operariosMaximos(ctx, operarios));
  }

  /** Tope por chute para una ola de esa cantidad: el definido o, si no hay, toda la ola. */
  private static int topePorChute(Contexto ctx, int equipo) {
    Integer definido = ctx.config().getOperariosPorChute();
    if (definido != null && definido > 0) {
      return Math.min(definido, Limites.MAX_OPERARIOS);
    }
    return Math.max(1, Math.min(Limites.MAX_OPERARIOS, equipo));
  }

  /** 0 o nulo: sin tope propio (así se guarda y se muestra). */
  private static Integer sinTope(Integer valor) {
    return valor == null || valor <= 0 ? null : valor;
  }

  /**
   * Operarios de referencia cuando no se mira una ola concreta (resumen del
   * dashboard y tope «sin tope» de la vista): la ola más grande que ya eligió
   * su cantidad o, si ninguna lo hizo, la suma de la base de los chutes. Ya no
   * existe un equipo del registro: `operarios_maximos` queda sin uso.
   */
  private static int operariosMaximos(Contexto ctx, Map<Long, Integer> operarios) {
    int mayorOla =
        ctx.parametrosCluster().values().stream()
            .map(ParametroCluster::getOperariosOla)
            .filter(Objects::nonNull)
            .mapToInt(Integer::intValue)
            .max()
            .orElse(0);
    if (mayorOla > 0) {
      return mayorOla;
    }
    int asignados = operarios.values().stream().mapToInt(Integer::intValue).sum();
    // Sin tope y sin operarios: al menos uno por chute para que el balanceo
    // pueda repartir el mínimo en cada ola.
    // Uno por chute del registro: los del plano agregados en memoria no cuentan
    // (mismo valor que antes del plano).
    return asignados > 0
        ? asignados
        : Math.max(1, ctx.parametrosChute().size() - ctx.chutesAgregados().size());
  }

  /** La consulta agrega COD como decimal; los contadores del balanceo son enteros. */
  private static long cod(BigDecimal valor) {
    return valor == null ? 0 : valor.longValue();
  }

  /** Convierte una fila agregada de rutas en los totales que usa el dashboard. */
  private static Estadistica estadistica(CargaRutas fila) {
    return new Estadistica(
        fila.getRutas(), fila.getBultos(), fila.getUnidades(), fila.getLineas(), cod(fila.getCod()));
  }

  /**
   * Horas = bultos / (velocidad × operarios). Sin bultos no hay tiempo (0);
   * con bultos pero sin operarios se devuelve la marca {@link #SIN_OPERARIOS}.
   */
  private static BigDecimal horas(BigDecimal bultos, BigDecimal velocidad, int operarios) {
    if (bultos == null || bultos.signum() == 0) {
      return BigDecimal.ZERO;
    }
    if (operarios <= 0) {
      return SIN_OPERARIOS;
    }
    return bultos.divide(
        velocidad.multiply(BigDecimal.valueOf(operarios)), ESCALA, RoundingMode.HALF_UP);
  }

  /** Suma de tiempos que conserva la marca «sin operarios» si alguno la tiene. */
  private static BigDecimal sumarHoras(BigDecimal a, BigDecimal b) {
    return sinOperarios(a) || sinOperarios(b) ? SIN_OPERARIOS : a.add(b);
  }

  /** Convierte la marca interna «sin operarios» en null para la respuesta. */
  private static BigDecimal anulable(BigDecimal horas) {
    return sinOperarios(horas) ? null : horas;
  }

  private static boolean sinOperarios(BigDecimal horas) {
    return horas != null && horas.compareTo(SIN_OPERARIOS) == 0;
  }

  /** Texto de un tiempo para mensajes: «sin operarios» si no hay cálculo posible. */
  private static String textoHoras(BigDecimal horas) {
    return sinOperarios(horas)
        ? "sin operarios"
        : horas.setScale(2, RoundingMode.HALF_UP).toPlainString() + " h";
  }

  private void tocar(ArchivoExcel archivo, Long actor) {
    Auditoria.alActualizar(archivo, actor);
    archivoRepo.save(archivo);
  }

  /**
   * Clave de caché de una lectura del registro: incluye su última
   * actualización, que cambia en cada escritura (tocar), así que una respuesta
   * vieja nunca se sirve y no hace falta invalidar a mano.
   */
  /**
   * Respuesta de solo lectura cacheada por registro y versión: un acierto
   * cuesta una consulta (el registro, por su actualizadoEn) y el contexto
   * completo (parámetros, operarios, nombres) solo se carga para recalcular.
   * Con muchos usuarios mirando el mismo registro, Caffeine calcula una sola
   * vez por clave y los demás esperan ese resultado.
   */
  private <T> T cacheado(Long archivoId, String vista, Function<Contexto, T> calculo) {
    return cacheado(archivoId, Balanceo.LXV, vista, calculo);
  }

  private <T> T cacheado(
      Long archivoId, Balanceo balanceo, String vista, Function<Contexto, T> calculo) {
    ArchivoExcel archivo =
        archivoRepo
            .findById(archivoId)
            .orElseThrow(
                () ->
                    new RecursoNoEncontradoException(
                        "No existe el registro con id " + archivoId + "."));
    String clave =
        "simulacion:" + archivo.getId() + ":" + archivo.getActualizadoEn() + ":" + balanceo + ":" + vista;
    return cache.obtener(clave, () -> calculo.apply(contexto(archivoId, balanceo, false)));
  }

  /**
   * Lecturas: con tope TOTAL en la transacción (Spring reparte el tiempo
   * restante entre sus sentencias y PostgreSQL las cancela; cada una además
   * bajo statement_timeout). Si vence, TransactionTimedOutException -> 503.
   */
  private <T> ApiResponse<T> leer(String operacion, Supplier<T> trabajo, String mensaje) {
    T datos =
        Trazas.medir(
            log,
            "SimulacionConfiguracionService." + operacion,
            () -> transacciones.consultar(trabajo, TOPE_TRANSACCION_LECTURA_S));
    return ApiResponse.ok(mensaje, datos);
  }

  /**
   * Escrituras: sin tope total (cortarlas a mitad dejaría al usuario con un
   * error de algo que sí se guardó); las acota statement_timeout por sentencia.
   */
  private <T> ApiResponse<T> escribir(String operacion, Supplier<T> trabajo, String mensaje) {
    T datos =
        Trazas.medir(
            log, "SimulacionConfiguracionService." + operacion, () -> transacciones.ejecutar(trabajo));
    return ApiResponse.ok(mensaje, datos);
  }

  /** Ruta movida; lanzamientoId null = movimiento manual. */
  private static HistorialBalanceo movimiento(
      Long archivoId,
      Long lanzamientoId,
      Traslado traslado,
      String motivo,
      Long actor,
      Instant momento) {
    HistorialBalanceo log =
        nuevoLog(archivoId, HistorialBalanceo.ACCION_MOVER_RUTA, motivo, actor, momento);
    log.setLanzamientoId(lanzamientoId);
    log.setRutaId(traslado.ruta().getId());
    log.setChuteOrigenId(traslado.origen());
    log.setChuteDestinoId(traslado.destino());
    return log;
  }

  /** Operarios del chute en la ola antes y después de la asignación del paso 2. */
  private static HistorialBalanceo asignados(
      Long archivoId,
      Long lanzamientoId,
      Long chuteId,
      int antes,
      int despues,
      Long actor,
      Instant momento) {
    HistorialBalanceo log =
        nuevoLog(
            archivoId,
            HistorialBalanceo.ACCION_ASIGNAR_OPERARIOS,
            HistorialBalanceo.MOTIVO_BALANCEO,
            actor,
            momento);
    log.setLanzamientoId(lanzamientoId);
    log.setChuteModificadoId(chuteId);
    log.setOperariosOrigen(antes);
    log.setOperariosDestino(despues);
    return log;
  }

  private static HistorialBalanceo nuevoLog(
      Long archivoId, String accion, String motivo, Long actor, Instant momento) {
    HistorialBalanceo log = new HistorialBalanceo();
    log.setArchivoId(archivoId);
    log.setTipoAccion(accion);
    log.setMotivo(motivo);
    log.setCreadoPor(actor);
    log.setCreadoEn(momento);
    return log;
  }

  /** Orden natural (CHUTE 2 antes que CHUTE 10); también lo usa el reporte. */
  static int compararNatural(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      char ca = a.charAt(i);
      char cb = b.charAt(j);
      if (Character.isDigit(ca) && Character.isDigit(cb)) {
        int inicioA = i;
        int inicioB = j;
        while (i < a.length() && Character.isDigit(a.charAt(i))) {
          i++;
        }
        while (j < b.length() && Character.isDigit(b.charAt(j))) {
          j++;
        }
        String numeroA = a.substring(inicioA, i).replaceFirst("^0+(?!$)", "");
        String numeroB = b.substring(inicioB, j).replaceFirst("^0+(?!$)", "");
        int comparacion = Integer.compare(numeroA.length(), numeroB.length());
        if (comparacion != 0) {
          return comparacion;
        }
        comparacion = numeroA.compareTo(numeroB);
        if (comparacion != 0) {
          return comparacion;
        }
      } else {
        int comparacion = Character.compare(Character.toUpperCase(ca), Character.toUpperCase(cb));
        if (comparacion != 0) {
          return comparacion;
        }
        i++;
        j++;
      }
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }
}
