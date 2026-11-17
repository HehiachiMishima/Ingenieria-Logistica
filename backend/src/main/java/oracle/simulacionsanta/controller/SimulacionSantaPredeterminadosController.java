package oracle.simulacionsanta.controller;

import jakarta.validation.Valid;
import oracle.common.response.ApiResponse;
import oracle.simulacionsanta.request.ConfiguracionRequest;
import oracle.simulacionsanta.response.ConfiguracionResponse;
import oracle.simulacionsanta.service.SimulacionSantaPredeterminadosService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuración con que nace cada registro nuevo de Santa Anita (registroId null) y el candado
 * de operarios y bultos por hora de todos los registros: ver, bloquear y desbloquear.
 */
@RestController
@RequestMapping("/api/simulacion-santa/predeterminados")
public class SimulacionSantaPredeterminadosController {

  private final SimulacionSantaPredeterminadosService servicio;

  public SimulacionSantaPredeterminadosController(SimulacionSantaPredeterminadosService servicio) {
    this.servicio = servicio;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('VER_CONFIGURACION_SIMULACION_SANTA')")
  public ApiResponse<ConfiguracionResponse> ver() {
    return servicio.ver();
  }

  @PutMapping("/bloquear")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION_SANTA')")
  public ApiResponse<ConfiguracionResponse> bloquear(@Valid @RequestBody ConfiguracionRequest req) {
    return servicio.bloquear(req);
  }

  @PutMapping("/desbloquear")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION_SANTA')")
  public ApiResponse<ConfiguracionResponse> desbloquear() {
    return servicio.desbloquear();
  }
}
