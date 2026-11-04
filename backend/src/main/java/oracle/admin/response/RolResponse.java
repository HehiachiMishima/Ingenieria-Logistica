package oracle.admin.response;

import java.time.Instant;
import oracle.admin.model.Rol;

/** Salida publica de rol. */
public record RolResponse(
        Long id,
        String nombre,
        String descripcion,
        Boolean activo,
        Instant creadoEn,
        Instant actualizadoEn,
        Long creadoPor,
        Long actualizadoPor) {

    public static RolResponse of(Rol r) {
        return new RolResponse(
                r.getId(), r.getNombre(), r.getDescripcion(), r.getActivo(), r.getCreadoEn(), r.getActualizadoEn(),
                r.getCreadoPor(), r.getActualizadoPor());
    }
}
