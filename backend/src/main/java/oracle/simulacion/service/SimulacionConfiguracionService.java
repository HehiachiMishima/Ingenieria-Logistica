package oracle.simulacion.service;

import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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
import oracle.simulacion.response.HistorialRegistroResponse;
import oracle.simulacion.response.HistorialSimulacionResponse;
import oracle.simulacion.response.LanzamientoSimulacionResponse;
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
        Balanceo.diaria(ruta.getFrecuencia()) && diariasFijas(ctx, ruta.getClusterId()));
  }

  /** DIARIA, LXV o MJS; las rutas sin dato (anteriores a las frecuencias) son diarias. */
  private static String frecuencia(RutaProyeccion ruta) {
    return ruta.getFrecuencia() == null ? Balanceo.DIARIA : ruta.getFrecuencia();
  }

  /** Parámetros de los clusters en ese balanceo (el del contexto o el otro). */
  private static Map<Long, ParametroCluster> parametrosDe(Contexto ctx, Balanceo balanceo) {
    return balanceo == ctx.balanceo() ? ctx.parametrosCluster() : ctx.parametrosOtro();
  }

  /**
   * La ruta puede cambiar de cluster en cada balanceo donde aparece (las diarias, en los dos): su
   * cluster y al menos otro siguen sin cerrar.
   */
  private static boolean rutaEditable(Contexto ctx, RutaProyeccion ruta) {
    for (Balanceo b : Balanceo.values()) {
      if (b.incluye(ruta.getFrecuencia())
          && !clusterEditable(parametrosDe(ctx, b), ruta.getClusterId())) {
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
   * Velocidad, máximo por chute y buff valen para los dos balanceos; los límites de los clusters
   * son del balanceo indicado.
   */
  public ApiResponse<ConfiguracionSimulacionResponse> guardar(
      Long archivoId, Balanceo balanceo, ConfiguracionActualizarRequest req) {
    Long actor = ActorActual.id();
    return escribir(
        "guardar id=" + archivoId + " balanceo=" + balanceo,
        () -> {
          Contexto ctx = contexto(archivoId, balanceo, true);
          SimulacionConfig config = ctx.config();
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
          // Velocidad y máximo por chute son de los dos balanceos: un cluster cerrado en
          // cualquiera de ellos ya los usó.
          Set<Long> cerradosAmbos = new LinkedHashSet<>(cerrados(ctx));
          ctx.parametrosOtro()
              .forEach(
                  (id, p) -> {
                    if (ParametroCluster.ESTADO_CERRADO.equals(p.getEstado())) {
                      cerradosAmbos.add(id);
                    }
                  });
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
              ParametroCluster parametro = ctx.parametrosCluster().get(limite.clusterId());
              if (parametro == null) {
                throw new SolicitudInvalidaException(
                    "El cluster " + limite.clusterId() + " no pertenece al registro.");
              }
              if (parametro.getLimiteHoras() == null
                  || parametro.getLimiteHoras().compareTo(limite.limiteHoras()) != 0) {
                if (ParametroCluster.ESTADO_CERRADO.equals(parametro.getEstado())) {
                  throw new ConflictoException(
                      ctx.nombreCluster(limite.clusterId())
                          + " está cerrado: reábrelo para cambiar su límite.");
                }
                limiteCambiado.add(limite.clusterId());
              }
              parametro.setLimiteHoras(limite.limiteHoras());
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
                          + " clusters ya balanceados (en L-X-V y M-J-S) para cambiarlo.");
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
            invalidarOperariosOtro(ctx, ctx.parametrosOtro().keySet());
          }
          invalidarOperarios(ctx, invalidar);
          tocar(ctx.archivo(), actor);
          return aConfiguracion(ctx);
        },
        "Parámetros guardados.");
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
   * Mueve a mano una ruta del balanceo. A mano todo se puede mover, también las diarias fijas;
   * como una diaria tiene un solo chute, moverla también la cambia en el otro balanceo.
   */
  public ApiResponse<ConfiguracionSimulacionResponse> mover(
      Long archivoId, Balanceo balanceo, Long rutaId, MoverRutaRequest req) {
    Long actor = ActorActual.id();
    return escribir(
        "mover archivo=" + archivoId + " balanceo=" + balanceo + " ruta=" + rutaId,
        () -> {
          Contexto ctx = contexto(archivoId, balanceo, true);
          RutaProyeccion ruta = rutaDelBalanceo(ctx, rutaId);
          boolean diaria = Balanceo.diaria(ruta.getFrecuencia());
          if (diaria) {
            exigirOtroAbierto(ctx, ruta.getClusterId());
          }
          if (!ctx.parametrosChute().containsKey(req.chuteId())) {
            throw new SolicitudInvalidaException(
                "El chute " + req.chuteId() + " no pertenece al registro.");
          }
          exigirNoBloqueado(ctx, req.chuteId());
          if (cerrado(ctx, ruta.getClusterId())) {
            throw new ConflictoException(
                ctx.nombreCluster(ruta.getClusterId())
                    + " está cerrado: reábrelo para mover sus rutas.");
          }
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
          manual.setBalanceo(ctx.balanceo().name());
          // Trabajo del cluster en curso: restablecerlo (o reabrir uno anterior) lo deshace.
          manual.setClusterTrabajoId(enCurso(ctx));
          historialRepo.save(manual);
          // Mover una ruta cambia la carga sobre la que se asignaron los
          // operarios de su ola: solo ese cluster vuelve al paso 2 (si es diaria,
          // también en el otro balanceo).
          invalidarOperarios(ctx, List.of(ruta.getClusterId()));
          if (diaria) {
            invalidarOperariosOtro(ctx, List.of(ruta.getClusterId()));
          }
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
      throw new ConflictoException(
          "La ruta "
              + ruta.getCodigoRuta()
              + " es de "
              + SimulacionExcel.textoFrecuencia(ruta.getFrecuencia())
              + ": no aparece en el balanceo "
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

  /** Igual, en un balanceo: una ruta diaria cambia de cluster también en el otro. */
  public ApiResponse<ConfiguracionSimulacionResponse> cambiarCluster(
      Long archivoId, Balanceo balanceo, Long rutaId, CambiarClusterRequest req) {
    Long actor = ActorActual.id();
    return escribir(
        "cambiar cluster archivo=" + archivoId + " balanceo=" + balanceo + " ruta=" + rutaId,
        () -> {
          Contexto ctx = contexto(archivoId, balanceo, true);
          RutaProyeccion ruta = rutaDelBalanceo(ctx, rutaId);
          boolean diaria = Balanceo.diaria(ruta.getFrecuencia());
          if (diaria) {
            exigirOtroAbierto(ctx, ruta.getClusterId());
            exigirOtroAbierto(ctx, req.clusterId());
          }
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
          exigirAbierto(ctx, origen);
          exigirAbierto(ctx, destino);
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
          log.setBalanceo(ctx.balanceo().name());
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
            movimiento.setBalanceo(ctx.balanceo().name());
            movimiento.setClusterTrabajoId(enCurso(ctx));
            historialRepo.save(movimiento);
          }
          // Cambia la carga de los dos clusters: si ya tenían operarios, vuelven al paso 2.
          invalidarOperarios(ctx, List.of(origen, destino));
          if (diaria) {
            invalidarOperariosOtro(ctx, List.of(origen, destino));
          }
          tocar(ctx.archivo(), actor);
          return aConfiguracion(ctx);
        },
        "Ruta cambiada de cluster.");
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
   * del paso. Paso 1: el rango dice entre qué chutes quedan las rutas del
   * cluster; sin rango, el último elegido o, si nunca se eligió, los chutes que
   * ya usa. Paso 2: la cantidad de operarios con que se calcula la ola; sin
   * cantidad, la última usada en ese cluster o, si nunca se eligió, el equipo
   * del registro.
   */
  public ApiResponse<BalanceoResponse> balancear(
      Long archivoId, Long clusterId, FaseBalanceo fase, BalanceoRequest pedido) {
    return balancear(archivoId, Balanceo.LXV, clusterId, fase, pedido);
  }

  /** Un paso de un cluster en un balanceo (L-X-V o M-J-S). */
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
                            : asignarOperarios(
                                archivoId,
                                balanceo,
                                clusterId,
                                pedido == null ? null : pedido.operarios(),
                                actor)));
    return ApiResponse.ok(datos.mensaje(), datos);
  }

  /** Todo el registro vuelve a la proyección: rutas, operarios, lanzamientos e historial. */
  public ApiResponse<Void> restablecer(Long archivoId) {
    return restablecer(archivoId, Balanceo.LXV);
  }

  /**
   * Un balanceo vuelve a la proyección: sus rutas, operarios, lanzamientos e historial. Las rutas
   * diarias solo vuelven atrás en los clusters que el otro balanceo aún no trabajó (si no, él ya
   * cuenta con ellas y siguen fijas).
   */
  public ApiResponse<Void> restablecer(Long archivoId, Balanceo balanceo) {
    Long actor = ActorActual.id();
    Trazas.medir(
        log,
        "SimulacionConfiguracionService.restablecer id=" + archivoId + " balanceo=" + balanceo,
        () ->
            transacciones.<Void>ejecutar(
                () -> {
                  deshacerHistorial(contexto(archivoId, balanceo, true), actor);
                  return null;
                }));
    return ApiResponse.ok("Datos restablecidos.", null);
  }

  /**
   * El cluster en curso vuelve a como estaba antes de trabajarlo: se deshace su
   * balanceo y lo que se cambió a mano mientras estuvo en curso (movimientos y
   * rutas que entraron o salieron de él), sin operarios de ola. Los clusters
   * cerrados no cambian.
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
          // Deshace todo lo hecho mientras estuvo en curso: su balanceo y los
          // cambios a mano (incluidas rutas que entraron o salieron de él).
          reiniciarClusters(ctx, List.of(clusterId));
          actualizarEstado(ctx);
          tocar(ctx.archivo(), actor);
          return aConfiguracion(ctx);
        },
        "Cluster restablecido.");
  }

  /**
   * Cierra el cluster en curso con sus operarios asignados. El equipo pasa al
   * siguiente cluster con esta dotación; al cerrar el último, la simulación
   * termina.
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
          ParametroCluster parametro = exigirEnCurso(ctx, clusterId);
          String nombre = ctx.nombreCluster(clusterId);
          if (!ParametroCluster.ESTADO_LISTO.equals(parametro.getEstado())) {
            throw new ConflictoException(
                "Balancea las rutas y asigna los operarios de " + nombre + " antes de cerrarlo.");
          }
          parametro.setEstado(ParametroCluster.ESTADO_CERRADO);
          actualizarEstado(ctx);
          tocar(ctx.archivo(), actor);
          return aConfiguracion(ctx);
        },
        "Cluster cerrado.");
  }

  /**
   * Reabre un cluster cerrado para cambiarlo. Los clusters siguientes partían
   * de su dotación, así que vuelven a la proyección (rutas, operarios y
   * lanzamientos); el reabierto conserva sus rutas y operarios.
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
          List<Long> siguientes =
              orden.subList(orden.indexOf(clusterId) + 1, orden.size()).stream()
                  .filter(
                      (id) ->
                          !ParametroCluster.ESTADO_PENDIENTE.equals(
                              ctx.parametrosCluster().get(id).getEstado()))
                  .toList();
          reiniciarClusters(ctx, siguientes);
          parametro.setEstado(ParametroCluster.ESTADO_LISTO);
          actualizarEstado(ctx);
          tocar(ctx.archivo(), actor);
          return aConfiguracion(ctx);
        },
        "Cluster reabierto.");
  }

  public ApiResponse<HistorialRegistroResponse> historial(Long archivoId) {
    return historial(archivoId, Balanceo.LXV);
  }

  /** Lanzamientos de un balanceo (y cuántos cambios a mano tiene). */
  public ApiResponse<HistorialRegistroResponse> historial(Long archivoId, Balanceo balanceo) {
    return leer(
        "historial id=" + archivoId + " balanceo=" + balanceo,
        () -> {
          Contexto ctx = contexto(archivoId, balanceo, false);
          List<LanzamientoSimulacionResponse> lanzamientos =
              lanzamientoRepo
                  .findTop200ByArchivoIdAndBalanceoOrderByIdDesc(archivoId, balanceo.name())
                  .stream()
                  .map(
                      (lanzamiento) ->
                          new LanzamientoSimulacionResponse(
                              lanzamiento.getId(),
                              lanzamiento.getClusterId(),
                              ctx.nombreCluster(lanzamiento.getClusterId()),
                              lanzamiento.getFase(),
                              lanzamiento.getMensaje(),
                              lanzamiento.getCreadoEn(),
                              lanzamiento.getCreadoPor(),
                              lanzamiento.getRutas(),
                              lanzamiento.getChutesOperarios()))
                  .toList();
          return new HistorialRegistroResponse(
              lanzamientos,
              historialRepo.countByArchivoIdAndBalanceoAndLanzamientoIdIsNull(
                  archivoId, balanceo.name()));
        },
        "Historial de balanceo.");
  }

  /**
   * Cambios de un lanzamiento (o, con lanzamientoId nulo, los que no tienen
   * lanzamiento), del más reciente al más antiguo y paginados: un balanceo
   * grande mueve miles de rutas.
   */
  public ApiResponse<PaginaResponse<HistorialSimulacionResponse>> historialCambios(
      Long archivoId, Long lanzamientoId, int pagina, int tamano) {
    return historialCambios(archivoId, Balanceo.LXV, lanzamientoId, pagina, tamano);
  }

  public ApiResponse<PaginaResponse<HistorialSimulacionResponse>> historialCambios(
      Long archivoId, Balanceo balanceo, Long lanzamientoId, int pagina, int tamano) {
    PageRequest pageable =
        PageRequest.of(
            Math.min(Math.max(pagina, 1), MAX_PAGINA) - 1,
            Math.min(Math.max(tamano, 1), MAX_TAMANO_PAGINA),
            Sort.by("id").descending());
    return leer(
        "historial cambios id=" + archivoId + " lanzamiento=" + lanzamientoId,
        () -> {
          Contexto ctx = contexto(archivoId, false);
          Page<HistorialBalanceo> page =
              lanzamientoId == null
                  ? historialRepo.findByArchivoIdAndBalanceoAndLanzamientoIdIsNull(
                      archivoId, balanceo.name(), pageable)
                  : historialRepo.findByArchivoIdAndLanzamientoId(archivoId, lanzamientoId, pageable);
          Set<Long> rutaIds = new HashSet<>();
          page.forEach(
              (log) -> {
                if (log.getRutaId() != null) {
                  rutaIds.add(log.getRutaId());
                }
              });
          Map<Long, RutaProyeccion> rutas =
              rutaRepo.findAllById(rutaIds).stream()
                  .collect(Collectors.toMap(RutaProyeccion::getId, Function.identity()));
          List<HistorialSimulacionResponse> cambios =
              page.getContent().stream()
                  .map(
                      (log) -> {
                        RutaProyeccion ruta =
                            log.getRutaId() == null ? null : rutas.get(log.getRutaId());
                        return new HistorialSimulacionResponse(
                            log.getId(),
                            log.getLanzamientoId(),
                            log.getTipoAccion(),
                            log.getMotivo(),
                            log.getRutaId(),
                            ruta == null ? null : ruta.getCodigoRuta(),
                            ruta == null ? null : ctx.nombreCluster(ruta.getClusterId()),
                            ruta == null || ruta.getTipo() == null ? null : ruta.getTipo().name(),
                            ruta == null ? null : ruta.getTotalBultos(),
                            ctx.nombreChute(log.getChuteOrigenId()),
                            ctx.nombreChute(log.getChuteDestinoId()),
                            ctx.nombreChute(log.getChuteModificadoId()),
                            log.getClusterOrigenId() == null
                                ? null
                                : ctx.nombreCluster(log.getClusterOrigenId()),
                            log.getClusterDestinoId() == null
                                ? null
                                : ctx.nombreCluster(log.getClusterDestinoId()),
                            log.getOperariosOrigen(),
                            log.getOperariosDestino(),
                            log.getCreadoEn(),
                            log.getCreadoPor());
                      })
                  .toList();
          return PaginaResponse.de(page, cambios);
        },
        "Cambios del historial.");
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
   */
  private BalanceoResponse balancearRutas(
      Long archivoId, Balanceo balanceo, Long clusterId, BalanceoRequest pedido, Long actor) {
    Contexto ctx = contexto(archivoId, balanceo, true);
    ParametroCluster parametro = exigirEnCurso(ctx, clusterId);
    String nombre = ctx.nombreCluster(clusterId);
    // Si el otro balanceo ya trabajó este cluster, su rango y los tramos de sus
    // grupos valen también aquí: así una ruta diaria fija queda en el tramo de su grupo.
    boolean heredado = rangoDelOtro(ctx, clusterId);
    List<Long> rango =
        heredado
            ? rangoGuardado(ctx, ctx.parametrosOtro().get(clusterId))
            : rangoDeBalanceo(ctx, parametro, pedido);
    // Las diarias se acomodan solo en el primer balanceo automático del cluster.
    boolean fijas = diariasFijas(ctx, clusterId);
    List<RutaProyeccion> todasCluster =
        rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(archivoId, clusterId);
    List<RutaProyeccion> rutasCluster =
        todasCluster.stream().filter((r) -> visible(ctx, r.getFrecuencia())).toList();
    Set<Long> inmoviles =
        fijas
            ? rutasCluster.stream()
                .filter((r) -> Balanceo.diaria(r.getFrecuencia()))
                .map(RutaProyeccion::getId)
                .collect(Collectors.toSet())
            : Set.of();
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
    } else {
      List<String> orden =
          ordenGrupos(
              nombre,
              porGrupoTodas.keySet(),
              guardados,
              pedido == null ? null : pedido.prioridad(),
              porGrupoTodas.size() > 1);
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
            TramosGrupos.repartir(rango, cargaDeGrupos(orden, porGrupoTodas));
        tramos = reparto.tramos();
        sinUsar = reparto.sinUsar();
      } else {
        // Un solo grupo (o ninguno): el balanceo de siempre sobre todo el rango.
        tramos =
            cargaDeGrupos(orden, porGrupoTodas).stream()
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
        // Cada ruta de fuera entra una sola vez: termina en a lo sumo rutasDe pasos.
        while ((traslado = reparto.traerAlRango(clusterId)) != null) {
          nivelaciones.add(traslado);
          traidas++;
        }
      }
      fueraDelRango += traidas;
      // Chutes con buff: reciben primero las rutas más pesadas, que quedan ahí.
      if (!chutesBuff.isEmpty()) {
        List<Traslado> anclas = reparto.anclar(clusterId, chutesBuff);
        nivelaciones.addAll(anclas);
        ancladas += anclas.size();
      }
      // Cada movimiento baja la suma de cuadrados de la carga, así que el
      // proceso termina solo; el tope es una red de seguridad.
      int maximo = traidas + Math.max(MAX_ITERACIONES, 4 * reparto.rutasDe(clusterId));
      int pasos = traidas;
      while (pasos < maximo && (traslado = reparto.nivelar(clusterId)) != null) {
        nivelaciones.add(traslado);
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
    if (heredado) {
      mensaje
          .append(" Rango y grupos definidos en ")
          .append(ctx.balanceo().otro().etiqueta())
          .append(".");
    }
    if (tramos.size() > 1) {
      mensaje.append(" Grupos por prioridad: ");
      for (int i = 0; i < tramos.size(); i++) {
        TramosGrupos.Tramo tramo = tramos.get(i);
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
   * Vista previa del paso 1: los grupos del cluster en el orden de prioridad
   * (la pedida o, si no, la guardada y luego por nombre) y el tramo que recibiría
   * cada uno con el rango (el pedido o, si no, el último). No cambia nada; si
   * así no se podría balancear, lo dice en {@code aviso}.
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
          // Carga de cada grupo por chute: totales y cuántas rutas quedan fuera de su tramo.
          Map<String, Map<Long, Long>> rutasPorChute = new LinkedHashMap<>();
          Map<String, TramosGrupos.Grupo> grupos = new LinkedHashMap<>();
          for (CargaGrupo fila : cargaGrupos(ctx, true)) {
            if (!clusterId.equals(fila.getClusterId()) || fila.getRutas() == null || fila.getRutas() == 0) {
              continue;
            }
            String g = fila.getGrupo() == null ? "" : fila.getGrupo();
            rutasPorChute.computeIfAbsent(g, (k) -> new HashMap<>()).merge(fila.getChuteId(), fila.getRutas(), Long::sum);
            BigDecimal bultos = fila.getBultos() == null ? BigDecimal.ZERO : fila.getBultos();
            grupos.merge(
                g,
                new TramosGrupos.Grupo(g, fila.getRutas(), bultos),
                (a, b) -> new TramosGrupos.Grupo(g, a.rutas() + b.rutas(), a.bultos().add(b.bultos())));
          }
          List<String> orden =
              ordenGrupos(
                  nombre,
                  grupos.keySet(),
                  gruposGuardados(archivoId, clusterId),
                  pedido == null ? null : pedido.prioridad(),
                  false);
          List<TramosGrupos.Grupo> enOrden = orden.stream().map(grupos::get).toList();
          String aviso = null;
          List<Long> rango = null;
          if (rangoDelOtro(ctx, clusterId)) {
            // El otro balanceo ya definió el rango y los tramos del cluster: se muestran tal cual.
            List<Long> heredado = rangoGuardado(ctx, ctx.parametrosOtro().get(clusterId));
            Map<String, GrupoGuardado> porClave = new HashMap<>();
            gruposGuardados(archivoId, clusterId)
                .forEach((g) -> porClave.putIfAbsent(SimulacionExcel.clave(g.grupo()), g));
            List<TramoGrupoResponse> respuesta = new ArrayList<>();
            for (int i = 0; i < enOrden.size(); i++) {
              TramosGrupos.Grupo g = enOrden.get(i);
              GrupoGuardado guardado = porClave.get(SimulacionExcel.clave(g.nombre()));
              List<Long> chutes =
                  grupos.size() == 1
                      ? heredado
                      : guardado == null
                          ? null
                          : chutesEntre(ctx, guardado.chuteDesdeId(), guardado.chuteHastaId());
              respuesta.add(
                  new TramoGrupoResponse(
                      g.nombre(),
                      guardado == null ? i + 1 : guardado.prioridad(),
                      g.rutas(),
                      g.bultos(),
                      chutes == null ? 0 : chutes.size(),
                      chutes == null ? null : chutes.getFirst(),
                      chutes == null ? null : ctx.nombreChute(chutes.getFirst()),
                      chutes == null ? null : chutes.getLast(),
                      chutes == null ? null : ctx.nombreChute(chutes.getLast()),
                      0));
            }
            return new VistaBalanceoResponse(
                clusterId,
                nombre,
                heredado == null ? null : heredado.size(),
                0,
                null,
                respuesta,
                ctx.balanceo().otro().etiqueta());
          }
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
          List<TramosGrupos.Tramo> tramos;
          int sinUsar = 0;
          if (aviso == null && rango != null && grupos.size() > 1) {
            TramosGrupos.Reparto reparto = TramosGrupos.repartir(rango, enOrden);
            tramos = reparto.tramos();
            sinUsar = reparto.sinUsar();
          } else {
            List<Long> todo = aviso == null && grupos.size() == 1 ? rango : null;
            List<TramosGrupos.Tramo> lista = new ArrayList<>();
            for (int i = 0; i < enOrden.size(); i++) {
              TramosGrupos.Grupo g = enOrden.get(i);
              lista.add(new TramosGrupos.Tramo(g.nombre(), i + 1, g.rutas(), g.bultos(), todo));
            }
            tramos = lista;
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
              respuesta);
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

  /**
   * Orden de prioridad de los grupos del cluster. Con prioridad pedida, debe
   * nombrar cada grupo una sola vez (se compara sin distinguir mayúsculas ni
   * tildes). Sin pedirla: la guardada; si falta o ya no coincide con los grupos
   * de hoy (p. ej. entró una ruta de otro grupo), con {@code exigir} se pide
   * ordenarla de nuevo y, si no, se completa por nombre (vista previa).
   */
  private static List<String> ordenGrupos(
      String cluster,
      Collection<String> grupos,
      List<GrupoGuardado> guardados,
      List<String> pedida,
      boolean exigir) {
    Map<String, String> porClave = new LinkedHashMap<>();
    grupos.forEach((g) -> porClave.put(SimulacionExcel.clave(g), g));
    if (pedida != null && !pedida.isEmpty()) {
      List<String> orden = new ArrayList<>();
      Set<String> vistos = new HashSet<>();
      for (String nombre : pedida) {
        String clave = SimulacionExcel.clave(nombre);
        String grupo = porClave.get(clave);
        if (grupo == null || !vistos.add(clave)) {
          throw prioridadInvalida(cluster, grupos);
        }
        orden.add(grupo);
      }
      if (orden.size() != grupos.size()) {
        throw prioridadInvalida(cluster, grupos);
      }
      return orden;
    }
    List<String> orden = new ArrayList<>();
    for (GrupoGuardado guardado : guardados) {
      String grupo = porClave.get(SimulacionExcel.clave(guardado.grupo()));
      if (grupo != null && !orden.contains(grupo)) {
        orden.add(grupo);
      }
    }
    if (exigir && orden.size() != grupos.size()) {
      throw new SolicitudInvalidaException(
          guardados.isEmpty()
              ? "Ordena la prioridad de los grupos de " + cluster + "."
              : "Los grupos de " + cluster + " cambiaron: vuelve a ordenar su prioridad.");
    }
    grupos.stream()
        .filter((g) -> !orden.contains(g))
        .sorted(ORDEN_NATURAL)
        .forEach(orden::add);
    return orden;
  }

  private static SolicitudInvalidaException prioridadInvalida(String cluster, Collection<String> grupos) {
    return new SolicitudInvalidaException(
        "La prioridad de "
            + cluster
            + " debe nombrar cada uno de sus grupos una sola vez ("
            + grupos.stream().sorted(ORDEN_NATURAL).collect(Collectors.joining(", "))
            + "). Vuelve a abrir el balanceo si los grupos cambiaron.");
  }

  /** Rutas y bultos de cada grupo, en el orden dado. */
  private static List<TramosGrupos.Grupo> cargaDeGrupos(
      List<String> orden, Map<String, List<RutaProyeccion>> porGrupo) {
    return orden.stream()
        .map(
            (g) -> {
              List<RutaProyeccion> rutas = porGrupo.getOrDefault(g, List.of());
              BigDecimal bultos =
                  rutas.stream()
                      .map(RutaProyeccion::getTotalBultos)
                      .filter(Objects::nonNull)
                      .reduce(BigDecimal.ZERO, BigDecimal::add);
              return new TramosGrupos.Grupo(g, rutas.size(), bultos);
            })
        .toList();
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
    List<String> orden =
        porGrupo.keySet().stream()
            .sorted(
                Comparator.comparing(
                        (String g) -> {
                          GrupoGuardado guardado = porClave.get(SimulacionExcel.clave(g));
                          return guardado == null ? Integer.MAX_VALUE : guardado.prioridad();
                        })
                    .thenComparing(ORDEN_NATURAL))
            .toList();
    List<TramosGrupos.Tramo> tramos = new ArrayList<>();
    for (TramosGrupos.Grupo g : cargaDeGrupos(orden, porGrupo)) {
      GrupoGuardado guardado = porClave.get(SimulacionExcel.clave(g.nombre()));
      List<Long> chutes =
          porGrupo.size() == 1
              ? rango
              : guardado == null
                  ? null
                  : chutesEntre(ctx, guardado.chuteDesdeId(), guardado.chuteHastaId());
      tramos.add(
          new TramosGrupos.Tramo(
              g.nombre(), guardado == null ? tramos.size() + 1 : guardado.prioridad(), g.rutas(), g.bultos(), chutes));
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

    /** Chutes de la ola donde se nivela: con rango, solo los del rango; sin él, los que ya usa. */
    private List<Long> chutesDeOla(Long cluster) {
      Map<Long, BigDecimal> porChute = carga.getOrDefault(cluster, Map.of());
      return porChute.keySet().stream()
          .filter((chute) -> rango.isEmpty() || rango.contains(chute))
          .toList();
    }

    /**
     * Ancla las rutas más pesadas en los chutes con buff de la ola: a cada uno, en orden, la ruta
     * más pesada que aún se puede mover (se trae si está en otro chute) y queda fija. Así el chute
     * con buff termina con más carga que los demás. Devuelve los traslados hechos.
     */
    List<Traslado> anclar(Long cluster, Collection<Long> chutesBuff) {
      List<Traslado> hechos = new ArrayList<>();
      List<Long> ola = chutesDeOla(cluster);
      List<Long> destinos =
          (rango.isEmpty() ? ola.stream().sorted().toList() : rango).stream()
              .filter(chutesBuff::contains)
              .toList();
      for (Long destino : destinos) {
        Par mejor = null;
        BigDecimal pesoMejor = null;
        for (Map.Entry<Par, TreeMap<BigDecimal, ArrayDeque<RutaProyeccion>>> entrada : rutas.entrySet()) {
          if (!entrada.getKey().clusterId().equals(cluster) || entrada.getValue().isEmpty()) {
            continue;
          }
          BigDecimal peso = entrada.getValue().lastKey();
          if (pesoMejor == null
              || peso.compareTo(pesoMejor) > 0
              || (peso.compareTo(pesoMejor) == 0 && entrada.getKey().chuteId().equals(destino))) {
            mejor = entrada.getKey();
            pesoMejor = peso;
          }
        }
        if (mejor == null) {
          break;
        }
        RutaProyeccion ruta = rutas.get(mejor).lastEntry().getValue().peekFirst();
        if (!mejor.chuteId().equals(destino)) {
          trasladar(ruta, cluster, mejor.chuteId(), destino);
          hechos.add(new Traslado(ruta, mejor.chuteId(), destino));
        }
        fijar(ruta, cluster, destino);
      }
      return hechos;
    }

    /**
     * Un movimiento para emparejar la ola: del chute más cargado que tenga rutas
     * que se pueden mover al menos cargado, la ruta que deja ambos más cerca del
     * medio. Cada movimiento reduce la diferencia, así que el proceso termina.
     * Null si la ola ya está pareja (dentro de la tolerancia) o ninguna ruta la
     * mejora.
     */
    Traslado nivelar(Long cluster) {
      Map<Long, BigDecimal> porChute = carga.getOrDefault(cluster, Map.of());
      List<Long> ola = chutesDeOla(cluster);
      if (ola.size() < 2) {
        return null;
      }
      Long cargado = null;
      Long liviano = null;
      BigDecimal total = BigDecimal.ZERO;
      for (Long chute : ola) {
        BigDecimal valor = porChute.get(chute);
        total = total.add(valor);
        if (rutasEn(cluster, chute) > 0
            && (cargado == null || valor.compareTo(porChute.get(cargado)) > 0)) {
          cargado = chute;
        }
        if (liviano == null || valor.compareTo(porChute.get(liviano)) < 0) {
          liviano = chute;
        }
      }
      if (cargado == null || cargado.equals(liviano)) {
        return null;
      }
      BigDecimal diferencia = porChute.get(cargado).subtract(porChute.get(liviano));
      BigDecimal promedio =
          total.divide(BigDecimal.valueOf(ola.size()), ESCALA, RoundingMode.HALF_UP);
      if (diferencia.compareTo(promedio.multiply(TOLERANCIA_CARGA)) <= 0) {
        return null;
      }
      BigDecimal mitad = diferencia.divide(BigDecimal.valueOf(2), ESCALA, RoundingMode.HALF_UP);
      TreeMap<BigDecimal, ArrayDeque<RutaProyeccion>> candidatas =
          rutas.get(new Par(cluster, cargado));
      // Solo sirven rutas con 0 < bultos < diferencia; la mejor es la más cercana a la mitad.
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
      if (elegida == null) {
        return null;
      }
      RutaProyeccion mejor = elegida.getValue().peekFirst();
      trasladar(mejor, cluster, cargado, liviano);
      return new Traslado(mejor, cargado, liviano);
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
  private BalanceoResponse asignarOperarios(
      Long archivoId, Balanceo balanceo, Long clusterId, Integer pedidos, Long actor) {
    Contexto ctx = contexto(archivoId, balanceo, true);
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
    actualizarEstado(ctx);
    tocar(ctx.archivo(), actor);
    return new BalanceoResponse(
        clusterId,
        nombre,
        FaseBalanceo.OPERARIOS.name(),
        parametro.getEstado(),
        0,
        total,
        mensaje.toString(),
        aConfiguracion(ctx));
  }

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
   * Devuelve las rutas a su chute original, quita los operarios de todas las
   * olas y borra lanzamientos e historial: todo el registro vuelve a la
   * proyección y la simulación empieza de nuevo por el primer cluster.
   */
  private void deshacerHistorial(Contexto ctx, Long actor) {
    Long archivoId = ctx.archivo().getId();
    // El chute y el cluster originales quedaron congelados al cargar el Excel, así
    // que basta un UPDATE masivo (sin reconstruir el historial movimiento a movimiento).
    // Las diarias solo vuelven atrás donde el otro balanceo aún no trabajó el cluster: si
    // no, él ya cuenta con ellas y siguen fijas.
    List<Long> libres =
        ctx.parametrosCluster().keySet().stream()
            .filter((id) -> pendiente(ctx.parametrosOtro().get(id)))
            .toList();
    rutaRepo.restaurarRutas(
        archivoId, ctx.balanceo().name(), libres.isEmpty() ? List.of(-1L) : libres);
    borrarOlas(ctx, ctx.parametrosCluster().keySet());
    historialRepo.borrarPorArchivo(archivoId, ctx.balanceo().name());
    lanzamientoRepo.borrarPorArchivo(archivoId, ctx.balanceo().name());
    // Prioridad y tramos son del cluster: se borran solo donde el otro balanceo no los usa.
    lotes.borrarGrupos(archivoId, libres);
    // Todo vuelve a la proyección: también el rango de chutes y la cantidad de
    // operarios que cada ola había elegido (si no, los modales los propondrían).
    ctx.parametrosCluster()
        .values()
        .forEach(
            (p) -> {
              p.setEstado(ParametroCluster.ESTADO_PENDIENTE);
              p.setChuteDesdeId(null);
              p.setChuteHastaId(null);
              p.setOperariosOla(null);
            });
    actualizarEstado(ctx);
    tocar(ctx.archivo(), actor);
  }

  /**
   * Deshace el trabajo hecho mientras esos clusters estaban en curso: los
   * movimientos de sus lanzamientos y los cambios a mano (mover o cambiar de
   * cluster) con ese cluster de trabajo. Como el trabajo de cada cluster es un
   * tramo seguido del historial, deshacerlo devuelve cada ruta exactamente al
   * chute y cluster que tenía antes; ninguna ruta de un cluster cerrado se toca
   * (mientras un cluster está en curso, los anteriores están cerrados y no se
   * pueden cambiar). Los clusters quedan PENDIENTE y sin operarios de ola.
   */
  private void reiniciarClusters(Contexto ctx, Collection<Long> clusterIds) {
    if (clusterIds.isEmpty()) {
      return;
    }
    Long archivoId = ctx.archivo().getId();
    // Del último cambio al primero: el origen del más antiguo de cada ruta es
    // donde estaba antes de ese trabajo (para el chute y para el cluster).
    Map<Long, Long> chutes = new LinkedHashMap<>();
    Map<Long, Long> clusters = new LinkedHashMap<>();
    for (CambioParaDeshacer cambio :
        historialRepo.trabajoParaDeshacer(archivoId, ctx.balanceo().name(), clusterIds)) {
      // Una diaria con la que el otro balanceo ya cuenta se queda donde está.
      if (Balanceo.diaria(cambio.getFrecuencia())
          && cambio.getClusterId() != null
          && !pendiente(ctx.parametrosOtro().get(cambio.getClusterId()))) {
        continue;
      }
      if (HistorialBalanceo.ACCION_CAMBIAR_CLUSTER.equals(cambio.getTipoAccion())) {
        if (cambio.getClusterOrigenId() != null) {
          clusters.put(cambio.getRutaId(), cambio.getClusterOrigenId());
        }
      } else if (cambio.getChuteOrigenId() != null) {
        chutes.put(cambio.getRutaId(), cambio.getChuteOrigenId());
      }
    }
    historialRepo.borrarManualesDeTrabajo(archivoId, ctx.balanceo().name(), clusterIds);
    for (Long clusterId : clusterIds) {
      lanzamientoRepo.borrarPorCluster(archivoId, ctx.balanceo().name(), clusterId);
      ctx.parametrosCluster().get(clusterId).setEstado(ParametroCluster.ESTADO_PENDIENTE);
    }
    lotes.moverRutas(chutes);
    lotes.cambiarClusters(clusters);
    borrarOlas(ctx, clusterIds);
    // Sus grupos conservan la prioridad (el modal la propone) pero ya no tienen tramo,
    // salvo donde el otro balanceo los sigue usando (los tramos son del cluster).
    lotes.quitarTramos(
        archivoId,
        clusterIds.stream().filter((id) -> pendiente(ctx.parametrosOtro().get(id))).toList());
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
    operarioOlaRepo.deleteAll(borrar);
  }

  /**
   * PROYECCION si ningún cluster empezó; FINALIZADO con todos cerrados; si no,
   * EN_PROCESO.
   */
  private static void actualizarEstado(Contexto ctx) {
    // El registro termina cuando los dos balanceos cerraron todos sus clusters.
    List<ParametroCluster> clusters = new ArrayList<>(ctx.parametrosCluster().values());
    clusters.addAll(ctx.parametrosOtro().values());
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

  /**
   * Cambiar rutas de cluster exige que el cluster no esté cerrado (antes o
   * después de balancear; si ya tenía operarios, vuelve al paso 2).
   */
  private static void exigirAbierto(Contexto ctx, Long clusterId) {
    if (ParametroCluster.ESTADO_CERRADO.equals(parametroCluster(ctx, clusterId).getEstado())) {
      throw new ConflictoException(
          ctx.nombreCluster(clusterId) + " está cerrado: reábrelo para cambiar rutas de cluster.");
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

  private static List<Long> cerrados(Contexto ctx) {
    return orden(ctx).stream().filter((id) -> cerrado(ctx, id)).toList();
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

  /** La fila (de parámetros u operarios) es de ese balanceo; sin dato, de L-X-V. */
  private static boolean deBalanceo(String guardado, Balanceo balanceo) {
    return (guardado == null ? Balanceo.LXV.name() : guardado).equals(balanceo.name());
  }

  /** La ruta (o el agregado) de esa frecuencia aparece en el balanceo del contexto. */
  private static boolean visible(Contexto ctx, String frecuencia) {
    return ctx.balanceo().incluye(frecuencia);
  }

  private static boolean pendiente(ParametroCluster parametro) {
    return parametro == null || ParametroCluster.ESTADO_PENDIENTE.equals(parametro.getEstado());
  }

  /**
   * Las rutas diarias del cluster ya están fijas: algún balanceo automático (de este o del otro
   * balanceo) las acomodó y desde ahí ningún automático las vuelve a mover. A mano sí se mueven.
   * Se liberan al restablecer el cluster en los dos balanceos.
   */
  private static boolean diariasFijas(Contexto ctx, Long clusterId) {
    return !pendiente(ctx.parametrosCluster().get(clusterId))
        || !pendiente(ctx.parametrosOtro().get(clusterId));
  }

  /**
   * El otro balanceo ya trabajó el cluster: su rango y los tramos de sus grupos valen también para
   * éste (así una ruta diaria fija queda siempre en el tramo de su grupo).
   */
  private static boolean rangoDelOtro(Contexto ctx, Long clusterId) {
    return !pendiente(ctx.parametrosOtro().get(clusterId));
  }

  /** Algún cluster ya se balanceó en alguno de los dos balanceos: el buff ya no se puede cambiar. */
  private static boolean buffBloqueado(Contexto ctx) {
    return ctx.parametrosCluster().values().stream().anyMatch((p) -> !pendiente(p))
        || ctx.parametrosOtro().values().stream().anyMatch((p) -> !pendiente(p));
  }

  /**
   * Una ruta diaria cambió a mano (de chute o de cluster): también cambia la carga del otro
   * balanceo. Si allí ese cluster está cerrado no se permite; si ya tenía operarios, vuelve al
   * paso 2.
   */
  private void exigirOtroAbierto(Contexto ctx, Long clusterId) {
    ParametroCluster otro = ctx.parametrosOtro().get(clusterId);
    if (otro != null && ParametroCluster.ESTADO_CERRADO.equals(otro.getEstado())) {
      throw new ConflictoException(
          ctx.nombreCluster(clusterId)
              + " está cerrado en el balanceo "
              + ctx.balanceo().otro().etiqueta()
              + " y la ruta es diaria (también cambia allí): reábrelo en "
              + ctx.balanceo().otro().etiqueta()
              + " para cambiarla.");
    }
  }

  /** Igual que {@link #invalidarOperarios} pero en las olas del otro balanceo. */
  private void invalidarOperariosOtro(Contexto ctx, Collection<Long> clusterIds) {
    List<Long> listos =
        clusterIds.stream()
            .filter(
                (id) -> {
                  ParametroCluster parametro = ctx.parametrosOtro().get(id);
                  return parametro != null
                      && ParametroCluster.ESTADO_LISTO.equals(parametro.getEstado());
                })
            .toList();
    if (listos.isEmpty()) {
      return;
    }
    listos.forEach((id) -> ctx.parametrosOtro().get(id).setEstado(ParametroCluster.ESTADO_RUTAS));
    List<OperarioOla> borrar = new ArrayList<>();
    ctx.operariosOlaOtro()
        .entrySet()
        .removeIf(
            (entrada) -> {
              if (listos.contains(entrada.getKey().clusterId())) {
                borrar.add(entrada.getValue());
                return true;
              }
              return false;
            });
    operarioOlaRepo.deleteAll(borrar);
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
  private static List<CargaRutas> sumarCarga(Contexto ctx, List<CargaRutas> filas) {
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
  private record Contexto(
      ArchivoExcel archivo,
      /** Balanceo en que se trabaja (L-X-V o M-J-S). */
      Balanceo balanceo,
      SimulacionConfig config,
      /** Parámetros de cada cluster en este balanceo. */
      Map<Long, ParametroCluster> parametrosCluster,
      /** Parámetros de cada cluster en el otro balanceo (para las rutas diarias). */
      Map<Long, ParametroCluster> parametrosOtro,
      Map<Long, ParametroChute> parametrosChute,
      Map<Par, OperarioOla> operariosOla,
      /** Operarios de las olas del otro balanceo (se invalidan si cambia una ruta diaria). */
      Map<Par, OperarioOla> operariosOlaOtro,
      Map<Long, String> clusters,
      Map<Long, String> chutes,
      /** Chutes del plano agregados en memoria (sin fila en parametros_chutes). */
      Set<Long> chutesAgregados) {

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

  private Contexto contexto(Long archivoId, Balanceo balanceo, boolean bloquear) {
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
    // Las filas de los dos balanceos: las de éste y las del otro, por cluster.
    Map<Long, ParametroCluster> parametrosCluster = new LinkedHashMap<>();
    Map<Long, ParametroCluster> parametrosOtro = new LinkedHashMap<>();
    for (ParametroCluster p : parametroClusterRepo.findByIdArchivoId(archivoId)) {
      (deBalanceo(p.getId().getBalanceo(), balanceo) ? parametrosCluster : parametrosOtro)
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
    Map<Par, OperarioOla> operariosOla = new HashMap<>();
    Map<Par, OperarioOla> operariosOlaOtro = new HashMap<>();
    operarioOlaRepo
        .findByIdArchivoId(archivoId)
        .forEach(
            (ola) ->
                (deBalanceo(ola.getId().getBalanceo(), balanceo) ? operariosOla : operariosOlaOtro)
                    .put(new Par(ola.getId().getClusterId(), ola.getId().getChuteId()), ola));
    Map<Long, String> clusters =
        clusterRepo.findAllById(parametrosCluster.keySet()).stream()
            .collect(Collectors.toMap(Cluster::getId, Cluster::getNombre));
    return new Contexto(
        archivo,
        balanceo,
        config,
        parametrosCluster,
        parametrosOtro,
        parametrosChute,
        operariosOla,
        operariosOlaOtro,
        clusters,
        chutes,
        Set.copyOf(agregados));
  }

  /**
   * Grupos de cada cluster con su carga de hoy, en orden de prioridad (la
   * guardada; los que no tienen, después y por nombre) y el tramo de su último
   * balanceo.
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
      rutas
          .computeIfAbsent(fila.getClusterId(), (id) -> new HashMap<>())
          .computeIfAbsent(g, (k) -> new long[1])[0] += fila.getRutas();
      bultos
          .computeIfAbsent(fila.getClusterId(), (id) -> new HashMap<>())
          .merge(g, fila.getBultos() == null ? BigDecimal.ZERO : fila.getBultos(), BigDecimal::add);
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
          List<GrupoClusterResponse> lista =
              porGrupo.keySet().stream()
                  .map(
                      (g) -> {
                        GrupoGuardado guardado = suyos.get(SimulacionExcel.clave(g));
                        Long desde = guardado == null ? null : guardado.chuteDesdeId();
                        Long hasta = guardado == null ? null : guardado.chuteHastaId();
                        return new GrupoClusterResponse(
                            g,
                            guardado == null ? null : guardado.prioridad(),
                            porGrupo.get(g)[0],
                            bultos.get(clusterId).get(g),
                            desde,
                            ctx.nombreChute(desde),
                            hasta,
                            ctx.nombreChute(hasta));
                      })
                  .sorted(
                      Comparator.comparing(
                              GrupoClusterResponse::prioridad,
                              Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                          .thenComparing(GrupoClusterResponse::nombre, ORDEN_NATURAL))
                  .toList();
          resultado.put(clusterId, lista);
        });
    return resultado;
  }

  private ConfiguracionSimulacionResponse aConfiguracion(Contexto ctx) {
    List<CargaRutas> carga = cargaVisible(ctx);
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
                        ctx.parametrosOtro().get(id) == null
                            ? ParametroCluster.ESTADO_PENDIENTE
                            : ctx.parametrosOtro().get(id).getEstado(),
                        diariasFijas(ctx, id),
                        rangoDelOtro(ctx, id)))
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
