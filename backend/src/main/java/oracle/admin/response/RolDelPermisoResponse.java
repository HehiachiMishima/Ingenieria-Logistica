package oracle.admin.response;

import java.util.List;

/**
 * Rol del catálogo visto desde un permiso: si ya lo concede, cuántos permisos trae, cuántos
 * usuarios lo tienen, a qué paneles da entrada y si está protegido (ADMINISTRADOR: no se le quitan
 * permisos).
 */
public record RolDelPermisoResponse(
        Long id,
        String nombre,
        String descripcion,
        boolean activo,
        boolean asignado,
        int permisos,
        int usuarios,
        List<String> paneles,
        boolean protegido) {
}
