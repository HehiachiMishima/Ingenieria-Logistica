package oracle.simulacionsanta.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import oracle.common.exception.ApiError;
import oracle.common.response.ApiResponse;
import oracle.common.response.PaginaResponse;
import oracle.simulacionsanta.request.ConfiguracionRequest;
import oracle.simulacionsanta.request.EnvioRequest;
import oracle.simulacionsanta.request.RegistroNombreRequest;
import oracle.simulacionsanta.response.ConfiguracionResponse;
import oracle.simulacionsanta.response.RegistroResponse;
import oracle.simulacionsanta.response.SimulacionResponse;
import oracle.simulacionsanta.service.ExcelSantaAnita.ArchivoInvalidoException;
import oracle.simulacionsanta.service.SimulacionSantaConfiguracionService;
import oracle.simulacionsanta.service.SimulacionSantaRegistroService;
import org.springframework.http.HttpStatus;
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

/** Cargas de Excel, configuración, envío y simulación del panel Simulación Santa Anita. */
@RestController
@RequestMapping("/api/simulacion-santa/registros")
public class SimulacionSantaController {

  private final SimulacionSantaRegistroService registroService;
  private final SimulacionSantaConfiguracionService configuracionService;

  public SimulacionSantaController(
      SimulacionSantaRegistroService registroService,
      SimulacionSantaConfiguracionService configuracionService) {
    this.registroService = registroService;
    this.configuracionService = configuracionService;
  }

  /** Recientes primero; {@code estados} repetido o separado por coma. */
  @GetMapping
  @PreAuthorize("hasAuthority('VER_REGISTRO_SIMULACION_SANTA')")
  public ApiResponse<PaginaResponse<RegistroResponse>> listar(
      @RequestParam(defaultValue = "1") int pagina,
      @RequestParam(defaultValue = "10") int tamano,
      @RequestParam(required = false) String texto,
      @RequestParam(required = false) List<String> estados) {
    return registroService.listar(pagina, tamano, texto, estados);
  }

  /** Los 200 más recientes, para los selectores. */
  @GetMapping("/opciones")
  @PreAuthorize(
      "hasAnyAuthority('VER_REGISTRO_SIMULACION_SANTA','VER_CONFIGURACION_SIMULACION_SANTA')")
  public ApiResponse<List<RegistroResponse>> opciones() {
    return registroService.opciones();
  }

  @GetMapping("/{id}")
  @PreAuthorize(
      "hasAnyAuthority('VER_REGISTRO_SIMULACION_SANTA','VER_CONFIGURACION_SIMULACION_SANTA')")
  public ApiResponse<RegistroResponse> obtener(@PathVariable Long id) {
    return registroService.obtener(id);
  }

  /**
   * Lee el Excel y dice qué se guardaría, sin guardar. Un archivo inválido responde su 4xx
   * (400/413/415/422) con el mismo cuerpo ApiError; el resto sigue en el GlobalExceptionHandler.
   */
  @PostMapping(value = "/validar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAuthority('CREAR_REGISTRO_SIMULACION_SANTA')")
  public ResponseEntity<?> validar(
      @RequestPart("archivo") MultipartFile archivo, HttpServletRequest peticion) {
    try {
      return ResponseEntity.ok(registroService.validar(archivo));
    } catch (ArchivoInvalidoException error) {
      return archivoInvalido(error, peticion);
    }
  }

  /**
   * Alta desde un .xlsx/.xls/.csv con su nombre (1 a 150): guarda solo las filas válidas. Un
   * reintento con el mismo archivo y nombre devuelve el registro ya creado (idempotente).
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAuthority('CREAR_REGISTRO_SIMULACION_SANTA')")
  public ResponseEntity<?> subir(
      @RequestPart("archivo") MultipartFile archivo,
      @RequestParam(required = false) String nombre,
      HttpServletRequest peticion) {
    try {
      return ResponseEntity.ok(registroService.subir(archivo, nombre));
    } catch (ArchivoInvalidoException error) {
      return archivoInvalido(error, peticion);
    }
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('MODIFICAR_REGISTRO_SIMULACION_SANTA')")
  public ApiResponse<RegistroResponse> renombrar(
      @PathVariable Long id, @Valid @RequestBody RegistroNombreRequest req) {
    return registroService.renombrar(id, req);
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('ELIMINAR_REGISTRO_SIMULACION_SANTA')")
  public ApiResponse<Void> eliminar(@PathVariable Long id) {
    return registroService.eliminar(id);
  }

  @GetMapping("/{id}/configuracion")
  @PreAuthorize("hasAuthority('VER_CONFIGURACION_SIMULACION_SANTA')")
  public ApiResponse<ConfiguracionResponse> configuracion(@PathVariable Long id) {
    return configuracionService.configuracion(id);
  }

  @PutMapping("/{id}/configuracion")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION_SANTA')")
  public ApiResponse<ConfiguracionResponse> guardarConfiguracion(
      @PathVariable Long id, @Valid @RequestBody ConfiguracionRequest req) {
    return configuracionService.guardarConfiguracion(id, req);
  }

  /** Sin {@code envio}, el envío actual del registro. */
  @GetMapping("/{id}/simulacion")
  @PreAuthorize("hasAuthority('VER_CONFIGURACION_SIMULACION_SANTA')")
  public ApiResponse<SimulacionResponse> simulacion(
      @PathVariable Long id, @RequestParam(required = false) Integer envio) {
    return configuracionService.simulacion(id, envio);
  }

  /** La simulación con la configuración del cuerpo, sin guardarla. */
  @PostMapping("/{id}/simulacion/previsualizar")
  @PreAuthorize("hasAuthority('VER_CONFIGURACION_SIMULACION_SANTA')")
  public ApiResponse<SimulacionResponse> previsualizar(
      @PathVariable Long id,
      @RequestParam(required = false) Integer envio,
      @Valid @RequestBody ConfiguracionRequest req) {
    return configuracionService.previsualizar(id, envio, req);
  }

  @PutMapping("/{id}/envio")
  @PreAuthorize("hasAuthority('MODIFICAR_CONFIGURACION_SIMULACION_SANTA')")
  public ApiResponse<RegistroResponse> cambiarEnvio(
      @PathVariable Long id, @Valid @RequestBody EnvioRequest req) {
    return configuracionService.cambiarEnvio(id, req);
  }

  /** ApiError con el estado del archivo inválido y, si ninguna fila es válida, sus errores. */
  static ResponseEntity<ApiError> archivoInvalido(
      ArchivoInvalidoException error, HttpServletRequest peticion) {
    HttpStatus estado = error.estado();
    ApiError cuerpo =
        error.detalle() == null || error.detalle().isEmpty()
            ? ApiError.de(
                estado.value(), estado.getReasonPhrase(), error.getMessage(), peticion.getRequestURI())
            : ApiError.conCampos(
                estado.value(),
                estado.getReasonPhrase(),
                error.getMessage(),
                peticion.getRequestURI(),
                error.detalle());
    return ResponseEntity.status(estado).body(cuerpo);
  }
}
