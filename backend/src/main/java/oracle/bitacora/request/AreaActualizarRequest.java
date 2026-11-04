package oracle.bitacora.request;

import jakarta.validation.constraints.Size;

/** Actualizacion parcial de area: campos null = no se tocan. */
public record AreaActualizarRequest(
        @Size(max = 50) String area) {
}
