package oracle.simulacionsanta.controller;

import jakarta.validation.Valid;
import oracle.common.response.ApiResponse;
import oracle.simulacionsanta.request.ConfiguracionRequest;
import oracle.simulacionsanta.response.ConfiguracionResponse;
import oracle.simulacionsanta.service.SimulacionSantaConfiguracionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Configuración con que nace cada registro nuevo de Santa Anita (registroId null). */
@RestController
@RequestMapping("/api/simulacion-santa/predeterminados")
public class SimulacionSantaPredeterminadosController {

  private final SimulacionSantaConfiguracionService servicio;

  public SimulacionSantaPredeterminadosController(SimulacionSantaConfiguracionService servicio) {
    this.servicio = servicio;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('VER_CONFIGURACION_SIMULACION_SANTA')")
  public ApiResponse<ConfiguracionResponse> ver() {
    return servicio.predeterminados();
  }

  @PutMapping
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION_SANTA')")
  public ApiResponse<ConfiguracionResponse> guardar(@Valid @RequestBody ConfiguracionRequest req) {
    return servicio.guardarPredeterminados(req);
  }
}
