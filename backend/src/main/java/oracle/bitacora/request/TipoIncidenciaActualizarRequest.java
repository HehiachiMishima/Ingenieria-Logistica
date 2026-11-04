package oracle.bitacora.request;

import jakarta.validation.constraints.Size;

/** Actualizacion parcial de tipo de incidencia: campos null = no se tocan. */
public record TipoIncidenciaActualizarRequest(
        @Size(max = 50) String incidencia) {
}
