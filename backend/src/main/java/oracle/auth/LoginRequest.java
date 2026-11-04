package oracle.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Credenciales de acceso: acepta usuario o email en el mismo campo. */
public record LoginRequest(
        @NotBlank(message = "El usuario o email es obligatorio.")
        @Size(max = 100)
        String usuario,

        @NotBlank(message = "La contrasena es obligatoria.")
        @Size(max = 100)
        String contrasena) {
}
