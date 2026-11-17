package oracle.simulacionsanta.service;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import oracle.common.config.Transacciones;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import oracle.common.support.ActorActual;
import oracle.common.support.Auditoria;
import oracle.common.support.Trazas;
import oracle.simulacionsanta.model.RegistroSanta;
import oracle.simulacionsanta.repository.Candado;
import oracle.simulacionsanta.repository.RegistroSantaRepository;
import oracle.simulacionsanta.repository.SimulacionSantaLotesRepository;
import oracle.simulacionsanta.repository.UbicacionGuardada;
import oracle.simulacionsanta.request.ConfiguracionRequest;
import oracle.simulacionsanta.request.EnvioRequest;
import oracle.simulacionsanta.response.ConfiguracionResponse;
import oracle.simulacionsanta.response.RegistroResponse;
import oracle.simulacionsanta.response.SimulacionResponse;
import org.springframework.stereotype.Service;

/**
 * Configuración, envío en curso y simulación de un registro (los predeterminados y su candado
 * están en {@link SimulacionSantaPredeterminadosService}). La simulación se calcula fuera de la
 * transacción con {@link AsignadorUbicaciones} (la misma para la guardada y la vista previa).
 */
@Slf4j
@Service
public class SimulacionSantaConfiguracionService {

  private final RegistroSantaRepository registros;
  private final SimulacionSantaLotesRepository lotes;
  private final Transacciones transacciones;

  public SimulacionSantaConfiguracionService(
      RegistroSantaRepository registros,
      SimulacionSantaLotesRepository lotes,
      Transacciones transacciones) {
    this.registros = registros;
    this.lotes = lotes;
    this.transacciones = transacciones;
  }

  public ApiResponse<ConfiguracionResponse> configuracion(Long id) {
    var datos =
        Trazas.medir(
            log,
            "SimulacionSantaConfiguracionService.configuracion id=" + id,
            () ->
                transacciones.consultar(
                    () -> {
                      RegistroSanta registro = buscar(id);
                      return SimulacionSantaRespuestas.configuracion(
                          id,
                          configuracionDe(registro, lotes.ubicaciones(id)),
                          registro.getConfiguradoPor(),
                          registro.getConfiguradoEn(),
                          lotes.candado());
                    }));
    return ApiResponse.ok("Configuración del registro.", datos);
  }

  /**
   * Reemplaza la configuración del registro (valida todo antes de abrir la transacción). Con el
   * candado de los predeterminados puesto no se guarda nada: 409 siempre (404 antes, si el
   * registro no existe).
   */
  public ApiResponse<ConfiguracionResponse> guardarConfiguracion(Long id, ConfiguracionRequest req) {
    ConfiguracionSanta config = ConfiguracionSanta.desde(req);
    Long actor = ActorActual.id();
    var datos =
        Trazas.medir(
            log,
            "SimulacionSantaConfiguracionService.guardarConfiguracion id=" + id,
            () ->
                transacciones.ejecutar(
                    () -> {
                      RegistroSanta registro = bloquear(id);
                      Candado candado = lotes.candado();
                      if (candado.bloqueado()) {
                        throw new ConflictoException(
                            SimulacionSantaPredeterminadosService.MENSAJE_BLOQUEADA);
                      }
                      List<String> orden = config.ordenComoTexto();
                      registro.setOperarios(config.operarios());
                      registro.setBultosHora(config.bultosHora());
                      registro.setOrdenCluster1(orden.get(0));
                      registro.setOrdenCluster2(orden.get(1));
                      registro.setOrdenCluster3(orden.get(2));
                      Auditoria.alActualizar(registro, actor);
                      registro.setConfiguradoPor(actor);
                      registro.setConfiguradoEn(registro.getActualizadoEn());
                      registros.save(registro);
                      lotes.guardarUbicaciones(id, config.ubicaciones());
                      return SimulacionSantaRespuestas.configuracion(
                          id, config, actor, registro.getConfiguradoEn(), candado);
                    }));
    return ApiResponse.ok("Configuración guardada.", datos);
  }

  /** Simulación con la configuración guardada; sin {@code envio}, el envío actual del registro. */
  public ApiResponse<SimulacionResponse> simulacion(Long id, Integer envio) {
    validarEnvio(envio);
    Lectura lectura =
        Trazas.medir(
            log,
            "SimulacionSantaConfiguracionService.simulacion id=" + id,
            () ->
                transacciones.consultar(
                    () -> {
                      RegistroSanta registro = buscar(id);
                      return new Lectura(
                          registro.getEnvioActual(),
                          configuracionDe(registro, lotes.ubicaciones(id)),
                          lotes.paletas(id));
                    }));
    return ApiResponse.ok("Simulación del registro.", simular(id, envio, lectura));
  }

  /** Simulación con la configuración del pedido, sin guardar nada. */
  public ApiResponse<SimulacionResponse> previsualizar(
      Long id, Integer envio, ConfiguracionRequest req) {
    validarEnvio(envio);
    ConfiguracionSanta config = ConfiguracionSanta.desde(req);
    Lectura lectura =
        Trazas.medir(
            log,
            "SimulacionSantaConfiguracionService.previsualizar id=" + id,
            () ->
                transacciones.consultar(
                    () ->
                        new Lectura(buscar(id).getEnvioActual(), config, lotes.paletas(id))));
    return ApiResponse.ok("Vista previa de la simulación.", simular(id, envio, lectura));
  }

  /** Envíos ya recibidos (0 a 3): mueve el estado del registro. */
  public ApiResponse<RegistroResponse> cambiarEnvio(Long id, EnvioRequest req) {
    if (req == null || req.envio() == null) {
      throw new SolicitudInvalidaException("Indica el envío.");
    }
    validarEnvio(req.envio());
    int envio = req.envio();
    Long actor = ActorActual.id();
    var datos =
        Trazas.medir(
            log,
            "SimulacionSantaConfiguracionService.cambiarEnvio id=" + id,
            () ->
                transacciones.ejecutar(
                    () -> {
                      RegistroSanta registro = bloquear(id);
                      registro.setEnvioActual(envio);
                      Auditoria.alActualizar(registro, actor);
                      return SimulacionSantaRespuestas.registro(registros.save(registro));
                    }));
    return ApiResponse.ok("Envío actualizado.", datos);
  }

  // ------------------------------------------------------------------

  private record Lectura(int envioActual, ConfiguracionSanta config, List<PaletaSanta> paletas) {}

  private static SimulacionResponse simular(Long id, Integer envio, Lectura lectura) {
    int mostrado = envio == null ? lectura.envioActual() : envio;
    return AsignadorUbicaciones.simular(
        id, mostrado, lectura.envioActual(), lectura.config(), lectura.paletas());
  }

  private static ConfiguracionSanta configuracionDe(
      RegistroSanta registro, List<UbicacionGuardada> ubicaciones) {
    return ConfiguracionSanta.guardada(
        registro.getOperarios(),
        registro.getBultosHora(),
        SimulacionSantaRespuestas.ordenDe(registro),
        ubicaciones);
  }

  private static void validarEnvio(Integer envio) {
    if (envio != null && (envio < 0 || envio > AsignadorUbicaciones.ENVIOS)) {
      throw new SolicitudInvalidaException("El envío debe estar entre 0 y 3.");
    }
  }

  private RegistroSanta buscar(Long id) {
    return registros.findById(id).orElseThrow(() -> SimulacionSantaRegistroService.noExiste(id));
  }

  private RegistroSanta bloquear(Long id) {
    return registros.bloquear(id).orElseThrow(() -> SimulacionSantaRegistroService.noExiste(id));
  }
}
