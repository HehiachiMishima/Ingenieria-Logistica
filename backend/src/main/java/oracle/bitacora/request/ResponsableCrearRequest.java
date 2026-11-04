package oracle.bitacora.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta de responsable de un area (el id del area viene en la ruta). */
public record ResponsableCrearRequest(
        @NotBlank(message = "El nombre del responsable es obligatorio.")
        @Size(max = 50) String responsable) {
}
