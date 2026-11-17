package oracle.simulacionsanta.controller;

import oracle.common.response.ApiResponse;
import oracle.simulacionsanta.response.DashboardSanta;
import oracle.simulacionsanta.service.SimulacionSantaDashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard de envíos y operarios de un registro de Santa Anita. Es un GET: cuenta en el cupo de
 * lecturas del panel (LimiteUsoSimulacionSanta), como los demás.
 */
@RestController
@RequestMapping("/api/simulacion-santa/registros")
public class SimulacionSantaDashboardController {

  private final SimulacionSantaDashboardService servicio;

  public SimulacionSantaDashboardController(SimulacionSantaDashboardService servicio) {
    this.servicio = servicio;
  }

  @GetMapping("/{id}/dashboard")
  @PreAuthorize("hasAuthority('VER_DASHBOARD_SIMULACION_SANTA')")
  public ApiResponse<DashboardSanta> dashboard(@PathVariable Long id) {
    return servicio.dashboard(id);
  }
}
