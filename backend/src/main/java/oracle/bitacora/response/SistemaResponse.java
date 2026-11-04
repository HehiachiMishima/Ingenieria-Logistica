package oracle.bitacora.response;

import java.time.Instant;
import oracle.bitacora.model.DataSistema;

/** Salida publica de sistema. */
public record SistemaResponse(
        Long id,
        String sistema,
        Instant creadoEn,
        Instant actualizadoEn,
        Long creadoPor,
        Long actualizadoPor) {

    public static SistemaResponse of(DataSistema s) {
        return new SistemaResponse(
                s.getId(), s.getSistema(), s.getCreadoEn(), s.getActualizadoEn(),
                s.getCreadoPor(), s.getActualizadoPor());
    }
}
