package oracle.admin.controller;

import jakarta.validation.Valid;
import oracle.admin.request.CambiarEstadoRequest;
import oracle.admin.request.UsuarioActualizarRequest;
import oracle.admin.request.UsuarioCrearRequest;
import oracle.common.response.ApiResponse;
import oracle.admin.response.ActorResponse;
import oracle.admin.response.UsuarioResponse;
import oracle.admin.response.UsuarioResumenResponse;
import oracle.admin.service.UsuarioService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** CRUD de usuarios. Cada endpoint exige su permiso. */
@RestController
@RequestMapping("/api/usuarios")
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VER_USUARIOS')")
    public ApiResponse<List<UsuarioResponse>> listar() {
        return usuarioService.listar();
    }

    /**
     * Login de quien creó o modificó registros (?ids=1,12). Basta con estar
     * autenticado: lo usan todos los paneles para sus columnas de auditoría.
     */
    @GetMapping("/actores")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<ActorResponse>> actores(@RequestParam List<Long> ids) {
        return usuarioService.actores(ids);
    }

    /** Roles y permisos efectivos de todos los usuarios para el listado (evita N+1 peticiones). */
    @GetMapping("/resumen")
    @PreAuthorize("hasAuthority('VER_USUARIOS') and hasAuthority('VER_ROLES') and hasAuthority('VER_PERMISOS')")
    public ApiResponse<List<UsuarioResumenResponse>> resumen() {
        return usuarioService.resumen();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CREAR_USUARIOS')")
    public ApiResponse<UsuarioResponse> crear(@Valid @RequestBody UsuarioCrearRequest peticion) {
        return usuarioService.crear(peticion);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODIFICAR_USUARIOS')")
    public ApiResponse<UsuarioResponse> actualizar(@PathVariable Long id,
            @Valid @RequestBody UsuarioActualizarRequest peticion) {
        return usuarioService.actualizar(id, peticion);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ELIMINAR_USUARIOS')")
    public ApiResponse<Void> eliminar(@PathVariable Long id) {
        return usuarioService.eliminar(id);
    }

    @PatchMapping("/{id}/activo")
    @PreAuthorize("hasAuthority('MODIFICAR_USUARIOS')")
    public ApiResponse<UsuarioResponse> cambiarActivo(@PathVariable Long id,
            @Valid @RequestBody CambiarEstadoRequest peticion) {
        return usuarioService.cambiarActivo(id, peticion);
    }
}
