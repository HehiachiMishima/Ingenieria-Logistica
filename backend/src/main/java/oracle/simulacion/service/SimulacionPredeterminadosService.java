package oracle.simulacion.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import oracle.common.config.CacheRespuestas;
import oracle.common.config.Transacciones;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import oracle.simulacion.model.Chute;
import oracle.simulacion.model.Predeterminados;
import oracle.simulacion.repository.ChuteRepository;
import oracle.simulacion.repository.SimulacionLotesRepository;
import oracle.simulacion.request.PredeterminadosRequest;
import oracle.simulacion.response.PredeterminadosResponse;
import org.springframework.stereotype.Service;

/**
 * Velocidad y chutes con buff predeterminados: con ellos nace todo registro. Bloqueados, ningún
 * registro los cambia; al bloquear se llevan también a los registros sin balancear (los ya
 * balanceados conservan los suyos: el buff ya decidió dónde quedaron sus rutas y la velocidad,
 * sus operarios). Sin historial: solo los valores y el candado.
 */
@Service
public class SimulacionPredeterminadosService {

  private final SimulacionLotesRepository lotes;
  private final ChuteRepository chuteRepo;
  private final PlanoChutes plano;
  private final Transacciones transacciones;
  private final CacheRespuestas cache;

  public SimulacionPredeterminadosService(
      SimulacionLotesRepository lotes,
      ChuteRepository chuteRepo,
      PlanoChutes plano,
      Transacciones transacciones,
      CacheRespuestas cache) {
    this.lotes = lotes;
    this.chuteRepo = chuteRepo;
    this.plano = plano;
    this.transacciones = transacciones;
    this.cache = cache;
  }

  public ApiResponse<PredeterminadosResponse> ver() {
    return ApiResponse.ok(
        "Predeterminados de Simulación.",
        transacciones.consultar(() -> PredeterminadosResponse.de(actuales(lotes))));
  }

  /**
   * Fija velocidad y buff para todos los registros y los bloquea. Se aplican en el momento a los
   * registros sin balancear; los nuevos nacen con ellos.
   */
  public ApiResponse<PredeterminadosResponse> bloquear(PredeterminadosRequest req) {
    List<Long> buff = req.chutesBuff().stream().distinct().sorted().toList();
    int registros =
        transacciones.ejecutar(
            () -> {
              validarChutes(buff);
              lotes.guardarPredeterminados(req.velocidad(), buff, true);
              int aplicados = lotes.aplicarPredeterminados(req.velocidad(), buff);
              // Las configuraciones cacheadas de esos registros ya no valen.
              transacciones.alConfirmar(() -> cache.invalidarEspacio("simulacion"));
              return aplicados;
            });
    String mensaje =
        "Velocidad y buff bloqueados para todos los registros"
            + (registros > 0 ? "; se aplicaron a " + registros + " registro(s) sin balancear." : ".");
    return ApiResponse.ok(mensaje, new PredeterminadosResponse(req.velocidad(), buff, true));
  }

  /** Abre velocidad y buff para cambiarlos (los valores se conservan hasta volver a bloquear). */
  public ApiResponse<PredeterminadosResponse> desbloquear() {
    Predeterminados actuales =
        transacciones.ejecutar(
            () -> {
              lotes.desbloquearPredeterminados();
              return actuales(lotes);
            });
    return ApiResponse.ok(
        "Velocidad y buff desbloqueados: cámbialos y vuelve a bloquearlos.",
        PredeterminadosResponse.de(actuales));
  }

  /** Los predeterminados guardados; los de siempre si la base aún no tiene la fila. */
  static Predeterminados actuales(SimulacionLotesRepository lotes) {
    Predeterminados guardados = lotes.leerPredeterminados();
    return guardados == null ? Predeterminados.INICIALES : guardados;
  }

  /** Cada chute existe en el catálogo y no está bloqueado en el plano (19, 20, 21). */
  private void validarChutes(List<Long> ids) {
    if (ids.isEmpty()) {
      return;
    }
    Set<Long> encontrados = new HashSet<>();
    for (Chute chute : chuteRepo.findAllById(ids)) {
      if (plano.bloqueado(chute.getNombre())) {
        throw new SolicitudInvalidaException(
            "El chute " + chute.getNombre() + " está bloqueado: no puede tener buff.");
      }
      encontrados.add(chute.getId());
    }
    for (Long id : ids) {
      if (!encontrados.contains(id)) {
        throw new SolicitudInvalidaException("El chute " + id + " no existe.");
      }
    }
  }
}
