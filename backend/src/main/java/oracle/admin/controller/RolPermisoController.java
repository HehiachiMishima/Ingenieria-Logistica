package oracle.admin.controller;

import jakarta.validation.Valid;
import oracle.admin.request.RolPermisoAsignarRequest;
import oracle.common.response.ApiResponse;
import oracle.admin.response.PermisoResponse;
import oracle.admin.response.RolPermisoResponse;
import oracle.admin.response.RolResponse;
import oracle.admin.service.RolPermisoService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/** Asignacion de permisos a roles y consulta inversa. */
@RestController
@RequestMapping("/api")
public class RolPermisoController {

    private final RolPermisoService rolPermisoService;

    public RolPermisoController(RolPermisoService rolPermisoService) {
        this.rolPermisoService = rolPermisoService;
    }

    @GetMapping("/roles/{rolId}/permisos")
    @PreAuthorize("hasAuthority('VER_ROLES') and hasAuthority('VER_PERMISOS')")
    public Mono<ApiResponse<List<PermisoResponse>>> permisosDeRol(@PathVariable Long rolId) {
        return rolPermisoService.listarPorRol(rolId);
    }

    @PostMapping("/roles/{rolId}/permisos")
    @PreAuthorize("hasAuthority('ASIGNAR_PERMISOS')")
    public Mono<ApiResponse<RolPermisoResponse>> asignar(@PathVariable Long rolId,
            @Valid @RequestBody RolPermisoAsignarRequest peticion) {
        return rolPermisoService.asignar(rolId, peticion);
    }

    @DeleteMapping("/roles/{rolId}/permisos/{permisoId}")
    @PreAuthorize("hasAuthority('ASIGNAR_PERMISOS')")
    public Mono<ApiResponse<Void>> quitar(@PathVariable Long rolId, @PathVariable Long permisoId) {
        return rolPermisoService.quitar(rolId, permisoId);
    }

    @GetMapping("/permisos/{permisoId}/roles")
    @PreAuthorize("hasAuthority('VER_PERMISOS') and hasAuthority('VER_ROLES')")
    public Mono<ApiResponse<List<RolResponse>>> rolesConPermiso(@PathVariable Long permisoId) {
        return rolPermisoService.listarPorPermiso(permisoId);
    }
}
