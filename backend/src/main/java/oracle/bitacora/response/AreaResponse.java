package oracle.bitacora.response;

import java.time.Instant;
import oracle.bitacora.model.DataArea;

/** Salida publica de area. */
public record AreaResponse(
        Long id,
        String area,
        Instant creadoEn,
        Instant actualizadoEn,
        Long creadoPor,
        Long actualizadoPor) {

    public static AreaResponse of(DataArea a) {
        return new AreaResponse(
                a.getId(), a.getArea(), a.getCreadoEn(), a.getActualizadoEn(),
                a.getCreadoPor(), a.getActualizadoPor());
    }
}
