package oracle.admin.controller;

import oracle.common.response.ApiResponse;
import oracle.admin.response.PermisoEfectivoResponse;
import oracle.admin.service.ResolucionPermisosService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Vista de resolucion de permisos: como se calcula lo efectivo/final. */
@RestController
@RequestMapping("/api/resolucion/usuarios/{usuarioId}")
@PreAuthorize("hasAuthority('VER_USUARIOS') and hasAuthority('VER_PERMISOS')")
public class ResolucionController {

    private final ResolucionPermisosService resolucionPermisosService;

    public ResolucionController(ResolucionPermisosService resolucionPermisosService) {
        this.resolucionPermisosService = resolucionPermisosService;
    }

    @GetMapping("/efectivos")
    public ApiResponse<List<PermisoEfectivoResponse>> efectivos(@PathVariable Long usuarioId) {
        return resolucionPermisosService.efectivos(usuarioId);
    }
}
