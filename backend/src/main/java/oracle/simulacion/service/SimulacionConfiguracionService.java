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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
import oracle.simulacion.model.Chute;
import oracle.simulacion.model.Cluster;
import oracle.simulacion.model.HistorialBalanceo;
import oracle.simulacion.model.LanzamientoBalanceo;
import oracle.simulacion.model.OperarioOla;
import oracle.simulacion.model.OperarioOlaId;
import oracle.simulacion.model.ParametroChute;
import oracle.simulacion.model.ParametroCluster;
import oracle.simulacion.model.RutaProyeccion;
import oracle.simulacion.model.SimulacionConfig;
import oracle.simulacion.model.TipoRuta;
import oracle.simulacion.repository.ArchivoExcelRepository;
import oracle.simulacion.repository.CambioClusterRutas;
import oracle.simulacion.repository.CargaRutas;
import oracle.simulacion.repository.ChuteRepository;
import oracle.simulacion.repository.ClusterRepository;
import oracle.simulacion.repository.HistorialBalanceoRepository;
import oracle.simulacion.repository.LanzamientoBalanceoRepository;
import oracle.simulacion.repository.MovimientoRutas;
import oracle.simulacion.repository.OperarioOlaRepository;
import oracle.simulacion.repository.CambioParaDeshacer;
import oracle.simulacion.repository.ParametroChuteRepository;
import oracle.simulacion.repository.ParametroClusterRepository;
import oracle.simulacion.repository.ResumenRutasFiltro;
import oracle.simulacion.repository.RutaProyeccionRepository;
import oracle.simulacion.repository.SimulacionConfigRepository;
import oracle.simulacion.repository.SimulacionLotesRepository;
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
import oracle.simulacion.response.PaginaRutasResponse;
import oracle.simulacion.response.ChuteComparativoResponse;
import oracle.simulacion.response.ChuteMezcladoResponse;
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
  /**
   * Clusters que no pueden compartir chute: CL1 y CL2 (número del nombre
   * {@link #CLUSTER_NUMERADO}). El resto de combinaciones sigue permitido.
   */
  private static final Set<Integer> CLUSTERS_INCOMPATIBLES = Set.of(1, 2);
  /** «CL1», «CL 1», «C1», «cl01» → 1. Otros prefijos (CP1, CB2…) no cuentan. */
  private static final Pattern CLUSTER_NUMERADO = Pattern.compile("CL?0*(\\d+)");
  private static final Pattern ESPACIOS = Pattern.compile("\\s+");
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
      CacheRespuestas cache) {
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
    return leer(
        "obtener id=" + archivoId,
        () -> cacheado(archivoId, "configuracion", this::aConfiguracion),
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
          Contexto ctx = contexto(archivoId, false);
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
                  soloCambios);
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
        clusterEditable(ctx, ruta.getClusterId()),
        ruta.getCodigoRuta(),
        ruta.getTipo() == null ? null : ruta.getTipo().name(),
        ruta.getTotalBultos(),
        ruta.getTotalUnidades(),
        ruta.getTotalLineas(),
        ruta.getConteoCod());
  }

  public ApiResponse<ConfiguracionSimulacionResponse> guardar(
      Long archivoId, ConfiguracionActualizarRequest req) {
    Long actor = ActorActual.id();
    return escribir(
        "guardar id=" + archivoId,
        () -> {
          Contexto ctx = contexto(archivoId, true);
          SimulacionConfig config = ctx.config();
          if (config.getCreadoEn() == null) {
            Auditoria.alCrear(config, actor);
          } else {
            Auditoria.alActualizar(config, actor);
          }
          // El equipo (velocidad y operarios) vale para todas las olas: si
          // cambia, ninguna asignación sigue al día. Con clusters cerrados no
          // se puede cambiar sin reabrirlos.
          boolean cambiaEquipo =
              config.getVelocidadBase() == null
                  || config.getVelocidadBase().compareTo(req.velocidadBase()) != 0
                  // Se compara con lo que el cliente recibió: 0 y nulo son "sin tope", y un
                  // equipo nunca definido es la suma que se le mostró (operariosMaximos).
                  || !Objects.equals(sinTope(config.getOperariosPorChute()), sinTope(req.operariosPorChute()))
                  || (req.operariosMaximos() != null
                      && operariosMaximos(ctx, operariosBase(ctx)) != req.operariosMaximos());
          String cerrados = nombres(ctx, cerrados(ctx));
          if (cambiaEquipo && !cerrados.isEmpty()) {
            throw new ConflictoException(
                "El equipo ya trabajó en "
                    + cerrados
                    + ": reabre el primero de esos clusters para cambiarlo.");
          }
          config.setVelocidadBase(req.velocidadBase());
          // Nulo = reparto parejo: el usuario puede volver a dejarlo vacío.
          config.setOperariosPorChute(req.operariosPorChute());
          if (req.operariosMaximos() != null) {
            config.setOperariosMaximos(req.operariosMaximos());
          }
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
            }
          }
          validarOperarios(ctx);
          // Un cluster LISTO cuyo límite (o el equipo) cambió vuelve a «Asignar
          // operarios»; sus rutas del paso 1 siguen valiendo.
          invalidarOperarios(ctx, cambiaEquipo ? ctx.parametrosCluster().keySet() : limiteCambiado);
          tocar(ctx.archivo(), actor);
          return aConfiguracion(ctx);
        },
        "Parámetros guardados.");
  }

  /**
   * La base de cada chute no puede pasar del máximo por chute ni desbordar el
   * pool de ninguna ola. Como los clusters se ejecutan en secuencia, el máximo
   * global se valida por cluster, no por la suma de todos los chutes. Lo que
   * asigna el paso 2 ya respeta ambos topes.
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
    Integer maximo = ctx.config().getOperariosMaximos();
    if (maximo == null || maximo <= 0) {
      return;
    }
    Map<Long, Map<Long, Integer>> operariosPorOla = new LinkedHashMap<>();
    for (CargaRutas fila : rutaRepo.cargaPorArchivo(ctx.archivo().getId())) {
      if (fila.getRutas() <= 0 || cerrado(ctx, fila.getClusterId())) {
        continue;
      }
      ParametroChute parametro = ctx.parametrosChute().get(fila.getChuteId());
      int operarios = parametro == null ? 0 : base(parametro);
      operariosPorOla
          .computeIfAbsent(fila.getClusterId(), (id) -> new LinkedHashMap<>())
          .putIfAbsent(fila.getChuteId(), operarios);
    }
    for (Map.Entry<Long, Map<Long, Integer>> ola : operariosPorOla.entrySet()) {
      int total = ola.getValue().values().stream().mapToInt(Integer::intValue).sum();
      if (total > maximo) {
        throw new ConflictoException(
            "El cluster "
                + ctx.nombreCluster(ola.getKey())
                + " necesita "
                + total
                + " operario(s) en su ola y el máximo disponible es "
                + maximo
                + ". Ajusta los chutes o sube el máximo.");
      }
    }
  }

  public ApiResponse<ConfiguracionSimulacionResponse> mover(
      Long archivoId, Long rutaId, MoverRutaRequest req) {
    Long actor = ActorActual.id();
    return escribir(
        "mover archivo=" + archivoId + " ruta=" + rutaId,
        () -> {
          Contexto ctx = contexto(archivoId, true);
          RutaProyeccion ruta =
              rutaRepo
                  .findByIdAndArchivoId(rutaId, archivoId)
                  .orElseThrow(
                      () -> new RecursoNoEncontradoException("No existe la ruta " + rutaId + "."));
          if (!ctx.parametrosChute().containsKey(req.chuteId())) {
            throw new SolicitudInvalidaException(
                "El chute " + req.chuteId() + " no pertenece al registro.");
          }
          if (cerrado(ctx, ruta.getClusterId())) {
            throw new ConflictoException(
                ctx.nombreCluster(ruta.getClusterId())
                    + " está cerrado: reábrelo para mover sus rutas.");
          }
          if (ruta.getChuteId().equals(req.chuteId())) {
            throw new ConflictoException(
                "La ruta ya pertenece al chute " + ctx.nombreChute(req.chuteId()) + ".");
          }
          // CL1 y CL2 no pueden compartir chute: un movimiento manual tampoco
          // puede crear esa mezcla.
          Long conflicto =
              clusterEnConflicto(
                  rutaRepo.clustersEnChute(archivoId, req.chuteId()),
                  ruta.getClusterId(),
                  numerosCluster(ctx.clusters()));
          if (conflicto != null) {
            throw new ConflictoException(
                "El chute "
                    + ctx.nombreChute(req.chuteId())
                    + " ya tiene rutas del cluster "
                    + ctx.nombreCluster(conflicto)
                    + " y no pueden mezclarse con las del cluster "
                    + ctx.nombreCluster(ruta.getClusterId())
                    + ".");
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
          // Trabajo del cluster en curso: restablecerlo (o reabrir uno anterior) lo deshace.
          manual.setClusterTrabajoId(enCurso(ctx));
          historialRepo.save(manual);
          // El chute nuevo puede sumar operarios base a la ola de su cluster.
          validarOperarios(ctx);
          // Mover una ruta cambia la carga sobre la que se asignaron los
          // operarios de su ola: solo ese cluster vuelve al paso 2.
          invalidarOperarios(ctx, List.of(ruta.getClusterId()));
          tocar(ctx.archivo(), actor);
          return aConfiguracion(ctx);
        },
        "Ruta movida.");
  }

  /**
   * Pasa una ruta a otro cluster, sobrescribiendo el suyo (el original queda
   * guardado). Vale antes y después de balancear, mientras ninguno de los dos
   * esté cerrado; los que ya tenían operarios vuelven al paso 2 porque su carga
   * cambió. La ruta se queda en su chute.
   */
  public ApiResponse<ConfiguracionSimulacionResponse> cambiarCluster(
      Long archivoId, Long rutaId, CambiarClusterRequest req) {
    Long actor = ActorActual.id();
    return escribir(
        "cambiar cluster archivo=" + archivoId + " ruta=" + rutaId,
        () -> {
          Contexto ctx = contexto(archivoId, true);
          RutaProyeccion ruta =
              rutaRepo
                  .findByIdAndArchivoId(rutaId, archivoId)
                  .orElseThrow(
                      () -> new RecursoNoEncontradoException("No existe la ruta " + rutaId + "."));
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
          // paso, sin estado intermedio) o el suyo. Con su nuevo cluster tampoco
          // puede juntar CL1 y CL2 ahí; solo se consulta si el destino tiene
          // incompatibles (CL1 o CL2).
          Long chuteAntes = ruta.getChuteId();
          Long chuteFinal = req.chuteId() == null ? chuteAntes : req.chuteId();
          if (!ctx.parametrosChute().containsKey(chuteFinal)) {
            throw new SolicitudInvalidaException(
                "El chute " + chuteFinal + " no pertenece al registro.");
          }
          Set<Long> incompatibles = incompatiblesDe(destino, numerosCluster(ctx.clusters()));
          Long conflicto =
              incompatibles.isEmpty()
                  ? null
                  : rutaRepo
                      .clusterEnChute(archivoId, chuteFinal, incompatibles, rutaId)
                      .orElse(null);
          if (conflicto != null) {
            throw new ConflictoException(
                "El chute "
                    + ctx.nombreChute(chuteFinal)
                    + " tiene rutas de "
                    + ctx.nombreCluster(conflicto)
                    + ", que no pueden mezclarse con "
                    + ctx.nombreCluster(destino)
                    + (chuteFinal.equals(chuteAntes)
                        ? ": llévala también a otro chute."
                        : "."));
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
            movimiento.setClusterTrabajoId(enCurso(ctx));
            historialRepo.save(movimiento);
          }
          validarOperarios(ctx);
          // Cambia la carga de los dos clusters: si ya tenían operarios, vuelven al paso 2.
          invalidarOperarios(ctx, List.of(origen, destino));
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
    Long actor = ActorActual.id();
    BalanceoResponse datos =
        Trazas.medir(
            log,
            "SimulacionConfiguracionService.balancear id="
                + archivoId
                + " cluster="
                + clusterId
                + " fase="
                + fase,
            () ->
                transacciones.ejecutar(
                    () ->
                        fase == FaseBalanceo.RUTAS
                            ? balancearRutas(archivoId, clusterId, actor)
                            : asignarOperarios(archivoId, clusterId, actor)));
    return ApiResponse.ok(datos.mensaje(), datos);
  }

  /** Todo el registro vuelve a la proyección: rutas, operarios, lanzamientos e historial. */
  public ApiResponse<Void> restablecer(Long archivoId) {
    Long actor = ActorActual.id();
    Trazas.medir(
        log,
        "SimulacionConfiguracionService.restablecer id=" + archivoId,
        () ->
            transacciones.<Void>ejecutar(
                () -> {
                  deshacerHistorial(contexto(archivoId, true), actor);
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
    Long actor = ActorActual.id();
    return escribir(
        "restablecer id=" + archivoId + " cluster=" + clusterId,
        () -> {
          Contexto ctx = contexto(archivoId, true);
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
   * Cierra el cluster en curso: con sus operarios asignados y sin compartir
   * chute con su incompatible. El equipo pasa al siguiente cluster con esta
   * dotación; al cerrar el último, la simulación termina.
   */
  public ApiResponse<ConfiguracionSimulacionResponse> cerrar(
      Long archivoId, Long clusterId) {
    Long actor = ActorActual.id();
    return escribir(
        "cerrar id=" + archivoId + " cluster=" + clusterId,
        () -> {
          Contexto ctx = contexto(archivoId, true);
          ParametroCluster parametro = exigirEnCurso(ctx, clusterId);
          String nombre = ctx.nombreCluster(clusterId);
          if (!ParametroCluster.ESTADO_LISTO.equals(parametro.getEstado())) {
            throw new ConflictoException(
                "Balancea las rutas y asigna los operarios de " + nombre + " antes de cerrarlo.");
          }
          String juntos =
              mezclas(
                      rutaRepo.cargaPorArchivo(archivoId).stream()
                          .filter((fila) -> fila.getRutas() > 0)
                          .map((fila) -> new Par(fila.getClusterId(), fila.getChuteId()))
                          .toList(),
                      ctx)
                  .stream()
                  .filter((mezcla) -> mezcla.clusters().contains(nombre))
                  .map(ChuteMezcladoResponse::chute)
                  .collect(Collectors.joining(", "));
          if (!juntos.isEmpty()) {
            throw new ConflictoException(
                nombre
                    + " sigue compartiendo "
                    + juntos
                    + " con un cluster incompatible: mueve esas rutas antes de cerrarlo.");
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
    Long actor = ActorActual.id();
    return escribir(
        "reabrir id=" + archivoId + " cluster=" + clusterId,
        () -> {
          Contexto ctx = contexto(archivoId, true);
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
    return leer(
        "historial id=" + archivoId,
        () -> {
          Contexto ctx = contexto(archivoId, false);
          List<LanzamientoSimulacionResponse> lanzamientos =
              lanzamientoRepo.findTop200ByArchivoIdOrderByIdDesc(archivoId).stream()
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
                              lanzamiento.getSeparaciones(),
                              lanzamiento.getChutesOperarios()))
                  .toList();
          return new HistorialRegistroResponse(
              lanzamientos, historialRepo.countByArchivoIdAndLanzamientoIdIsNull(archivoId));
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
                  ? historialRepo.findByArchivoIdAndLanzamientoIdIsNull(archivoId, pageable)
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
    return leer(
        "dashboard id=" + archivoId + " cluster=" + clusterId,
        () ->
            cacheado(
                archivoId,
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
   * Paso 1 del cluster en curso. Si comparte chute con su incompatible (CL1
   * con CL2), los separa moviendo las rutas del que tenga menos allí (sin tocar
   * clusters cerrados); luego reparte sus rutas entre los chutes que ya lo
   * atienden hasta que su carga (bultos) quede pareja. No toca operarios.
   */
  private BalanceoResponse balancearRutas(Long archivoId, Long clusterId, Long actor) {
    Contexto ctx = contexto(archivoId, true);
    ParametroCluster parametro = exigirEnCurso(ctx, clusterId);
    String nombre = ctx.nombreCluster(clusterId);
    // Volver a balancear un cluster ya balanceado (p. ej. tras pasarle rutas de
    // otro cluster) reparte desde como está hoy, sin perder los cambios a mano;
    // si ya tenía operarios, su carga cambia y vuelve al paso 2.
    invalidarOperarios(ctx, List.of(clusterId));
    Reparto reparto =
        new Reparto(
            rutaRepo.findByArchivoIdOrderByIdAsc(archivoId),
            ctx.parametrosChute().keySet(),
            numerosCluster(ctx.clusters()));
    Integer dispersionAntes = reparto.dispersion(clusterId);
    List<Traslado> separaciones = new ArrayList<>();
    Traslado traslado;
    // Se pueden mover rutas de este cluster y de los que aún no empezaron.
    Set<Long> movibles = new HashSet<>();
    ctx.parametrosCluster()
        .forEach(
            (id, p) -> {
              if (id.equals(clusterId) || ParametroCluster.ESTADO_PENDIENTE.equals(p.getEstado())) {
                movibles.add(id);
              }
            });
    while ((traslado = reparto.separar(clusterId, movibles)) != null) {
      separaciones.add(traslado);
    }
    // Cada movimiento baja la suma de cuadrados de la carga, así que el
    // proceso termina solo; el tope es una red de seguridad.
    int tope = Math.max(MAX_ITERACIONES, 4 * reparto.rutasDe(clusterId));
    List<Traslado> nivelaciones = new ArrayList<>();
    while (nivelaciones.size() < tope && (traslado = reparto.nivelar(clusterId)) != null) {
      nivelaciones.add(traslado);
    }

    int movidas =
        (int)
            Stream.concat(separaciones.stream(), nivelaciones.stream())
                .map((t) -> t.ruta().getId())
                .distinct()
                .count();
    StringBuilder mensaje =
        new StringBuilder(nombre).append(": ").append(movidas).append(" ruta(s) movida(s)");
    if (!separaciones.isEmpty()) {
      mensaje.append(" (").append(separaciones.size()).append(" para separar CL1 y CL2)");
    }
    mensaje.append(".");
    Integer dispersionDespues = reparto.dispersion(clusterId);
    if (dispersionAntes != null) {
      mensaje
          .append(" Diferencia de carga entre sus chutes: ")
          .append(dispersionAntes)
          .append(" % → ")
          .append(dispersionDespues == null ? 0 : dispersionDespues)
          .append(" %.");
    }
    if (nivelaciones.size() >= tope) {
      mensaje.append(" Se alcanzó el máximo de movimientos por ejecución.");
    }
    if (!reparto.mezclasSinSalida().isEmpty()) {
      mensaje
          .append(" No se pudo separar CL1 y CL2 en ")
          .append(
              reparto.mezclasSinSalida().stream()
                  .map(ctx::nombreChute)
                  .sorted(ORDEN_NATURAL)
                  .collect(Collectors.joining(", ")))
          .append(": no hay otro chute libre de esos clusters.");
    }

    // Las rutas se leyeron en solo lectura: los chutes nuevos se guardan en lote.
    lotes.moverRutas(reparto.chutesNuevos());
    Instant momento = Instant.now();
    LanzamientoBalanceo lanzamiento =
        lanzar(archivoId, clusterId, FaseBalanceo.RUTAS, mensaje.toString(), actor, momento);
    lanzamiento.setRutas(separaciones.size() + nivelaciones.size());
    lanzamiento.setSeparaciones(separaciones.size());
    Long lanzamientoId = lanzamiento.getId();
    List<HistorialBalanceo> logs = new ArrayList<>();
    separaciones.forEach(
        (t) ->
            logs.add(
                movimiento(
                    archivoId,
                    lanzamientoId,
                    t,
                    HistorialBalanceo.MOTIVO_SEPARACION,
                    actor,
                    momento)));
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
   * Reparto de rutas por carga de un cluster. Cada cluster es una ola que
   * corre sola, así que lo que se iguala es la carga de ese cluster en cada
   * chute que lo atiende (no la carga total del chute). Conoce todas las rutas
   * para saber qué clusters hay en cada chute, pero solo mueve las del cluster
   * lanzado (y, al separar CL1/CL2, las de su incompatible si aún no empezó).
   * Las rutas de cada (cluster, chute) van ordenadas por bultos: elegir la
   * ruta a mover cuesta log(n), no recorrer todas.
   */
  private static final class Reparto {
    /** La ola se da por pareja si la diferencia entre chutes es ≤ 10 % de su promedio. */
    private static final BigDecimal TOLERANCIA_CARGA = new BigDecimal("0.10");

    /** Bultos de cada cluster por chute. */
    private final Map<Long, Map<Long, BigDecimal>> carga = new HashMap<>();
    /** Rutas de cada (cluster, chute) agrupadas por bultos. */
    private final Map<Par, TreeMap<BigDecimal, ArrayDeque<RutaProyeccion>>> rutas = new HashMap<>();
    private final Map<Par, Integer> cuenta = new HashMap<>();
    private final Map<Long, Integer> rutasPorCluster = new HashMap<>();
    /** Clusters con rutas en cada chute, para validar la mezcla CL1/CL2. */
    private final Map<Long, Set<Long>> clustersPorChute = new HashMap<>();
    private final Map<Long, BigDecimal> bultosChute = new HashMap<>();
    private final Set<Long> chutes = new HashSet<>();
    private final Map<Long, Integer> numerosCluster;
    private final Set<Long> mezclasSinSalida = new TreeSet<>();
    /** Chute final de cada ruta movida, para guardarlo en lote. */
    private final Map<Long, Long> chutesNuevos = new LinkedHashMap<>();

    /** Los chutes del registro, incluso vacíos: son destino válido al separar CL1/CL2. */
    Reparto(
        List<RutaProyeccion> todas, Set<Long> chutesRegistro, Map<Long, Integer> numerosCluster) {
      this.numerosCluster = numerosCluster;
      chutes.addAll(chutesRegistro);
      for (RutaProyeccion ruta : todas) {
        poner(ruta, ruta.getClusterId(), ruta.getChuteId());
        rutasPorCluster.merge(ruta.getClusterId(), 1, Integer::sum);
        chutes.add(ruta.getChuteId());
      }
    }

    Set<Long> mezclasSinSalida() {
      return mezclasSinSalida;
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

    /**
     * Separa el cluster de su incompatible (CL1 con CL2) en el primer chute
     * donde comparten: saca la ruta más pesada del que tenga menos rutas allí
     * (si no tiene adónde ir, prueba con el otro). Solo se mueven rutas de los
     * clusters movibles: el que se trabaja y los que aún no empezaron, nunca uno
     * cerrado. Prefiere otro chute de su ola; si no hay, el chute compatible
     * menos cargado. Null cuando no quedan mezclas que separar.
     */
    Traslado separar(Long cluster, Set<Long> movibles) {
      List<Long> ordenados = new ArrayList<>(carga.getOrDefault(cluster, Map.of()).keySet());
      ordenados.sort(Comparator.naturalOrder());
      for (Long chute : ordenados) {
        if (mezclasSinSalida.contains(chute) || admite(chute, cluster)) {
          continue;
        }
        List<Long> salientes =
            clustersPorChute.getOrDefault(chute, Set.of()).stream()
                .filter(movibles::contains)
                .filter(
                    (otro) ->
                        otro.equals(cluster)
                            || clusterEnConflicto(List.of(otro), cluster, numerosCluster) != null)
                .sorted(
                    Comparator.comparingInt((Long otro) -> rutasEn(otro, chute))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        for (Long saliente : salientes) {
          Long destino = destinoSeparacion(saliente, chute);
          if (destino != null) {
            RutaProyeccion ruta =
                rutas.get(new Par(saliente, chute)).lastEntry().getValue().peekFirst();
            trasladar(ruta, saliente, chute, destino);
            return new Traslado(ruta, chute, destino);
          }
        }
        mezclasSinSalida.add(chute);
      }
      return null;
    }

    private Long destinoSeparacion(Long cluster, Long origen) {
      Comparator<Long> porCarga =
          Comparator.comparing(
              (Long chute) -> carga.get(cluster).getOrDefault(chute, BigDecimal.ZERO));
      Long mismaOla =
          carga.get(cluster).keySet().stream()
              .filter((chute) -> !chute.equals(origen) && admite(chute, cluster))
              .min(porCarga)
              .orElse(null);
      if (mismaOla != null) {
        return mismaOla;
      }
      return chutes.stream()
          .filter((chute) -> !chute.equals(origen) && admite(chute, cluster))
          .min(Comparator.comparing((Long chute) -> bultosChute.getOrDefault(chute, BigDecimal.ZERO)))
          .orElse(null);
    }

    /**
     * Un movimiento para emparejar la ola: del chute más cargado al menos
     * cargado, la ruta que deja ambos más cerca del medio. Cada movimiento
     * reduce la diferencia, así que el proceso termina. Null si la ola ya está
     * pareja (dentro de la tolerancia) o ninguna ruta la mejora.
     */
    Traslado nivelar(Long cluster) {
      Map<Long, BigDecimal> porChute = carga.getOrDefault(cluster, Map.of());
      if (porChute.size() < 2) {
        return null;
      }
      Long cargado = null;
      Long liviano = null;
      BigDecimal total = BigDecimal.ZERO;
      for (Map.Entry<Long, BigDecimal> entrada : porChute.entrySet()) {
        total = total.add(entrada.getValue());
        if (cargado == null || entrada.getValue().compareTo(porChute.get(cargado)) > 0) {
          cargado = entrada.getKey();
        }
        if (liviano == null || entrada.getValue().compareTo(porChute.get(liviano)) < 0) {
          liviano = entrada.getKey();
        }
      }
      BigDecimal diferencia = porChute.get(cargado).subtract(porChute.get(liviano));
      BigDecimal promedio =
          total.divide(BigDecimal.valueOf(porChute.size()), ESCALA, RoundingMode.HALF_UP);
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

    private int rutasEn(Long cluster, Long chute) {
      return cuenta.getOrDefault(new Par(cluster, chute), 0);
    }

    /** CL1 y CL2 no pueden quedar juntos: el chute admite al cluster si no crea esa mezcla. */
    private boolean admite(Long chute, Long cluster) {
      return !mezclaRestringida(
          clustersPorChute.getOrDefault(chute, Set.of()), cluster, numerosCluster);
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

    private void quitar(RutaProyeccion ruta, Long cluster, Long chute) {
      Par par = new Par(cluster, chute);
      TreeMap<BigDecimal, ArrayDeque<RutaProyeccion>> porBultos = rutas.get(par);
      ArrayDeque<RutaProyeccion> iguales = porBultos.get(ruta.getTotalBultos());
      iguales.remove(ruta);
      if (iguales.isEmpty()) {
        porBultos.remove(ruta.getTotalBultos());
      }
      sumar(cluster, chute, ruta.getTotalBultos().negate());
      if (cuenta.merge(par, -1, Integer::sum) == 0) {
        cuenta.remove(par);
        rutas.remove(par);
        carga.get(cluster).remove(chute);
        clustersPorChute.get(chute).remove(cluster);
      }
    }

    private void sumar(Long cluster, Long chute, BigDecimal bultos) {
      carga.computeIfAbsent(cluster, (id) -> new HashMap<>()).merge(chute, bultos, BigDecimal::add);
      bultosChute.merge(chute, bultos, BigDecimal::add);
      clustersPorChute.computeIfAbsent(chute, (id) -> new HashSet<>()).add(cluster);
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
   * Paso 2 del cluster en curso. Todo el equipo trabaja y se reparte para que
   * la ola cierre lo antes posible; el límite de horas solo dice si cumple.
   * Entre repartos con el mismo cierre, se queda el que deja a más gente en el
   * chute donde terminó la ola anterior. Solo sobra gente si cada chute con
   * carga llega al máximo por chute. Puede repetirse para recalcular.
   */
  private BalanceoResponse asignarOperarios(Long archivoId, Long clusterId, Long actor) {
    Contexto ctx = contexto(archivoId, true);
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
    List<CargaRutas> filas = rutaRepo.cargaPorArchivo(archivoId);
    Map<Long, BigDecimal> carga = new HashMap<>();
    for (CargaRutas fila : filas) {
      if (fila.getClusterId().equals(clusterId) && fila.getRutas() > 0) {
        carga.merge(fila.getChuteId(), fila.getBultos(), BigDecimal::add);
      }
    }
    Map<Long, Integer> base = operariosBase(ctx);
    int equipo = operariosMaximos(ctx, base);
    int topeChute = topePorChute(ctx, base);
    boolean topeDefinido = ctx.config().getOperariosPorChute() != null;
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
            .append(" chute(s), de un equipo de ")
            .append(equipo)
            .append(".");
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
      mensaje.append(". El equipo es de ").append(equipo);
      if (topeDefinido) {
        mensaje
            .append(" y el máximo por chute, ")
            .append(topeChute)
            .append(": sube alguno de los dos o revisa el límite.");
      } else {
        mensaje.append(": amplía el equipo o revisa el límite.");
      }
    }

    Instant momento = Instant.now();
    LanzamientoBalanceo lanzamiento =
        lanzar(archivoId, clusterId, FaseBalanceo.OPERARIOS, mensaje.toString(), actor, momento);
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
        ola.setId(new OperarioOlaId(archivoId, clusterId, chute));
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
    lotes.insertarHistorial(logs);
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
      carga.keySet().forEach((chute) -> operarios.put(chute, 0));
      int disponibles = Math.min(equipo, carga.size() * topeChute);
      for (int i = 0; i < disponibles; i++) {
        operarios.merge(chuteMasLento(), 1, Integer::sum);
      }
      BigDecimal cierre = tiempoOla();
      if (sinOperarios(cierre)) {
        return;
      }
      int sobrantes = 0;
      for (Long chute : carga.keySet()) {
        int justos = operarios(chute);
        while (justos > 0
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
    rutaRepo.restaurarRutas(archivoId);
    borrarOlas(ctx, ctx.parametrosCluster().keySet());
    historialRepo.borrarPorArchivo(archivoId);
    lanzamientoRepo.borrarPorArchivo(archivoId);
    ctx.parametrosCluster()
        .values()
        .forEach((p) -> p.setEstado(ParametroCluster.ESTADO_PENDIENTE));
    actualizarEstado(ctx);
    tocar(ctx.archivo(), actor);
  }

  /**
   * Devuelve esos clusters a como estaban antes de trabajarlos: deshace los
   * movimientos de sus lanzamientos (también los de rutas de otro cluster que
   * no había empezado) y los manuales de sus rutas, del último al primero. Lo
   * que movió un cluster anterior al separarse se conserva. Quedan sin
   * operarios de ola y sin sus lanzamientos.
   */
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
    for (CambioParaDeshacer cambio : historialRepo.trabajoParaDeshacer(archivoId, clusterIds)) {
      if (HistorialBalanceo.ACCION_CAMBIAR_CLUSTER.equals(cambio.getTipoAccion())) {
        if (cambio.getClusterOrigenId() != null) {
          clusters.put(cambio.getRutaId(), cambio.getClusterOrigenId());
        }
      } else if (cambio.getChuteOrigenId() != null) {
        chutes.put(cambio.getRutaId(), cambio.getChuteOrigenId());
      }
    }
    historialRepo.borrarManualesDeTrabajo(archivoId, clusterIds);
    for (Long clusterId : clusterIds) {
      lanzamientoRepo.borrarPorCluster(archivoId, clusterId);
      ctx.parametrosCluster().get(clusterId).setEstado(ParametroCluster.ESTADO_PENDIENTE);
    }
    lotes.moverRutas(chutes);
    lotes.cambiarClusters(clusters);
    borrarOlas(ctx, clusterIds);
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
      Long archivoId,
      Long clusterId,
      FaseBalanceo fase,
      String mensaje,
      Long actor,
      Instant momento) {
    LanzamientoBalanceo lanzamiento = new LanzamientoBalanceo();
    lanzamiento.setArchivoId(archivoId);
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
    Collection<ParametroCluster> clusters = ctx.parametrosCluster().values();
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

  /**
   * Un cluster acepta o entrega rutas de otro cluster solo si sigue pendiente:
   * con su paso 1 hecho, su reparto y sus operarios quedarían desfasados.
   */
  /** Cambiar rutas de cluster exige que el cluster no esté cerrado. */
  private static void exigirAbierto(Contexto ctx, Long clusterId) {
    if (ParametroCluster.ESTADO_CERRADO.equals(parametroCluster(ctx, clusterId).getEstado())) {
      throw new ConflictoException(
          ctx.nombreCluster(clusterId) + " está cerrado: reábrelo para cambiar rutas de cluster.");
    }
  }

  /** La ruta puede cambiar de cluster: el suyo y al menos otro siguen sin cerrar. */
  private static boolean clusterEditable(Contexto ctx, Long clusterId) {
    ParametroCluster propio = ctx.parametrosCluster().get(clusterId);
    if (propio == null || ParametroCluster.ESTADO_CERRADO.equals(propio.getEstado())) {
      return false;
    }
    return ctx.parametrosCluster().entrySet().stream()
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

    List<CargaRutas> filasActuales = rutaRepo.cargaPorArchivo(archivoId);
    Map<Par, Estadistica> cargaActual = new HashMap<>();
    for (CargaRutas fila : filasActuales) {
      if (clusterId == null || clusterId.equals(fila.getClusterId())) {
        cargaActual.put(new Par(fila.getClusterId(), fila.getChuteId()), estadistica(fila));
      }
    }
    List<CargaRutas> filasOriginales = rutaRepo.cargaOriginalPorArchivo(archivoId);
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
    for (MovimientoRutas movimiento : rutaRepo.movimientosPorArchivo(archivoId, clusterId)) {
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
    for (CambioClusterRutas cambio : rutaRepo.cambiosClusterPorArchivo(archivoId)) {
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
    Map<Long, Integer> numeros = numerosCluster(ctx.clusters());
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
            .sorted(Comparator.comparing(ctx::nombreChute, ORDEN_NATURAL))
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
                      nombresCluster(clustersActuales.get(chuteId), ctx),
                      mezclaIncompatible(clustersOriginales.get(chuteId), numeros),
                      mezclaIncompatible(clustersActuales.get(chuteId), numeros),
                      clustersSeparados(
                          clustersOriginales.get(chuteId), clustersActuales.get(chuteId), numeros, ctx));
                })
            .toList();

    SimulacionKpisResponse kpis =
        new SimulacionKpisResponse(anulable(tiempoOriginal), anulable(tiempoBalanceado), rutasMovidas);
    List<Par> ocupados =
        cargaActual.entrySet().stream()
            .filter((entrada) -> entrada.getValue().rutas() > 0)
            .map(Map.Entry::getKey)
            .toList();
    List<Long> orden = orden(ctx);
    Map<Long, Integer> rutasMovidasPorCluster = new HashMap<>();
    rutaRepo
        .rutasMovidasPorCluster(archivoId)
        .forEach(
            (fila) ->
                rutasMovidasPorCluster.put(fila.getClusterId(), Math.toIntExact(fila.getRutas())));
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
        mezclas(ocupados, ctx),
        movimientos,
        cambiosCluster,
        olas,
        flujoOperarios(ctx, filasActuales, orden).stream()
            .filter((paso) -> clusterId == null || paso.destinoId().equals(clusterId))
            .toList(),
        operariosMaximos(ctx, operariosBase(ctx)),
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
        .sorted(Comparator.comparing(ctx::nombreChute, ORDEN_NATURAL))
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
      detalle.sort(Comparator.comparing(FlujoChuteResponse::chute, ORDEN_NATURAL));
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

  /** Operarios asignados al chute en esa ola; 0 si el chute no trabaja en ella. */
  private static int operariosOla(Contexto ctx, Long clusterId, Long chuteId) {
    OperarioOla ola = ctx.operariosOla().get(new Par(clusterId, chuteId));
    return ola == null ? 0 : ola.getOperarios();
  }

  /**
   * Chutes que hoy mezclan CL1 y CL2 (pares cluster/chute con rutas). La carga
   * ya se aceptó con esos datos, así que se informa como advertencia: el
   * balanceo los separa primero y un movimiento manual tampoco puede crearlos.
   */
  private List<ChuteMezcladoResponse> mezclas(Collection<Par> ocupados, Contexto ctx) {
    Map<Long, Integer> numeros = numerosCluster(ctx.clusters());
    Map<Long, Set<Long>> clustersPorChute = new LinkedHashMap<>();
    ocupados.forEach(
        (par) ->
            clustersPorChute
                .computeIfAbsent(par.chuteId(), (id) -> new LinkedHashSet<>())
                .add(par.clusterId()));
    return clustersPorChute.entrySet().stream()
        .filter((entrada) -> mezclaIncompatible(entrada.getValue(), numeros))
        .sorted(
            Comparator.comparing(
                (Map.Entry<Long, Set<Long>> entrada) -> ctx.nombreChute(entrada.getKey()),
                ORDEN_NATURAL))
        .map(
            (entrada) ->
                new ChuteMezcladoResponse(
                    entrada.getKey(),
                    ctx.nombreChute(entrada.getKey()),
                    entrada.getValue().stream()
                        .map(ctx::nombreCluster)
                        .sorted(ORDEN_NATURAL)
                        .toList()))
        .toList();
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

  /**
   * Clusters CL1/CL2 que el chute tenía mezclados al cargar y que ya no están
   * en él: salieron para cumplir la regla. Vacío si no había mezcla.
   */
  private static List<String> clustersSeparados(
      Set<Long> originales, Set<Long> actuales, Map<Long, Integer> numeros, Contexto ctx) {
    if (!mezclaIncompatible(originales, numeros)) {
      return List.of();
    }
    Set<Long> ahora = actuales == null ? Set.of() : actuales;
    return originales.stream()
        .filter((cluster) -> !ahora.contains(cluster))
        .filter((cluster) -> CLUSTERS_INCOMPATIBLES.contains(numeros.get(cluster)))
        .map(ctx::nombreCluster)
        .sorted(ORDEN_NATURAL)
        .toList();
  }

  /** True si entre los clusters hay más de uno de {@link #CLUSTERS_INCOMPATIBLES} (CL1 con CL2). */
  private static boolean mezclaIncompatible(Collection<Long> clusters, Map<Long, Integer> numeros) {
    if (clusters == null) {
      return false;
    }
    return clusters.stream()
            .map(numeros::get)
            .filter((numero) -> numero != null && CLUSTERS_INCOMPATIBLES.contains(numero))
            .distinct()
            .count()
        > 1;
  }

  /**
   * Número del cluster según {@link #CLUSTER_NUMERADO} («CL1», «CL 2», «C1»).
   * Null si el nombre no sigue ese formato (p. ej. «CP1»).
   */
  static Integer numeroCluster(String nombre) {
    if (nombre == null) {
      return null;
    }
    Matcher coincidencia =
        CLUSTER_NUMERADO.matcher(ESPACIOS.matcher(nombre).replaceAll("").toUpperCase(Locale.ROOT));
    return coincidencia.matches() ? aNumero(coincidencia.group(1)) : null;
  }

  private static Integer aNumero(String digitos) {
    try {
      return Integer.valueOf(digitos);
    } catch (NumberFormatException error) {
      return null;
    }
  }

  private static Map<Long, Integer> numerosCluster(Map<Long, String> nombres) {
    Map<Long, Integer> numeros = new HashMap<>();
    nombres.forEach(
        (id, nombre) -> {
          Integer numero = numeroCluster(nombre);
          if (numero != null) {
            numeros.put(id, numero);
          }
        });
    return numeros;
  }

  /**
   * Cluster ya presente en el chute que impide agregar el cluster indicado:
   * solo CL1 y CL2 no pueden compartir chute. Null si la mezcla es válida.
   */
  private static Long clusterEnConflicto(
      Collection<Long> presentes, Long cluster, Map<Long, Integer> numeros) {
    Integer numero = numeros.get(cluster);
    if (numero == null || !CLUSTERS_INCOMPATIBLES.contains(numero)) {
      return null;
    }
    for (Long presente : presentes) {
      Integer otro = numeros.get(presente);
      if (otro != null && !otro.equals(numero) && CLUSTERS_INCOMPATIBLES.contains(otro)) {
        return presente;
      }
    }
    return null;
  }

  /** Clusters que no pueden compartir chute con el indicado (CL1 ↔ CL2); vacío si no es CL1/CL2. */
  private static Set<Long> incompatiblesDe(Long cluster, Map<Long, Integer> numeros) {
    Integer numero = numeros.get(cluster);
    if (numero == null || !CLUSTERS_INCOMPATIBLES.contains(numero)) {
      return Set.of();
    }
    Set<Long> otros = new HashSet<>();
    numeros.forEach(
        (id, otro) -> {
          if (!otro.equals(numero) && CLUSTERS_INCOMPATIBLES.contains(otro)) {
            otros.add(id);
          }
        });
    return otros;
  }

  private static boolean mezclaRestringida(
      Collection<Long> presentes, Long cluster, Map<Long, Integer> numeros) {
    return clusterEnConflicto(presentes, cluster, numeros) != null;
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
      SimulacionConfig config,
      Map<Long, ParametroCluster> parametrosCluster,
      Map<Long, ParametroChute> parametrosChute,
      Map<Par, OperarioOla> operariosOla,
      Map<Long, String> clusters,
      Map<Long, String> chutes) {

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
    Map<Long, ParametroCluster> parametrosCluster =
        parametroClusterRepo.findByIdArchivoId(archivoId).stream()
            .collect(
                Collectors.toMap(
                    (p) -> p.getId().getClusterId(), Function.identity(), (a, b) -> a, LinkedHashMap::new));
    Map<Long, ParametroChute> parametrosChute =
        parametroChuteRepo.findByIdArchivoId(archivoId).stream()
            .collect(
                Collectors.toMap(
                    (p) -> p.getId().getChuteId(), Function.identity(), (a, b) -> a, LinkedHashMap::new));
    Map<Par, OperarioOla> operariosOla = new HashMap<>();
    operarioOlaRepo
        .findByIdArchivoId(archivoId)
        .forEach(
            (ola) ->
                operariosOla.put(
                    new Par(ola.getId().getClusterId(), ola.getId().getChuteId()), ola));
    Map<Long, String> clusters =
        clusterRepo.findAllById(parametrosCluster.keySet()).stream()
            .collect(Collectors.toMap(Cluster::getId, Cluster::getNombre));
    Map<Long, String> chutes =
        chuteRepo.findAllById(parametrosChute.keySet()).stream()
            .collect(Collectors.toMap(Chute::getId, Chute::getNombre));
    return new Contexto(
        archivo, config, parametrosCluster, parametrosChute, operariosOla, clusters, chutes);
  }

  private ConfiguracionSimulacionResponse aConfiguracion(Contexto ctx) {
    List<CargaRutas> carga = rutaRepo.cargaPorArchivo(ctx.archivo().getId());
    Map<Long, BigDecimal> limites = limites(ctx);
    Map<Long, Integer> operarios = operariosBase(ctx);
    Map<Long, Integer> numeros = numerosCluster(ctx.clusters());
    Map<Long, Integer> diferenciasOriginales =
        diferenciasPorCluster(rutaRepo.cargaOriginalPorArchivo(ctx.archivo().getId()));
    List<ClusterParametroResponse> clusters =
        ctx.parametrosCluster().keySet().stream()
            .map(
                (id) ->
                    new ClusterParametroResponse(
                        id,
                        ctx.nombreCluster(id),
                        limites.getOrDefault(id, BigDecimal.ZERO),
                        ctx.parametrosCluster().keySet().stream()
                            .filter(
                                (otro) ->
                                    clusterEnConflicto(List.of(otro), id, numeros) != null)
                            .toList(),
                        ctx.parametrosCluster().get(id).getEstado(),
                        diferenciasOriginales.get(id)))
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
    List<ChuteParametroResponse> chutes =
        ctx.parametrosChute().entrySet().stream()
            .map(
                (entrada) ->
                    new ChuteParametroResponse(
                        entrada.getKey(),
                        ctx.nombreChute(entrada.getKey()),
                        operarios.get(entrada.getKey()),
                        clustersEnChute.getOrDefault(entrada.getKey(), List.of())))
            .sorted(Comparator.comparing(ChuteParametroResponse::nombre, ORDEN_NATURAL))
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
        mezclas(
            carga.stream()
                .filter((fila) -> fila.getRutas() > 0)
                .map((fila) -> new Par(fila.getClusterId(), fila.getChuteId()))
                .toList(),
            ctx));
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
    Integer definido = ctx.config().getOperariosPorChute();
    if (definido != null && definido > 0) {
      return Math.min(definido, Limites.MAX_OPERARIOS);
    }
    return Math.max(1, Math.min(Limites.MAX_OPERARIOS, operariosMaximos(ctx, operarios)));
  }

  /** 0 o nulo: sin tope propio (así se guarda y se muestra). */
  private static Integer sinTope(Integer valor) {
    return valor == null || valor <= 0 ? null : valor;
  }

  /** Tope global del registro; si nunca se definió, la suma asignada actual. */
  private static int operariosMaximos(Contexto ctx, Map<Long, Integer> operarios) {
    Integer maximo = ctx.config().getOperariosMaximos();
    if (maximo != null && maximo > 0) {
      return maximo;
    }
    int asignados = operarios.values().stream().mapToInt(Integer::intValue).sum();
    // Sin tope y sin operarios: al menos uno por chute para que el balanceo
    // pueda repartir el mínimo en cada ola.
    return asignados > 0 ? asignados : Math.max(1, ctx.parametrosChute().size());
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
    ArchivoExcel archivo =
        archivoRepo
            .findById(archivoId)
            .orElseThrow(
                () ->
                    new RecursoNoEncontradoException(
                        "No existe el registro con id " + archivoId + "."));
    String clave =
        "simulacion:" + archivo.getId() + ":" + archivo.getActualizadoEn() + ":" + vista;
    return cache.obtener(clave, () -> calculo.apply(contexto(archivoId, false)));
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

  private static int compararNatural(String a, String b) {
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
