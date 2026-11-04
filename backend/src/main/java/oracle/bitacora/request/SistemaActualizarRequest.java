package oracle.bitacora.request;

import jakarta.validation.constraints.Size;

/** Actualizacion parcial de sistema: campos null = no se tocan. */
public record SistemaActualizarRequest(
        @Size(max = 50) String sistema) {
}
