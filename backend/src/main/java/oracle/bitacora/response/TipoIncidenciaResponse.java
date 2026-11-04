package oracle.bitacora.response;

import java.time.Instant;
import oracle.bitacora.model.DataIncidencia;

/** Salida publica de tipo de incidencia de un sistema. */
public record TipoIncidenciaResponse(
        Long id,
        Long idDataSistema,
        String incidencia,
        Instant creadoEn,
        Instant actualizadoEn,
        Long creadoPor,
        Long actualizadoPor) {

    public static TipoIncidenciaResponse of(DataIncidencia d) {
        return new TipoIncidenciaResponse(
                d.getId(), d.getIdDataSistema(), d.getIncidencia(), d.getCreadoEn(), d.getActualizadoEn(),
                d.getCreadoPor(), d.getActualizadoPor());
    }
}
