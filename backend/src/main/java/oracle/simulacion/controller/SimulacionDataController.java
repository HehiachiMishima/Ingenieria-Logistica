package oracle.simulacion.controller;

import jakarta.validation.Valid;
import oracle.common.response.ApiResponse;
import oracle.simulacion.request.DataRequest;
import oracle.simulacion.response.CambioDataResponse;
import oracle.simulacion.service.SimulacionDataService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DATA en Configuración de Simulación Titan: las rutas que no se balancean (crear, editar,
 * eliminar, tipo, frecuencia y grupo), moverlas entre DATA y los clusters, y cuántos clusters hay.
 * Las lecturas son las de Configuración (las rutas de DATA llegan con {@code clusterId} null).
 */
@RestController
@RequestMapping("/api/simulacion/registros")
public class SimulacionDataController {

  private final SimulacionDataService service;

  public SimulacionDataController(SimulacionDataService service) {
    this.service = service;
  }

  @PostMapping("/{id}/data/rutas")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<CambioDataResponse> crearRuta(@PathVariable Long id, @Valid @RequestBody DataRequest.Ruta req) {
    return service.crear(id, req);
  }

  @PutMapping("/{id}/data/rutas/{rutaId}")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<CambioDataResponse> actualizarRuta(
      @PathVariable Long id, @PathVariable Long rutaId, @Valid @RequestBody DataRequest.Ruta req) {
    return service.actualizar(id, rutaId, req);
  }

  @DeleteMapping("/{id}/data/rutas/{rutaId}")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<CambioDataResponse> eliminarRuta(@PathVariable Long id, @PathVariable Long rutaId) {
    return service.eliminar(id, rutaId);
  }

  @PutMapping("/{id}/data/tipo")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<CambioDataResponse> asignarTipo(@PathVariable Long id, @Valid @RequestBody DataRequest.Tipo req) {
    return service.asignarTipo(id, req);
  }

  @PutMapping("/{id}/data/frecuencia")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<CambioDataResponse> asignarFrecuencia(
      @PathVariable Long id, @Valid @RequestBody DataRequest.Frecuencia req) {
    return service.asignarFrecuencia(id, req);
  }

  @PutMapping("/{id}/data/grupo")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<CambioDataResponse> asignarGrupo(@PathVariable Long id, @Valid @RequestBody DataRequest.Grupo req) {
    return service.asignarGrupo(id, req);
  }

  /** Rutas a una celda: a un cluster (desde DATA) o a DATA ({@code clusterId} null). Todas o ninguna. */
  @PutMapping("/{id}/data/mover")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<CambioDataResponse> mover(@PathVariable Long id, @Valid @RequestBody DataRequest.Mover req) {
    return service.mover(id, req);
  }

  /** Cuántos clusters hay: se agregan o se quitan desde el último. */
  @PutMapping("/{id}/clusters/cantidad")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<CambioDataResponse> cambiarClusters(@PathVariable Long id, @Valid @RequestBody DataRequest.Clusters req) {
    return service.cambiarClusters(id, req);
  }
}
