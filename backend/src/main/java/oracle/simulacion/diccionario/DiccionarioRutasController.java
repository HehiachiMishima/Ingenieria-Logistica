package oracle.simulacion.diccionario;

import jakarta.validation.Valid;
import oracle.common.response.ApiResponse;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Cambio;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Detalle;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Diccionario;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Ruta;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registro de Simulación Titan: diccionario de rutas (reemplaza al registro por Excel). Usa los
 * permisos del registro: ver, crear, modificar y eliminar.
 */
@RestController
@RequestMapping("/api/simulacion/diccionario")
public class DiccionarioRutasController {

  private final DiccionarioRutasService servicio;

  public DiccionarioRutasController(DiccionarioRutasService servicio) {
    this.servicio = servicio;
  }

  /** Clusters y rutas (con totales y códigos) para dibujar los bloques. */
  @GetMapping
  @PreAuthorize("hasAuthority('VER_REGISTRO_SIMULACION')")
  public ApiResponse<Diccionario> ver() {
    return servicio.ver();
  }

  /** Una ruta con todos sus locales (ficha de detalle y edición). */
  @GetMapping("/rutas/{id}")
  @PreAuthorize("hasAuthority('VER_REGISTRO_SIMULACION')")
  public ApiResponse<Detalle> detalle(@PathVariable Long id) {
    return servicio.detalle(id);
  }

  @PostMapping("/rutas")
  @PreAuthorize("hasAuthority('CREAR_REGISTRO_SIMULACION')")
  public ApiResponse<Ruta> crear(@Valid @RequestBody DiccionarioPeticiones.Ruta req) {
    return servicio.crear(req);
  }

  @PutMapping("/rutas/{id}")
  @PreAuthorize("hasAuthority('MODIFICAR_REGISTRO_SIMULACION')")
  public ApiResponse<Ruta> actualizar(@PathVariable Long id, @Valid @RequestBody DiccionarioPeticiones.Ruta req) {
    return servicio.actualizar(id, req);
  }

  @DeleteMapping("/rutas/{id}")
  @PreAuthorize("hasAuthority('ELIMINAR_REGISTRO_SIMULACION')")
  public ApiResponse<Void> eliminar(@PathVariable Long id) {
    return servicio.eliminar(id);
  }

  /** Arrastre: a un cluster (conservan frecuencia) o a Datos ({@code clusterId} null: la pierden). */
  @PutMapping("/rutas/mover")
  @PreAuthorize("hasAuthority('MODIFICAR_REGISTRO_SIMULACION')")
  public ApiResponse<Cambio> mover(@Valid @RequestBody DiccionarioPeticiones.Mover req) {
    return servicio.mover(req);
  }

  @PutMapping("/rutas/frecuencia")
  @PreAuthorize("hasAuthority('MODIFICAR_REGISTRO_SIMULACION')")
  public ApiResponse<Cambio> asignarFrecuencia(@Valid @RequestBody DiccionarioPeticiones.Frecuencia req) {
    return servicio.asignarFrecuencia(req);
  }

  @PutMapping("/rutas/grupo")
  @PreAuthorize("hasAuthority('MODIFICAR_REGISTRO_SIMULACION')")
  public ApiResponse<Cambio> asignarGrupo(@Valid @RequestBody DiccionarioPeticiones.Grupo req) {
    return servicio.asignarGrupo(req);
  }

  @PutMapping("/rutas/tipo")
  @PreAuthorize("hasAuthority('MODIFICAR_REGISTRO_SIMULACION')")
  public ApiResponse<Cambio> cambiarTipo(@Valid @RequestBody DiccionarioPeticiones.Tipo req) {
    return servicio.cambiarTipo(req);
  }

  /** Deshacer el último movimiento. */
  @PutMapping("/rutas/restaurar")
  @PreAuthorize("hasAuthority('MODIFICAR_REGISTRO_SIMULACION')")
  public ApiResponse<Cambio> restaurar(@Valid @RequestBody DiccionarioPeticiones.Restaurar req) {
    return servicio.restaurar(req);
  }

  /** Cuántos bloques de cluster hay (CL1…CLn); al quitar, sus rutas vuelven a Datos. */
  @PutMapping("/clusters")
  @PreAuthorize("hasAuthority('MODIFICAR_REGISTRO_SIMULACION')")
  public ApiResponse<Diccionario> cambiarClusters(@Valid @RequestBody DiccionarioPeticiones.Clusters req) {
    return servicio.cambiarClusters(req);
  }
}
