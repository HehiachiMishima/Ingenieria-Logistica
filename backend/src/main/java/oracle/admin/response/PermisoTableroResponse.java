package oracle.admin.response;

/**
 * Permiso del catálogo visto desde un rol: si el rol lo concede y en cuántos
 * roles está (para la ficha del tablero de permisos).
 */
public record PermisoTableroResponse(
        Long id,
        String nombre,
        String descripcion,
        boolean activo,
        boolean asignado,
        int roles) {
}
