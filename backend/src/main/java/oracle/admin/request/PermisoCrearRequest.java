package oracle.admin.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta de permiso. */
public record PermisoCrearRequest(
        @NotBlank(message = "El nombre del permiso es obligatorio.")
        @Size(max = 50)
        String nombre,
        @Size(max = 200) String descripcion,
        Boolean activo) {
}
