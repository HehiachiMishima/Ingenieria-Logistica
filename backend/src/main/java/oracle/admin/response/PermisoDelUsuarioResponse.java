package oracle.admin.response;

/**
 * Permiso del catálogo visto desde un usuario: si alguno de sus roles activos lo concede, la
 * excepción que tiene (TRUE concede, FALSE deniega, null = hereda del rol) y si quien edita
 * puede concederlo (lo tiene permitido).
 */
public record PermisoDelUsuarioResponse(
        Long id,
        String nombre,
        String descripcion,
        boolean activo,
        boolean porRol,
        Boolean excepcion,
        boolean otorgable) {
}
