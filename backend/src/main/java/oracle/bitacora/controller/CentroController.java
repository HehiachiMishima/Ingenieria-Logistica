package oracle.bitacora.controller;

import jakarta.validation.Valid;
import oracle.bitacora.request.CentroActualizarRequest;
import oracle.bitacora.request.CentroCrearRequest;
import oracle.common.response.ApiResponse;
import oracle.bitacora.response.CentroResponse;
import oracle.bitacora.service.CentroService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/** CRUD de centros. */
@RestController
@RequestMapping("/api/centros")
public class CentroController {

    private final CentroService centroService;

    public CentroController(CentroService centroService) {
        this.centroService = centroService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VER_CONTROL')")
    public Mono<ApiResponse<List<CentroResponse>>> listar() {
        return centroService.listar();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CREAR_CONTROL')")
    public Mono<ApiResponse<CentroResponse>> crear(@Valid @RequestBody CentroCrearRequest peticion) {
        return centroService.crear(peticion);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODIFICAR_CONTROL')")
    public Mono<ApiResponse<CentroResponse>> actualizar(@PathVariable Long id,
            @Valid @RequestBody CentroActualizarRequest peticion) {
        return centroService.actualizar(id, peticion);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ELIMINAR_CONTROL')")
    public Mono<ApiResponse<Void>> eliminar(@PathVariable Long id) {
        return centroService.eliminar(id);
    }
}
