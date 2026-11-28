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
 * Velocidad, chutes con buff y chutes bloqueados predeterminados: con ellos nace todo registro. Con
 * el candado puesto, ningún registro los cambia; al ponerlo, velocidad y buff se llevan también a
 * los registros sin balancear (los ya balanceados conservan los suyos: el buff ya decidió dónde
 * quedaron sus rutas y la velocidad, sus operarios) y los chutes bloqueados, a todos (valen para
 * los balanceos siguientes). Sin historial: solo los valores y el candado.
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
        transacciones.consultar(() -> PredeterminadosResponse.de(actuales(lotes), plano.idsManualesPorDefecto())));
  }

  /**
   * Fija velocidad y buff para todos los registros y los bloquea. Se aplican en el momento a los
   * registros sin balancear; los nuevos nacen con ellos.
   */
  public ApiResponse<PredeterminadosResponse> bloquear(PredeterminadosRequest req) {
    List<Long> buff = req.chutesBuff().stream().distinct().sorted().toList();
    List<Long> bloqueados =
        req.chutesBloqueados() == null ? null : req.chutesBloqueados().stream().distinct().sorted().toList();
    List<Long> porDefecto = plano.idsManualesPorDefecto();
    record Hecho(int registros, List<Long> bloqueados) {}
    Hecho hecho =
        transacciones.ejecutar(
            () -> {
              validarChutes(buff);
              if (bloqueados != null) {
                validarBloqueados(bloqueados, buff);
              }
              lotes.guardarPredeterminados(req.velocidad(), buff, bloqueados, true);
              int aplicados = lotes.aplicarPredeterminados(req.velocidad(), buff, bloqueados, porDefecto);
              // Las configuraciones cacheadas de esos registros ya no valen.
              transacciones.alConfirmar(() -> cache.invalidarEspacio("simulacion"));
              return new Hecho(aplicados, bloqueados != null ? bloqueados : actuales(lotes).bloqueadosO(porDefecto));
            });
    int registros = hecho.registros();
    String mensaje =
        "Velocidad, buff y chutes bloqueados fijados para todos los registros"
            + (registros > 0 ? "; velocidad y buff se aplicaron a " + registros + " registro(s) sin balancear." : ".");
    return ApiResponse.ok(mensaje, new PredeterminadosResponse(req.velocidad(), buff, hecho.bloqueados(), true));
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
        PredeterminadosResponse.de(actuales, plano.idsManualesPorDefecto()));
  }

  /** Los predeterminados guardados; los de siempre si la base aún no tiene la fila. */
  public static Predeterminados actuales(SimulacionLotesRepository lotes) {
    Predeterminados guardados = lotes.leerPredeterminados();
    return guardados == null ? Predeterminados.INICIALES : guardados;
  }

  /**
   * Cada chute bloqueado existe en el catálogo, no está fuera de servicio y no tiene buff; y queda al
   * menos un chute del plano para el balanceo automático.
   */
  private void validarBloqueados(List<Long> ids, List<Long> buff) {
    for (Long id : ids) {
      if (buff.contains(id)) {
        throw new SolicitudInvalidaException(
            "El chute " + id + " no puede tener buff y estar bloqueado: el balanceo automático no lo usa.");
      }
    }
    if (!ids.isEmpty()) {
      Set<Long> encontrados = new HashSet<>();
      for (Chute chute : chuteRepo.findAllById(ids)) {
        if (plano.bloqueado(chute.getNombre())) {
          throw new SolicitudInvalidaException(
              "El chute " + chute.getNombre() + " está fuera de servicio: no se puede bloquear.");
        }
        encontrados.add(chute.getId());
      }
      for (Long id : ids) {
        if (!encontrados.contains(id)) {
          throw new SolicitudInvalidaException("El chute " + id + " no existe.");
        }
      }
    }
    List<Long> visibles = plano.idsVisibles();
    if (!visibles.isEmpty() && ids.containsAll(visibles)) {
      throw new SolicitudInvalidaException(
          "Deja al menos un chute sin bloquear: el balanceo automático necesita dónde repartir.");
    }
  }

  /** Cada chute existe en el catálogo y no está fuera de servicio. */
  private void validarChutes(List<Long> ids) {
    if (ids.isEmpty()) {
      return;
    }
    Set<Long> encontrados = new HashSet<>();
    for (Chute chute : chuteRepo.findAllById(ids)) {
      if (plano.bloqueado(chute.getNombre())) {
        throw new SolicitudInvalidaException(
            "El chute " + chute.getNombre() + " está fuera de servicio: no puede tener buff.");
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
