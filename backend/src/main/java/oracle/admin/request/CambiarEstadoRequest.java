package oracle.admin.request;

import jakarta.validation.constraints.NotNull;

/** Conmutador de estado activo (kill switch) para usuario, rol o permiso. */
public record CambiarEstadoRequest(
        @NotNull(message = "El estado activo es obligatorio.")
        Boolean activo) {
}
