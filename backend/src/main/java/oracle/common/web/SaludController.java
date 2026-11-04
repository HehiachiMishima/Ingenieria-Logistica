package oracle.common.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de salud publico (sin autenticacion) que usa el health check
 * del despliegue (Render) para confirmar que la aplicacion esta en pie.
 */
@RestController
@RequestMapping("/api")
public class SaludController {

    @GetMapping("/health")
    public ResponseEntity<Void> salud() {
        return ResponseEntity.noContent().build();
    }
}
