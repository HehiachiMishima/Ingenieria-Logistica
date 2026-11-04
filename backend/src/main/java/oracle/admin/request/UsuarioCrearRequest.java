package oracle.admin.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta de usuario. */
public record UsuarioCrearRequest(
        @NotBlank(message = "El usuario es obligatorio.")
        @Size(max = 50, message = "El usuario no puede exceder 50 caracteres.")
        String usuario,

        @NotBlank(message = "La contrasena es obligatoria.")
        @Size(min = 10, max = 72, message = "La contrasena debe tener entre 10 y 72 caracteres.")
        String contrasena,

        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 100)
        String nombre,

        @NotBlank(message = "El apellido es obligatorio.")
        @Size(max = 100)
        String apellido,

        @NotBlank(message = "El email es obligatorio.")
        @Email(message = "El email no es valido.")
        @Size(max = 100)
        String email,

        @Size(max = 20, message = "El telefono no puede exceder 20 caracteres.")
        String telefono,

        Boolean activo) {
}
