package oracle.bitacora.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta de sistema. */
public record SistemaCrearRequest(
        @NotBlank(message = "El nombre del sistema es obligatorio.")
        @Size(max = 50) String sistema) {
}
