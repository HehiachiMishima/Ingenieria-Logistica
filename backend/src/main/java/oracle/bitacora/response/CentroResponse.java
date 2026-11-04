package oracle.bitacora.response;

import java.time.Instant;
import oracle.bitacora.model.DataCentro;

/** Salida publica de centro. */
public record CentroResponse(
        Long id,
        String centro,
        Instant creadoEn,
        Instant actualizadoEn,
        Long creadoPor,
        Long actualizadoPor) {

    public static CentroResponse of(DataCentro c) {
        return new CentroResponse(
                c.getId(), c.getCentro(), c.getCreadoEn(), c.getActualizadoEn(),
                c.getCreadoPor(), c.getActualizadoPor());
    }
}
