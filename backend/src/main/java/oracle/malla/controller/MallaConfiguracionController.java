package oracle.malla.controller;

import jakarta.validation.Valid;
import oracle.common.response.ApiResponse;
import oracle.malla.request.MallaReglaActualizarRequest;
import oracle.malla.response.EvaluacionMallaResponse;
import oracle.malla.response.ReglaMallaResponse;
import oracle.malla.service.MallaConfiguracionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Validación de la Malla Operativa: evaluación de las reglas (de todos los
 * centros o de uno) y ajuste de sus parámetros. Ver exige {@code VER_MALLA};
 * cambiar una regla, {@code CONFIGURAR_REGLAS_MALLA}.
 */
@RestController
@RequestMapping("/api/malla/configuracion")
public class MallaConfiguracionController {

    private final MallaConfiguracionService service;

    public MallaConfiguracionController(MallaConfiguracionService service) {
        this.service = service;
    }

    @GetMapping("/evaluacion")
    @PreAuthorize("hasAuthority('VER_MALLA')")
    public ApiResponse<EvaluacionMallaResponse> evaluacion(
            @RequestParam(required = false) Long centroId) {
        return service.evaluar(centroId);
    }

    /** Activar o ajustar reglas cambia qué se considera correcto: tiene su propio permiso. */
    @PutMapping("/reglas/{id}")
    @PreAuthorize("hasAuthority('CONFIGURAR_REGLAS_MALLA')")
    public ApiResponse<ReglaMallaResponse> actualizar(@PathVariable Long id,
            @Valid @RequestBody MallaReglaActualizarRequest req) {
        return service.actualizar(id, req);
    }
}
