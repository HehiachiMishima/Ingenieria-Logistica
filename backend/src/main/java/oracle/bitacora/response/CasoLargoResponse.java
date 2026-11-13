package oracle.bitacora.response;

import java.time.LocalDate;
import java.time.LocalTime;

/** Incidencia resuelta con su duracion, para el detalle del cuadro ejecutivo. */
public record CasoLargoResponse(
        String ticket,
        String descripcion,
        String tipo,
        String centro,
        String responsable,
        LocalDate fecha,
        LocalTime horaInicio,
        LocalTime horaFin,
        long segundos) {
}
