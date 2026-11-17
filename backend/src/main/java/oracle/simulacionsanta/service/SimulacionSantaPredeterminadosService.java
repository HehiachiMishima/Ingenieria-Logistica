package oracle.simulacionsanta.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import oracle.common.config.Transacciones;
import oracle.common.response.ApiResponse;
import oracle.common.support.ActorActual;
import oracle.common.support.Trazas;
import oracle.simulacionsanta.repository.Candado;
import oracle.simulacionsanta.repository.PredeterminadosGuardados;
import oracle.simulacionsanta.repository.SimulacionSantaLotesRepository;
import oracle.simulacionsanta.request.ConfiguracionRequest;
import oracle.simulacionsanta.response.ConfiguracionResponse;
import org.springframework.stereotype.Service;

/**
 * Predeterminados de Santa Anita: la configuración con que nace todo registro y su candado.
 * Bloqueada, ningún registro cambia su configuración (operarios, bultos por hora, orden de
 * llenado, clusters y posiciones); al bloquear se lleva completa a los registros PENDIENTE (los
 * EN_CURSO y COMPLETO conservan la suya: ya se despachan con ella). Los envíos no tienen
 * candado. Sin cachés: nada que invalidar al bloquear.
 */
@Slf4j
@Service
public class SimulacionSantaPredeterminadosService {

  /** 409 al guardar la configuración de un registro con el candado puesto. */
  static final String MENSAJE_BLOQUEADA =
      "La configuración está bloqueada: desbloquéala para cambiarla.";

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
   * nuevos nacen con ella), pone el candado y la lleva completa a los registros PENDIENTE. Valida
   * todo antes de abrir la transacción; la respuesta es la fila recién guardada (con el nombre
   * de quien bloqueó).
   */
  public ApiResponse<ConfiguracionResponse> bloquear(ConfiguracionRequest req) {
    ConfiguracionSanta config = ConfiguracionSanta.desde(req);
    Long actor = ActorActual.id();
    // Microsegundos: lo que guarda TIMESTAMPTZ, así la respuesta coincide con una lectura posterior.
    Instant ahora = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Bloqueo bloqueo =
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
                      int aplicados =
                          lotes.aplicarPredeterminados(
                              config.operarios(),
                              config.bultosHora(),
                              config.ordenComoTexto(),
                              actor,
                              ahora);
                      return new Bloqueo(aplicados, respuesta(lotes.predeterminados(false)));
                    }));
    String mensaje =
        "Configuración bloqueada para todos los registros"
            + (bloqueo.aplicados() > 0
                ? "; se aplicó a " + bloqueo.aplicados() + " registro(s) pendiente(s)."
                : ".");
    return ApiResponse.ok(mensaje, bloqueo.datos());
  }

  /** Abre la configuración para cambiarla; los valores se conservan. */
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
    return ApiResponse.ok("Configuración desbloqueada: cámbiala y vuelve a bloquearla.", datos);
  }

  private record Bloqueo(int aplicados, ConfiguracionResponse datos) {}

  /** Respuesta de los predeterminados guardados; los de fábrica (abiertos) si falta la fila. */
  static ConfiguracionResponse respuesta(PredeterminadosGuardados guardados) {
    if (guardados == null) {
      return SimulacionSantaRespuestas.configuracion(
          null, ConfiguracionSanta.fabrica(), null, null, Candado.ABIERTO);
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
        guardados.candado());
  }
}
