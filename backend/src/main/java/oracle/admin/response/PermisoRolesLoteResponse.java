package oracle.admin.response;

import java.util.List;

/**
 * Resultado de dar o quitar un permiso a varios roles: cuántos roles cambiaron de verdad, los
 * roles que lo tienen ahora y a cuántos usuarios llega por esos roles.
 */
public record PermisoRolesLoteResponse(
        Long permisoId,
        int agregados,
        int quitados,
        List<Long> asignados,
        int usuarios) {
}
