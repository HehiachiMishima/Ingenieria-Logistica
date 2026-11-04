package oracle.admin.request;

import jakarta.validation.constraints.NotNull;

/** Asignar un rol a un usuario. */
public record UsuarioRolAsignarRequest(
        @NotNull(message = "El rolId es obligatorio.")
        Long rolId) {
}
