package oracle.admin.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta de rol. */
public record RolCrearRequest(
        @NotBlank(message = "El nombre del rol es obligatorio.")
        @Size(max = 50)
        String nombre,
        @Size(max = 200) String descripcion,
        Boolean activo) {
}
