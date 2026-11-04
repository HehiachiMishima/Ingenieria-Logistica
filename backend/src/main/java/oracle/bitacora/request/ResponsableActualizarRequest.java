package oracle.bitacora.request;

import jakarta.validation.constraints.Size;

/** Actualizacion parcial de responsable: campos null = no se tocan. */
public record ResponsableActualizarRequest(
        @Size(max = 50) String responsable) {
}
