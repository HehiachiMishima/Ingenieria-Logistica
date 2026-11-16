package oracle.bitacora.response;

import oracle.bitacora.request.EstadoIncidencia;
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
        /** Fecha + hora de fin (instante); null = abierta. */
        Instant cerradoEn,
        /** Deducido de cerradoEn (no se guarda). */
        EstadoIncidencia estado,
        Instant creadoEn,
        Long creadoPor,
        /**
         * Ficha de concurrencia (opaca): el cliente la devuelve al editar y, si
         * otra persona guardo antes, la edicion se rechaza con 409. Es
         * actualizado_en, que no se muestra pero se mantiene en cada cambio.
         */
        Instant version) {

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
                i.getCerradoEn(), EstadoIncidencia.de(i.getCerradoEn()),
                i.getCreadoEn(),
                i.getCreadoPor(),
                i.getActualizadoEn());
    }
}
