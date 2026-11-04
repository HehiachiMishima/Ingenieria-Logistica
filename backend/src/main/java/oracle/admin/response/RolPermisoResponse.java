package oracle.admin.response;

import java.time.Instant;
import oracle.admin.model.RolPermiso;

/** Salida de la plantilla rol-permiso. */
public record RolPermisoResponse(
        Long rolId,
        String rolNombre,
        Long permisoId,
        String permisoNombre,
        Instant creadoEn) {

    public static RolPermisoResponse of(RolPermiso rp) {
        return new RolPermisoResponse(
                rp.getRol().getId(),
                rp.getRol().getNombre(),
                rp.getPermiso().getId(),
                rp.getPermiso().getNombre(),
                rp.getCreadoEn());
    }
}
