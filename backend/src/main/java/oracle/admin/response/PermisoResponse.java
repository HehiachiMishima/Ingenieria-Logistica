package oracle.admin.response;

import java.time.Instant;
import oracle.admin.model.Permiso;

/** Salida publica de permiso. */
public record PermisoResponse(
        Long id,
        String nombre,
        String descripcion,
        Boolean activo,
        Instant creadoEn,
        Instant actualizadoEn,
        Long creadoPor,
        Long actualizadoPor) {

    public static PermisoResponse of(Permiso p) {
        return new PermisoResponse(
                p.getId(), p.getNombre(), p.getDescripcion(), p.getActivo(), p.getCreadoEn(), p.getActualizadoEn(),
                p.getCreadoPor(), p.getActualizadoPor());
    }
}
