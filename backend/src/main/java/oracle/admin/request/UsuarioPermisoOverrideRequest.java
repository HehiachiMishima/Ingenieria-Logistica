package oracle.admin.request;

import jakarta.validation.constraints.NotNull;

/**
 * Override individual sobre un permiso: otorgado=true (grant) o false (deny).
 * Resuelve por MIN(otorgado) => deny-overrides.
 */
public record UsuarioPermisoOverrideRequest(
        @NotNull(message = "El permisoId es obligatorio.")
        Long permisoId,

        @NotNull(message = "El campo otorgado (true=grant/false=deny) es obligatorio.")
        Boolean otorgado) {
}
