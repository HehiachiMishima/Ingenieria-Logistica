package oracle.admin.request;

import jakarta.validation.constraints.NotNull;

/** Asignar un permiso a un rol. */
public record RolPermisoAsignarRequest(
        @NotNull(message = "El permisoId es obligatorio.")
        Long permisoId) {
}
