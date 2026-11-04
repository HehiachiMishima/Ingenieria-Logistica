package oracle.bitacora.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta de tipo de incidencia de un sistema (el id del sistema viene en la ruta). */
public record TipoIncidenciaCrearRequest(
        @NotBlank(message = "El tipo de incidencia es obligatorio.")
        @Size(max = 50) String incidencia) {
}
