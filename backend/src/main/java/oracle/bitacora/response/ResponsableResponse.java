package oracle.bitacora.response;

import java.time.Instant;
import oracle.bitacora.model.DataResponsable;

/** Salida publica de responsable de un area. */
public record ResponsableResponse(
        Long id,
        Long idDataArea,
        String responsable,
        Instant creadoEn,
        Instant actualizadoEn,
        Long creadoPor,
        Long actualizadoPor) {

    public static ResponsableResponse of(DataResponsable r) {
        return new ResponsableResponse(
                r.getId(), r.getIdDataArea(), r.getResponsable(), r.getCreadoEn(), r.getActualizadoEn(),
                r.getCreadoPor(), r.getActualizadoPor());
    }
}
