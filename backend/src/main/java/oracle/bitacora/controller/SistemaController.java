package oracle.bitacora.controller;

import jakarta.validation.Valid;
import oracle.bitacora.request.SistemaActualizarRequest;
import oracle.bitacora.request.SistemaCrearRequest;
import oracle.bitacora.request.TipoIncidenciaActualizarRequest;
import oracle.bitacora.request.TipoIncidenciaCrearRequest;
import oracle.common.response.ApiResponse;
import oracle.bitacora.response.SistemaResponse;
import oracle.bitacora.response.TipoIncidenciaResponse;
import oracle.bitacora.service.SistemaService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** CRUD de sistemas + tipos de incidencia (hijos 1:N). */
@RestController
@RequestMapping("/api/sistemas")
public class SistemaController {

    private final SistemaService sistemaService;

    public SistemaController(SistemaService sistemaService) {
        this.sistemaService = sistemaService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VER_CONTROL')")
    public ApiResponse<List<SistemaResponse>> listar() {
        return sistemaService.listar();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CREAR_CONTROL')")
    public ApiResponse<SistemaResponse> crear(@Valid @RequestBody SistemaCrearRequest peticion) {
        return sistemaService.crear(peticion);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODIFICAR_CONTROL')")
    public ApiResponse<SistemaResponse> actualizar(@PathVariable Long id,
            @Valid @RequestBody SistemaActualizarRequest peticion) {
        return sistemaService.actualizar(id, peticion);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ELIMINAR_CONTROL')")
    public ApiResponse<Void> eliminar(@PathVariable Long id) {
        return sistemaService.eliminar(id);
    }

    @GetMapping("/{id}/tipos-incidencia")
    @PreAuthorize("hasAuthority('VER_CONTROL')")
    public ApiResponse<List<TipoIncidenciaResponse>> tiposDe(@PathVariable Long id) {
        return sistemaService.tiposDe(id);
    }

    @PostMapping("/{id}/tipos-incidencia")
    @PreAuthorize("hasAuthority('CREAR_CONTROL')")
    public ApiResponse<TipoIncidenciaResponse> crearTipo(@PathVariable Long id,
            @Valid @RequestBody TipoIncidenciaCrearRequest peticion) {
        return sistemaService.crearTipo(id, peticion);
    }

    @PutMapping("/{sistemaId}/tipos-incidencia/{tipoId}")
    @PreAuthorize("hasAuthority('MODIFICAR_CONTROL')")
    public ApiResponse<TipoIncidenciaResponse> actualizarTipo(@PathVariable Long sistemaId,
            @PathVariable Long tipoId, @Valid @RequestBody TipoIncidenciaActualizarRequest peticion) {
        return sistemaService.actualizarTipo(sistemaId, tipoId, peticion);
    }

    @DeleteMapping("/{sistemaId}/tipos-incidencia/{tipoId}")
    @PreAuthorize("hasAuthority('ELIMINAR_CONTROL')")
    public ApiResponse<Void> eliminarTipo(@PathVariable Long sistemaId, @PathVariable Long tipoId) {
        return sistemaService.eliminarTipo(sistemaId, tipoId);
    }
}
