package oracle.bitacora.request;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Cierre de una incidencia abierta. Fecha y hora de fin en hora del negocio;
 * ambas vacías = ahora. La fecha sola no es válida.
 */
public record CerrarIncidenciaRequest(LocalDate fechaFin, LocalTime horaFin) {
}
