package oracle.admin.response;

import java.util.List;

/**
 * Rol del catálogo visto desde un usuario: si ya lo tiene, cuántos permisos trae, cuántos
 * usuarios lo tienen, a qué paneles da entrada (permisos PANEL_* activos) y si quien edita
 * puede otorgarlo (tiene todos sus permisos).
 */
public record RolTableroResponse(
        Long id,
        String nombre,
        String descripcion,
        boolean activo,
        boolean asignado,
        int permisos,
        int usuarios,
        List<String> paneles,
        boolean otorgable) {
}
