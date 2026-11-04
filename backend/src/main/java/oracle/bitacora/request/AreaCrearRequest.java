package oracle.bitacora.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta de area. */
public record AreaCrearRequest(
        @NotBlank(message = "El nombre del area es obligatorio.")
        @Size(max = 50) String area) {
}
