package oracle.admin.response;

import java.util.List;

/**
 * Tablero «roles con este permiso»: el permiso, si quien edita puede darlo (lo tiene), a cuántos
 * usuarios llega hoy por sus roles activos y el catálogo de roles.
 */
public record RolesPermisoTableroResponse(
        Long permisoId,
        String nombre,
        String descripcion,
        boolean activo,
        boolean otorgable,
        int usuarios,
        List<RolDelPermisoResponse> roles) {
}
