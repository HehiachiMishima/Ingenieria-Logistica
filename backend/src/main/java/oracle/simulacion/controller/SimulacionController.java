package oracle.simulacion.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import oracle.common.exception.ApiError;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import oracle.common.response.PaginaResponse;
import oracle.simulacion.model.TipoRuta;
import oracle.simulacion.model.Balanceo;
import oracle.simulacion.request.BalanceoRequest;
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
import oracle.simulacion.response.VistaBalanceoResponse;
import oracle.simulacion.service.SimulacionConfiguracionService;
import oracle.simulacion.service.SimulacionExcel;
import oracle.simulacion.service.SimulacionRegistroService;
import oracle.simulacion.service.SimulacionReporte;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
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
  private final SimulacionReporte reporte;

  private static final MediaType XLSX =
      MediaType.parseMediaType(
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  public SimulacionController(
      SimulacionRegistroService registroService,
      SimulacionConfiguracionService configuracionService,
      SimulacionReporte reporte) {
    this.registroService = registroService;
    this.configuracionService = configuracionService;
    this.reporte = reporte;
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
  public ApiResponse<ConfiguracionSimulacionResponse> configuracion(
      @PathVariable Long id, @RequestParam(required = false) String balanceo) {
    return configuracionService.obtener(id, Balanceo.de(balanceo));
  }

  @PutMapping("/{id}/configuracion")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<ConfiguracionSimulacionResponse> guardarConfiguracion(
      @PathVariable Long id,
      @RequestParam(required = false) String balanceo,
      @Valid @RequestBody ConfiguracionActualizarRequest req) {
    return configuracionService.guardar(id, Balanceo.de(balanceo), req);
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
      @RequestParam(required = false) String balanceo,
      @Valid @RequestBody MoverRutaRequest req) {
    return configuracionService.mover(id, Balanceo.de(balanceo), rutaId, req);
  }

  /** Pasa una ruta a otro cluster (ambos clusters sin cerrar). */
  @PutMapping("/{id}/rutas/{rutaId}/cluster")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<ConfiguracionSimulacionResponse> cambiarClusterRuta(
      @PathVariable Long id,
      @PathVariable Long rutaId,
      @RequestParam(required = false) String balanceo,
      @Valid @RequestBody CambiarClusterRequest req) {
    return configuracionService.cambiarCluster(id, Balanceo.de(balanceo), rutaId, req);
  }

  /**
   * Lanza un cluster: paso 1 (RUTAS) o paso 2 (OPERARIOS) de su ola. En el paso
   * 1, el cuerpo opcional dice entre qué chutes repartir sus rutas.
   */
  @PostMapping("/{id}/clusters/{clusterId}/balancear")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<BalanceoResponse> balancear(
      @PathVariable Long id,
      @PathVariable Long clusterId,
      @RequestParam FaseBalanceo fase,
      @RequestParam(required = false) String balanceo,
      @Valid @RequestBody(required = false) BalanceoRequest rango) {
    return configuracionService.balancear(id, Balanceo.de(balanceo), clusterId, fase, rango);
  }

  /**
   * Vista previa del paso 1 (no cambia nada): los grupos del cluster en orden de
   * prioridad y el tramo de chutes que recibiría cada uno. Parámetros opcionales:
   * desde y hasta (rango; sin ellos, el último) y prioridad, repetido una vez
   * por grupo en orden (los nombres se leen tal cual, sin partir por comas).
   */
  @GetMapping("/{id}/clusters/{clusterId}/balanceo/vista")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<VistaBalanceoResponse> vistaBalanceo(
      @PathVariable Long id,
      @PathVariable Long clusterId,
      @RequestParam(required = false) @Positive Long desde,
      @RequestParam(required = false) @Positive Long hasta,
      @RequestParam(required = false) String balanceo,
      @RequestParam MultiValueMap<String, String> parametros) {
    List<String> prioridad = parametros.get("prioridad");
    if (prioridad != null
        && (prioridad.size() > BalanceoRequest.MAX_GRUPOS
            || prioridad.stream().anyMatch((g) -> g == null || g.isBlank() || g.length() > 50))) {
      throw new SolicitudInvalidaException("La prioridad de los grupos no es válida.");
    }
    return configuracionService.vistaBalanceo(
        id, Balanceo.de(balanceo), clusterId, new BalanceoRequest(desde, hasta, null, prioridad));
  }

  @PostMapping("/{id}/clusters/{clusterId}/restablecer")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<ConfiguracionSimulacionResponse> restablecerCluster(
      @PathVariable Long id,
      @PathVariable Long clusterId,
      @RequestParam(required = false) String balanceo) {
    return configuracionService.restablecerCluster(id, Balanceo.de(balanceo), clusterId);
  }

  @PostMapping("/{id}/restablecer")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<Void> restablecer(
      @PathVariable Long id, @RequestParam(required = false) String balanceo) {
    return configuracionService.restablecer(id, Balanceo.de(balanceo));
  }

  /** Cierra el cluster en curso: el equipo pasa al siguiente con esta dotación. */
  @PostMapping("/{id}/clusters/{clusterId}/cerrar")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<ConfiguracionSimulacionResponse> cerrar(
      @PathVariable Long id,
      @PathVariable Long clusterId,
      @RequestParam(required = false) String balanceo) {
    return configuracionService.cerrar(id, Balanceo.de(balanceo), clusterId);
  }

  /** Reabre un cluster cerrado; los siguientes vuelven a la proyección. */
  @PostMapping("/{id}/clusters/{clusterId}/reabrir")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION')")
  public ApiResponse<ConfiguracionSimulacionResponse> reabrir(
      @PathVariable Long id,
      @PathVariable Long clusterId,
      @RequestParam(required = false) String balanceo) {
    return configuracionService.reabrir(id, Balanceo.de(balanceo), clusterId);
  }

  @GetMapping("/{id}/historial")
  @PreAuthorize("hasAuthority('VER_CONFIGURACION_SIMULACION')")
  public ApiResponse<HistorialRegistroResponse> historial(
      @PathVariable Long id, @RequestParam(required = false) String balanceo) {
    return configuracionService.historial(id, Balanceo.de(balanceo));
  }

  /** Cambios de un lanzamiento (sin lanzamientoId: los que no tienen), paginados. */
  @GetMapping("/{id}/historial/cambios")
  @PreAuthorize("hasAuthority('VER_CONFIGURACION_SIMULACION')")
  public ApiResponse<PaginaResponse<HistorialSimulacionResponse>> historialCambios(
      @PathVariable Long id,
      @RequestParam(required = false) Long lanzamientoId,
      @RequestParam(defaultValue = "1") int pagina,
      @RequestParam(defaultValue = "25") int tamano,
      @RequestParam(required = false) String balanceo) {
    return configuracionService.historialCambios(
        id, Balanceo.de(balanceo), lanzamientoId, pagina, tamano);
  }

  /** Sin clusterId, el resumen del registro; con él, la vista de ese cluster. */
  @GetMapping("/{id}/dashboard")
  @PreAuthorize("hasAuthority('VER_DASHBOARD_SIMULACION')")
  public ApiResponse<DashboardSimulacionResponse> dashboard(
      @PathVariable Long id,
      @RequestParam(required = false) Long clusterId,
      @RequestParam(required = false) String balanceo) {
    return configuracionService.dashboard(id, Balanceo.de(balanceo), clusterId);
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
      @RequestParam(defaultValue = "false") boolean soloCambios,
      @RequestParam(required = false) String balanceo) {
    // Mismo criterio que el Excel: «b2c» o « B2C » también valen.
    TipoRuta tipoRuta = TipoRuta.desdeTexto(tipo);
    if (tipo != null && !tipo.isBlank() && tipoRuta == null) {
      throw new SolicitudInvalidaException("El tipo de ruta debe ser B2C o B2B.");
    }
    return configuracionService.rutasDashboard(
        id, Balanceo.de(balanceo), pagina, tamano, texto, tipoRuta, chuteIds, lado, clusterId, soloCambios);
  }

  /**
   * Excel completo de la simulación (un solo archivo): resumen, comparativos con
   * gráficos, cuadrículas de Configuración y el detalle de chutes, rutas,
   * operarios e historial. Solo lee.
   */
  @GetMapping("/{id}/exportar")
  @PreAuthorize("hasAuthority('VER_DASHBOARD_SIMULACION')")
  public ResponseEntity<byte[]> exportar(
      @PathVariable Long id, @RequestParam(required = false) String balanceo) {
    Balanceo cual = Balanceo.de(balanceo);
    byte[] contenido = reporte.excel(id, cual);
    return ResponseEntity.ok()
        .contentType(XLSX)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(reporte.nombreArchivo(id, cual), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(contenido);
  }
}
