package oracle.simulacionsanta.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import oracle.common.config.Transacciones;
import oracle.common.response.ApiResponse;
import oracle.common.support.ActorActual;
import oracle.common.support.Trazas;
import oracle.simulacionsanta.repository.PredeterminadosGuardados;
import oracle.simulacionsanta.repository.SimulacionSantaLotesRepository;
import oracle.simulacionsanta.request.ConfiguracionRequest;
import oracle.simulacionsanta.response.ConfiguracionResponse;
import org.springframework.stereotype.Service;

/**
 * Predeterminados de Santa Anita: la configuración con que nace todo registro y el candado de
 * operarios y bultos por hora («productividad»). Bloqueados, ningún registro los cambia; al
 * bloquear se llevan también a los registros PENDIENTE (los EN_CURSO y COMPLETO conservan los
 * suyos: ya se despachan con ellos). Clusters, posiciones y orden de llenado no tienen candado:
 * se siguen editando en cada registro. Sin cachés: nada que invalidar al bloquear.
 */
@Slf4j
@Service
public class SimulacionSantaPredeterminadosService {

  /** 409 al cambiar operarios o bultos por hora de un registro con el candado puesto. */
  static final String MENSAJE_BLOQUEADOS =
      "Operarios y productividad están bloqueados: desbloquéalos para cambiarlos.";

  private final SimulacionSantaLotesRepository lotes;
  private final Transacciones transacciones;

  public SimulacionSantaPredeterminadosService(
      SimulacionSantaLotesRepository lotes, Transacciones transacciones) {
    this.lotes = lotes;
    this.transacciones = transacciones;
  }

  /** Los predeterminados y su candado (los de fábrica, abiertos, si la fila no existe). */
  public ApiResponse<ConfiguracionResponse> ver() {
    return ApiResponse.ok(
        "Predeterminados de Santa Anita.",
        transacciones.consultar(() -> respuesta(lotes.predeterminados(false))));
  }

  /**
   * En una sola transacción: guarda toda la configuración como predeterminados (los registros
   * nuevos nacen con ella), pone el candado y lleva operarios y bultos por hora a los registros
   * PENDIENTE. Valida todo antes de abrir la transacción.
   */
  public ApiResponse<ConfiguracionResponse> bloquear(ConfiguracionRequest req) {
    ConfiguracionSanta config = ConfiguracionSanta.desde(req);
    Long actor = ActorActual.id();
    // Microsegundos: lo que guarda TIMESTAMPTZ, así la respuesta coincide con una lectura posterior.
    Instant ahora = Instant.now().truncatedTo(ChronoUnit.MICROS);
    int aplicados =
        Trazas.medir(
            log,
            "SimulacionSantaPredeterminadosService.bloquear",
            () ->
                transacciones.ejecutar(
                    () -> {
                      lotes.guardarPredeterminados(
                          config.operarios(),
                          config.bultosHora(),
                          config.ordenComoTexto(),
                          config.ubicaciones(),
                          true,
                          actor,
                          ahora);
                      return lotes.aplicarPredeterminados(
                          config.operarios(), config.bultosHora(), actor, ahora);
                    }));
    String mensaje =
        "Operarios y productividad bloqueados para todos los registros"
            + (aplicados > 0
                ? "; se aplicaron a " + aplicados + " registro(s) pendiente(s)."
                : ".");
    return ApiResponse.ok(
        mensaje, SimulacionSantaRespuestas.configuracion(null, config, actor, ahora, true));
  }

  /** Abre operarios y bultos por hora para cambiarlos; los valores se conservan. */
  public ApiResponse<ConfiguracionResponse> desbloquear() {
    Long actor = ActorActual.id();
    Instant ahora = Instant.now().truncatedTo(ChronoUnit.MICROS);
    var datos =
        Trazas.medir(
            log,
            "SimulacionSantaPredeterminadosService.desbloquear",
            () ->
                transacciones.ejecutar(
                    () -> {
                      lotes.desbloquearPredeterminados(actor, ahora);
                      return respuesta(lotes.predeterminados(false));
                    }));
    return ApiResponse.ok(
        "Operarios y productividad desbloqueados: cámbialos y vuelve a bloquearlos.", datos);
  }

  /** Respuesta de los predeterminados guardados; los de fábrica (abiertos) si falta la fila. */
  static ConfiguracionResponse respuesta(PredeterminadosGuardados guardados) {
    if (guardados == null) {
      return SimulacionSantaRespuestas.configuracion(
          null, ConfiguracionSanta.fabrica(), null, null, false);
    }
    return SimulacionSantaRespuestas.configuracion(
        null,
        ConfiguracionSanta.guardada(
            guardados.operarios(),
            guardados.bultosHora(),
            guardados.ordenClusters(),
            guardados.ubicaciones()),
        guardados.actualizadoPor(),
        guardados.actualizadoEn(),
        guardados.bloqueado());
  }
}
