package oracle.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cambio de la propia contrasena: exige la actual y la nueva (dos veces). */
public record CambiarContrasenaRequest(
        @NotBlank(message = "La contrasena actual es obligatoria.")
        @Size(max = 100)
        String contrasenaActual,

        @NotBlank(message = "La contrasena nueva es obligatoria.")
        @Size(min = 10, max = 72, message = "La contrasena debe tener entre 10 y 72 caracteres.")
        String contrasenaNueva) {
}
