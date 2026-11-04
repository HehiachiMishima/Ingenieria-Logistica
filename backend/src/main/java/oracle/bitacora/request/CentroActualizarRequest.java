package oracle.bitacora.request;

import jakarta.validation.constraints.Size;

/** Actualizacion parcial de centro: campos null = no se tocan. */
public record CentroActualizarRequest(
        @Size(max = 50) String centro) {
}
