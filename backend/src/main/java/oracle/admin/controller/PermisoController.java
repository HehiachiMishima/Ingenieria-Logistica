package oracle.admin.controller;

import jakarta.validation.Valid;
import oracle.admin.request.CambiarEstadoRequest;
import oracle.admin.request.PermisoActualizarRequest;
import oracle.admin.request.PermisoCrearRequest;
import oracle.common.response.ApiResponse;
import oracle.admin.response.PermisoResponse;
import oracle.admin.service.PermisoService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** CRUD de permisos (catalogo). */
@RestController
@RequestMapping("/api/permisos")
public class PermisoController {

    private final PermisoService permisoService;

    public PermisoController(PermisoService permisoService) {
        this.permisoService = permisoService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VER_PERMISOS')")
    public ApiResponse<List<PermisoResponse>> listar() {
        return permisoService.listar();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CREAR_PERMISOS')")
    public ApiResponse<PermisoResponse> crear(@Valid @RequestBody PermisoCrearRequest peticion) {
        return permisoService.crear(peticion);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODIFICAR_PERMISOS')")
    public ApiResponse<PermisoResponse> actualizar(@PathVariable Long id,
            @Valid @RequestBody PermisoActualizarRequest peticion) {
        return permisoService.actualizar(id, peticion);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ELIMINAR_PERMISOS')")
    public ApiResponse<Void> eliminar(@PathVariable Long id) {
        return permisoService.eliminar(id);
    }

    @PatchMapping("/{id}/activo")
    @PreAuthorize("hasAuthority('MODIFICAR_PERMISOS')")
    public ApiResponse<PermisoResponse> cambiarActivo(@PathVariable Long id,
            @Valid @RequestBody CambiarEstadoRequest peticion) {
        return permisoService.cambiarActivo(id, peticion);
    }
}
