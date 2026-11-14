package oracle.simulacion.controller;

import jakarta.validation.Valid;
import oracle.common.response.ApiResponse;
import oracle.simulacion.request.PredeterminadosRequest;
import oracle.simulacion.response.PredeterminadosResponse;
import oracle.simulacion.service.SimulacionPredeterminadosService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Velocidad y chutes con buff de todos los registros: ver, bloquear y desbloquear. */
@RestController
@RequestMapping("/api/simulacion/predeterminados")
public class SimulacionPredeterminadosController {

  private final SimulacionPredeterminadosService servicio;

  public SimulacionPredeterminadosController(SimulacionPredeterminadosService servicio) {
    this.servicio = servicio;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('VER_CONFIGURACION_SIMULACION')")
  public ApiResponse<PredeterminadosResponse> ver() {
    return servicio.ver();
  }

  @PutMapping("/bloquear")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<PredeterminadosResponse> bloquear(@Valid @RequestBody PredeterminadosRequest req) {
    return servicio.bloquear(req);
  }

  @PutMapping("/desbloquear")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<PredeterminadosResponse> desbloquear() {
    return servicio.desbloquear();
  }
}
