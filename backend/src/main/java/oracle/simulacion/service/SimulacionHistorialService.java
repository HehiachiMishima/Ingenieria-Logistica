package oracle.simulacion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import oracle.common.config.Transacciones;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.common.response.ApiResponse;
import oracle.common.response.PaginaResponse;
import oracle.common.support.Trazas;
import oracle.simulacion.model.Balanceo;
import oracle.simulacion.model.HistorialBalanceo;
import oracle.simulacion.model.LanzamientoBalanceo;
import oracle.simulacion.model.RutaProyeccion;
import oracle.simulacion.repository.CargaRutas;
import oracle.simulacion.repository.DeltaChute;
import oracle.simulacion.repository.HistorialBalanceoRepository;
import oracle.simulacion.repository.LanzamientoBalanceoRepository;
import oracle.simulacion.repository.RutaProyeccionRepository;
import oracle.simulacion.request.FaseBalanceo;
import oracle.simulacion.response.ComparativoLanzamientoResponse;
import oracle.simulacion.response.HistorialRegistroResponse;
import oracle.simulacion.response.HistorialSimulacionResponse;
import oracle.simulacion.response.LanzamientoSimulacionResponse;
import oracle.simulacion.service.SimulacionConfiguracionService.Contexto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Historial del balanceo: los lanzamientos que se ven desde un balanceo, los cambios de cada uno
 * (paginados) y su antes y después por chute. Solo lectura; el contexto del registro lo arma
 * {@link SimulacionConfiguracionService}.
 */
@Slf4j
@Service
public class SimulacionHistorialService {

  private static final int MAX_TAMANO_PAGINA = 100;
  private static final int MAX_PAGINA = 2_000;
  /** Tope de una lectura: lo mismo que las del servicio de configuración. */
  private static final int TOPE_TRANSACCION_LECTURA_S = 18;

  private final SimulacionConfiguracionService configuracion;
  private final HistorialBalanceoRepository historialRepo;
  private final LanzamientoBalanceoRepository lanzamientoRepo;
  private final RutaProyeccionRepository rutaRepo;
  private final Transacciones transacciones;

  public SimulacionHistorialService(
      SimulacionConfiguracionService configuracion,
      HistorialBalanceoRepository historialRepo,
      LanzamientoBalanceoRepository lanzamientoRepo,
      RutaProyeccionRepository rutaRepo,
      Transacciones transacciones) {
    this.configuracion = configuracion;
    this.historialRepo = historialRepo;
    this.lanzamientoRepo = lanzamientoRepo;
    this.rutaRepo = rutaRepo;
    this.transacciones = transacciones;
  }

  public ApiResponse<HistorialRegistroResponse> historial(Long archivoId) {
    return historial(archivoId, Balanceo.LXV);
  }

  /**
   * Balanceos cuyo historial se ve desde uno: un día ve el suyo y el de Diarias (sus diarias
   * cambian también ahí); Diarias, solo el suyo.
   */
  private static List<String> historialDe(Balanceo balanceo) {
    return balanceo == Balanceo.DIA
        ? List.of(Balanceo.DIA.name())
        : List.of(balanceo.name(), Balanceo.DIA.name());
  }

  /** Lanzamientos que se ven desde un balanceo (y cuántos cambios a mano tiene). */
  public ApiResponse<HistorialRegistroResponse> historial(Long archivoId, Balanceo balanceo) {
    return leer(
        "historial id=" + archivoId + " balanceo=" + balanceo,
        () -> {
          Contexto ctx = configuracion.contexto(archivoId, balanceo, false);
          List<LanzamientoSimulacionResponse> lanzamientos =
              lanzamientoRepo
                  .findTop200ByArchivoIdAndBalanceoInOrderByIdDesc(archivoId, historialDe(balanceo))
                  .stream()
                  .map(
                      (lanzamiento) ->
                          new LanzamientoSimulacionResponse(
                              lanzamiento.getId(),
                              lanzamiento.getClusterId(),
                              ctx.nombreCluster(lanzamiento.getClusterId()),
                              lanzamiento.getFase(),
                              etiquetaLanzamiento(lanzamiento, balanceo),
                              lanzamiento.getCreadoEn(),
                              lanzamiento.getCreadoPor(),
                              lanzamiento.getRutas(),
                              lanzamiento.getChutesOperarios()))
                  .toList();
          return new HistorialRegistroResponse(
              lanzamientos,
              historialRepo.countByArchivoIdAndBalanceoInAndLanzamientoIdIsNull(
                  archivoId, historialDe(balanceo)));
        },
        "Historial de balanceo.");
  }

  /** Mensaje del lanzamiento; uno de Diarias visto desde un día lo dice delante. */
  private static String etiquetaLanzamiento(LanzamientoBalanceo lanzamiento, Balanceo vista) {
    return vista != Balanceo.DIA && Balanceo.DIA.name().equals(lanzamiento.getBalanceo())
        ? Balanceo.DIA.etiqueta() + " · " + lanzamiento.getMensaje()
        : lanzamiento.getMensaje();
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
        "historial cambios id=" + archivoId + " balanceo=" + balanceo + " lanzamiento=" + lanzamientoId,
        () -> {
          Contexto ctx = configuracion.contexto(archivoId, Balanceo.LXV, false);
          if (lanzamientoId != null) {
            // El lanzamiento tiene que ser del registro y verse desde este balanceo.
            lanzamientoRepo
                .findById(lanzamientoId)
                .filter(
                    (l) ->
                        archivoId.equals(l.getArchivoId())
                            && historialDe(balanceo).contains(l.getBalanceo()))
                .orElseThrow(
                    () ->
                        new RecursoNoEncontradoException(
                            "No existe el lanzamiento "
                                + lanzamientoId
                                + " en "
                                + balanceo.etiqueta()
                                + "."));
          }
          Page<HistorialBalanceo> page =
              lanzamientoId == null
                  ? historialRepo.findByArchivoIdAndBalanceoInAndLanzamientoIdIsNull(
                      archivoId, historialDe(balanceo), pageable)
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

  /**
   * Antes y después de un lanzamiento en los chutes de su cluster. Fase RUTAS: bultos por chute;
   * el después es la carga de hoy sin los cambios posteriores al lanzamiento, y el antes, ese
   * después sin los cambios del lanzamiento (una sola consulta agregada). Fase OPERARIOS: los
   * operarios de cada chute que el historial ya guarda (antes → después).
   */
  public ApiResponse<ComparativoLanzamientoResponse> comparativoLanzamiento(
      Long archivoId, Balanceo balanceo, Long lanzamientoId) {
    return leer(
        "comparativo id=" + archivoId + " balanceo=" + balanceo + " lanzamiento=" + lanzamientoId,
        () -> {
          LanzamientoBalanceo lanzamiento =
              lanzamientoRepo
                  .findById(lanzamientoId)
                  .filter(
                      (l) ->
                          archivoId.equals(l.getArchivoId())
                              && historialDe(balanceo).contains(l.getBalanceo()))
                  .orElseThrow(
                      () ->
                          new RecursoNoEncontradoException(
                              "No existe el lanzamiento "
                                  + lanzamientoId
                                  + " en "
                                  + balanceo.etiqueta()
                                  + "."));
          Contexto ctx = configuracion.contexto(archivoId, balanceo, false);
          Long clusterId = lanzamiento.getClusterId();
          List<ComparativoLanzamientoResponse.ChuteComparado> chutes = new ArrayList<>();
          long bultosMovidos = 0;
          if (FaseBalanceo.OPERARIOS.name().equals(lanzamiento.getFase())) {
            for (HistorialBalanceo log :
                historialRepo.findByLanzamientoIdAndTipoAccion(
                    lanzamientoId, HistorialBalanceo.ACCION_ASIGNAR_OPERARIOS)) {
              if (log.getChuteModificadoId() != null) {
                chutes.add(
                    new ComparativoLanzamientoResponse.ChuteComparado(
                        log.getChuteModificadoId(),
                        ctx.nombreChute(log.getChuteModificadoId()),
                        log.getOperariosOrigen() == null ? 0 : log.getOperariosOrigen(),
                        log.getOperariosDestino() == null ? 0 : log.getOperariosDestino()));
              }
            }
          } else {
            Map<Long, BigDecimal> hoy = new HashMap<>();
            // Solo la carga de este cluster (las frecuencias que se ven desde el balanceo).
            for (CargaRutas fila : SimulacionConfiguracionService.sumarCarga(ctx, rutaRepo.cargaPorCluster(archivoId, clusterId))) {
              if (fila.getBultos() != null) {
                hoy.merge(fila.getChuteId(), fila.getBultos(), BigDecimal::add);
              }
            }
            Long ultimo = historialRepo.ultimoCambio(lanzamientoId);
            Map<Long, BigDecimal[]> deltas = new HashMap<>();
            for (DeltaChute d :
                historialRepo.deltasDeLanzamiento(
                    archivoId,
                    clusterId,
                    lanzamientoId,
                    ultimo == null ? Long.MAX_VALUE : ultimo,
                    historialDe(balanceo))) {
              deltas.put(d.getChuteId(), new BigDecimal[] {cero(d.getDelta()), cero(d.getDeltaPosterior())});
              if (d.getDelta() != null && d.getDelta().signum() > 0) {
                bultosMovidos += entero(d.getDelta());
              }
            }
            Set<Long> ids = new HashSet<>(hoy.keySet());
            ids.addAll(deltas.keySet());
            for (Long chuteId : ids) {
              BigDecimal[] d = deltas.getOrDefault(chuteId, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
              BigDecimal despues = hoy.getOrDefault(chuteId, BigDecimal.ZERO).subtract(d[1]);
              BigDecimal antes = despues.subtract(d[0]);
              long a = entero(antes.max(BigDecimal.ZERO));
              long b = entero(despues.max(BigDecimal.ZERO));
              if (a > 0 || b > 0) {
                chutes.add(new ComparativoLanzamientoResponse.ChuteComparado(chuteId, ctx.nombreChute(chuteId), a, b));
              }
            }
          }
          chutes.sort(
              Comparator.comparing(ComparativoLanzamientoResponse.ChuteComparado::chute, PlanoChutes.ORDEN)
                  .thenComparing(ComparativoLanzamientoResponse.ChuteComparado::chuteId));
          return new ComparativoLanzamientoResponse(
              lanzamientoId,
              clusterId,
              ctx.nombreCluster(clusterId),
              lanzamiento.getFase(),
              lanzamiento.getRutas(),
              bultosMovidos,
              List.copyOf(chutes));
        },
        "Comparativo del lanzamiento.");
  }

  private static BigDecimal cero(BigDecimal valor) {
    return valor == null ? BigDecimal.ZERO : valor;
  }

  /** Bultos redondeados hacia arriba, como en la pantalla y los reportes. */
  private static long entero(BigDecimal valor) {
    return valor.setScale(0, RoundingMode.CEILING).longValue();
  }

  private <T> ApiResponse<T> leer(String operacion, Supplier<T> trabajo, String mensaje) {
    T datos =
        Trazas.medir(
            log,
            "SimulacionHistorialService." + operacion,
            () -> transacciones.consultar(trabajo, TOPE_TRANSACCION_LECTURA_S));
    return ApiResponse.ok(mensaje, datos);
  }
}
