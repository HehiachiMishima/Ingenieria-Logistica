package oracle.admin.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Actualizacion parcial de usuario: campos null = no se tocan.
 * Se valida regla de negocio de unicidad en el service.
 */
public record UsuarioActualizarRequest(
        @Size(max = 50) String usuario,
        @Size(min = 10, max = 72) String contrasena,
        @Size(max = 100) String nombre,
        @Size(max = 100) String apellido,
        @Email @Size(max = 100) String email,
        @Size(max = 20) String telefono,
        Boolean activo) {
}
