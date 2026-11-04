package oracle.bitacora.response;

import java.time.Instant;
import java.time.LocalTime;
import oracle.bitacora.model.Incidencia;

/**
 * Salida pública de incidencia con los nombres de los catálogos ya resueltos
 * para que el cliente no tenga que cruzarlos.
 */
public record IncidenciaResponse(
        Long id,
        Long idDataCentro,
        String centro,
        Long idDataSistema,
        String sistema,
        Long idDataIncidencia,
        String tipoIncidencia,
        String ticket,
        Long idDataArea,
        String area,
        Long idDataResponsable,
        String responsable,
        String descripcion,
        LocalTime horaInicio,
        LocalTime horaFin,
        Integer tiempoSolucionSeg,
        Instant creadoEn,
        Instant actualizadoEn,
        Long creadoPor,
        Long actualizadoPor) {

    public static IncidenciaResponse of(Incidencia i,
            String centro,
            String sistema,
            String tipoIncidencia,
            String area,
            String responsable) {
        return new IncidenciaResponse(
                i.getId(),
                i.getIdDataCentro(), centro,
                i.getIdDataSistema(), sistema,
                i.getIdDataIncidencia(), tipoIncidencia,
                i.getTicket(),
                i.getIdDataArea(), area,
                i.getIdDataResponsable(), responsable,
                i.getDescripcion(),
                i.getHoraInicio(), i.getHoraFin(), i.getTiempoSolucionSeg(),
                i.getCreadoEn(), i.getActualizadoEn(),
                i.getCreadoPor(), i.getActualizadoPor());
    }
}
