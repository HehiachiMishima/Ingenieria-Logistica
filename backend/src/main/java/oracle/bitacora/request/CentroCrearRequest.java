package oracle.bitacora.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta de centro. */
public record CentroCrearRequest(
        @NotBlank(message = "El nombre del centro es obligatorio.")
        @Size(max = 50) String centro) {
}
