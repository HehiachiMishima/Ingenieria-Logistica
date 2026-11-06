package oracle.admin.controller;

import jakarta.validation.Valid;
import oracle.admin.request.CambiarEstadoRequest;
import oracle.admin.request.RolActualizarRequest;
import oracle.admin.request.RolCrearRequest;
import oracle.common.response.ApiResponse;
import oracle.admin.response.RolResponse;
import oracle.admin.service.RolService;
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

/** CRUD de roles; incluye los permisos concedidos al rol. */
@RestController
@RequestMapping("/api/roles")
public class RolController {

    private final RolService rolService;

    public RolController(RolService rolService) {
        this.rolService = rolService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VER_ROLES')")
    public ApiResponse<List<RolResponse>> listar() {
        return rolService.listar();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CREAR_ROLES')")
    public ApiResponse<RolResponse> crear(@Valid @RequestBody RolCrearRequest peticion) {
        return rolService.crear(peticion);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODIFICAR_ROLES')")
    public ApiResponse<RolResponse> actualizar(@PathVariable Long id,
            @Valid @RequestBody RolActualizarRequest peticion) {
        return rolService.actualizar(id, peticion);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ELIMINAR_ROLES')")
    public ApiResponse<Void> eliminar(@PathVariable Long id) {
        return rolService.eliminar(id);
    }

    @PatchMapping("/{id}/activo")
    @PreAuthorize("hasAuthority('MODIFICAR_ROLES')")
    public ApiResponse<RolResponse> cambiarActivo(@PathVariable Long id,
            @Valid @RequestBody CambiarEstadoRequest peticion) {
        return rolService.cambiarActivo(id, peticion);
    }
}
