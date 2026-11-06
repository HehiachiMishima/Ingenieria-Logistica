package oracle.simulacion.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import oracle.common.exception.ApiError;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import oracle.common.response.PaginaResponse;
import oracle.simulacion.model.TipoRuta;
import oracle.simulacion.request.CambiarClusterRequest;
import oracle.simulacion.request.ConfiguracionActualizarRequest;
import oracle.simulacion.request.FaseBalanceo;
import oracle.simulacion.request.LadoChute;
import oracle.simulacion.request.Limites;
import oracle.simulacion.request.MoverRutaRequest;
import oracle.simulacion.request.RegistroActualizarRequest;
import oracle.simulacion.response.ArchivoSimulacionResponse;
import oracle.simulacion.response.BalanceoResponse;
import oracle.simulacion.response.ConfiguracionSimulacionResponse;
import oracle.simulacion.response.DashboardSimulacionResponse;
import oracle.simulacion.response.HistorialRegistroResponse;
import oracle.simulacion.response.HistorialSimulacionResponse;
import oracle.simulacion.response.PaginaRutasResponse;
import oracle.simulacion.response.RutasDashboardResponse;
import oracle.simulacion.service.SimulacionConfiguracionService;
import oracle.simulacion.service.SimulacionExcel;
import oracle.simulacion.service.SimulacionRegistroService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Registro, configuración y dashboard del panel Simulación. */
@RestController
@RequestMapping("/api/simulacion/registros")
public class SimulacionController {

  private final SimulacionRegistroService registroService;
  private final SimulacionConfiguracionService configuracionService;

  public SimulacionController(
      SimulacionRegistroService registroService,
      SimulacionConfiguracionService configuracionService) {
    this.registroService = registroService;
    this.configuracionService = configuracionService;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('VER_REGISTRO_SIMULACION')")
  public ApiResponse<PaginaResponse<ArchivoSimulacionResponse>> listar(
      @RequestParam(defaultValue = "1") int pagina,
      @RequestParam(defaultValue = "10") int tamano,
      @RequestParam(required = false) String texto,
      @RequestParam(required = false) List<String> estados) {
    return registroService.listar(pagina, tamano, texto, estados);
  }

  @GetMapping("/opciones")
  @PreAuthorize(
      "hasAnyAuthority('VER_REGISTRO_SIMULACION','VER_CONFIGURACION_SIMULACION','VER_DASHBOARD_SIMULACION')")
  public ApiResponse<List<ArchivoSimulacionResponse>> opciones() {
    return registroService.opciones();
  }

  /**
   * Alta de un registro desde un .xlsx/.xls/.csv.
   *
   * <p>Un archivo inválido responde con su 4xx (400/413/415/422) y el mismo cuerpo ApiError; el
   * resto de errores sigue en el GlobalExceptionHandler. La carga corre en el hilo virtual de la
   * petición; si pasa de los 60 s del proxy, la carga termina igual y un reintento con el mismo
   * archivo devuelve el registro ya creado (es idempotente).
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAuthority('CREAR_REGISTRO_SIMULACION')")
  public ResponseEntity<?> subir(
      @RequestPart("archivo") MultipartFile archivo,
      @RequestParam(required = false) String nombreRegistro,
      @RequestParam(required = false) String etiqueta,
      @RequestParam(required = false) String colorHex,
      HttpServletRequest peticion) {
    try {
      return ResponseEntity.ok(registroService.subir(archivo, nombreRegistro, etiqueta, colorHex));
    } catch (SimulacionExcel.ArchivoInvalidoException error) {
      return ResponseEntity.status(error.estado())
          .body(
              ApiError.de(
                  error.estado().value(),
                  error.estado().getReasonPhrase(),
                  error.getMessage(),
                  peticion.getRequestURI()));
    }
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('MODIFICAR_REGISTRO_SIMULACION')")
  public ApiResponse<ArchivoSimulacionResponse> actualizar(
      @PathVariable Long id, @Valid @RequestBody RegistroActualizarRequest req) {
    return registroService.actualizar(id, req);
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('ELIMINAR_REGISTRO_SIMULACION')")
  public ApiResponse<Void> eliminar(@PathVariable Long id) {
    return registroService.eliminar(id);
  }

  @GetMapping("/{id}/configuracion")
  @PreAuthorize("hasAuthority('VER_CONFIGURACION_SIMULACION')")
  public ApiResponse<ConfiguracionSimulacionResponse> configuracion(@PathVariable Long id) {
    return configuracionService.obtener(id);
  }

  @PutMapping("/{id}/configuracion")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<ConfiguracionSimulacionResponse> guardarConfiguracion(
      @PathVariable Long id, @Valid @RequestBody ConfiguracionActualizarRequest req) {
    return configuracionService.guardar(id, req);
  }

  @GetMapping("/{id}/rutas")
  @PreAuthorize("hasAuthority('VER_CONFIGURACION_SIMULACION')")
  public ApiResponse<PaginaRutasResponse> rutas(
      @PathVariable Long id,
      @RequestParam(required = false) Long despues,
      @RequestParam(defaultValue = "1000") int tamano,
      @RequestParam(required = false) String texto,
      @RequestParam(required = false) List<Long> clusterIds,
      @RequestParam(required = false) List<Long> chuteIds) {
    return configuracionService.rutas(id, despues, tamano, texto, clusterIds, chuteIds);
  }

  @PutMapping("/{id}/rutas/{rutaId}/mover")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<ConfiguracionSimulacionResponse> moverRuta(
      @PathVariable Long id,
      @PathVariable Long rutaId,
      @Valid @RequestBody MoverRutaRequest req) {
    return configuracionService.mover(id, rutaId, req);
  }

  /** Pasa una ruta a otro cluster (solo con ambos clusters sin balancear). */
  @PutMapping("/{id}/rutas/{rutaId}/cluster")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<ConfiguracionSimulacionResponse> cambiarClusterRuta(
      @PathVariable Long id,
      @PathVariable Long rutaId,
      @Valid @RequestBody CambiarClusterRequest req) {
    return configuracionService.cambiarCluster(id, rutaId, req);
  }

  /** Lanza un cluster: paso 1 (RUTAS) o paso 2 (OPERARIOS) de su ola. */
  @PostMapping("/{id}/clusters/{clusterId}/balancear")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<BalanceoResponse> balancear(
      @PathVariable Long id, @PathVariable Long clusterId, @RequestParam FaseBalanceo fase) {
    return configuracionService.balancear(id, clusterId, fase);
  }

  @PostMapping("/{id}/clusters/{clusterId}/restablecer")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<ConfiguracionSimulacionResponse> restablecerCluster(
      @PathVariable Long id, @PathVariable Long clusterId) {
    return configuracionService.restablecerCluster(id, clusterId);
  }

  @PostMapping("/{id}/restablecer")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<Void> restablecer(@PathVariable Long id) {
    return configuracionService.restablecer(id);
  }

  /** Cierra el cluster en curso: el equipo pasa al siguiente con esta dotación. */
  @PostMapping("/{id}/clusters/{clusterId}/cerrar")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<ConfiguracionSimulacionResponse> cerrar(
      @PathVariable Long id, @PathVariable Long clusterId) {
    return configuracionService.cerrar(id, clusterId);
  }

  /** Reabre un cluster cerrado; los siguientes vuelven a la proyección. */
  @PostMapping("/{id}/clusters/{clusterId}/reabrir")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<ConfiguracionSimulacionResponse> reabrir(
      @PathVariable Long id, @PathVariable Long clusterId) {
    return configuracionService.reabrir(id, clusterId);
  }

  @GetMapping("/{id}/historial")
  @PreAuthorize("hasAuthority('VER_CONFIGURACION_SIMULACION')")
  public ApiResponse<HistorialRegistroResponse> historial(@PathVariable Long id) {
    return configuracionService.historial(id);
  }

  /** Cambios de un lanzamiento (sin lanzamientoId: los que no tienen), paginados. */
  @GetMapping("/{id}/historial/cambios")
  @PreAuthorize("hasAuthority('VER_CONFIGURACION_SIMULACION')")
  public ApiResponse<PaginaResponse<HistorialSimulacionResponse>> historialCambios(
      @PathVariable Long id,
      @RequestParam(required = false) Long lanzamientoId,
      @RequestParam(defaultValue = "1") int pagina,
      @RequestParam(defaultValue = "25") int tamano) {
    return configuracionService.historialCambios(id, lanzamientoId, pagina, tamano);
  }

  /** Sin clusterId, el resumen del registro; con él, la vista de ese cluster. */
  @GetMapping("/{id}/dashboard")
  @PreAuthorize("hasAuthority('VER_DASHBOARD_SIMULACION')")
  public ApiResponse<DashboardSimulacionResponse> dashboard(
      @PathVariable Long id, @RequestParam(required = false) Long clusterId) {
    return configuracionService.dashboard(id, clusterId);
  }

  @GetMapping("/{id}/dashboard/rutas")
  @PreAuthorize("hasAuthority('VER_DASHBOARD_SIMULACION')")
  public ApiResponse<RutasDashboardResponse> rutasDashboard(
      @PathVariable Long id,
      @RequestParam(defaultValue = "1") int pagina,
      @RequestParam(defaultValue = "25") int tamano,
      @RequestParam(required = false) String texto,
      @RequestParam(required = false) String tipo,
      @RequestParam @Size(min = 1, max = Limites.MAX_CHUTES_DETALLE) List<Long> chuteIds,
      @RequestParam(defaultValue = "DESPUES") LadoChute lado,
      @RequestParam(required = false) Long clusterId,
      @RequestParam(defaultValue = "false") boolean soloCambios) {
    // Mismo criterio que el Excel: «b2c» o « B2C » también valen.
    TipoRuta tipoRuta = TipoRuta.desdeTexto(tipo);
    if (tipo != null && !tipo.isBlank() && tipoRuta == null) {
      throw new SolicitudInvalidaException("El tipo de ruta debe ser B2C o B2B.");
    }
    return configuracionService.rutasDashboard(
        id, pagina, tamano, texto, tipoRuta, chuteIds, lado, clusterId, soloCambios);
  }
}
