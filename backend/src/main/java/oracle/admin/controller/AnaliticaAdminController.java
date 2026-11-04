package oracle.admin.controller;

import oracle.admin.response.AnaliticaAdminResponse;
import oracle.admin.service.AnaliticaAdminService;
import oracle.common.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Dashboard ejecutivo del panel Administracion. */
@RestController
@RequestMapping("/api/admin")
public class AnaliticaAdminController {

    private final AnaliticaAdminService service;

    public AnaliticaAdminController(AnaliticaAdminService service) {
        this.service = service;
    }

    @GetMapping("/analitica")
    @PreAuthorize("hasAuthority('DASHBOARD_ADMIN')")
    public Mono<ApiResponse<AnaliticaAdminResponse>> analitica() {
        return service.analitica();
    }
}
