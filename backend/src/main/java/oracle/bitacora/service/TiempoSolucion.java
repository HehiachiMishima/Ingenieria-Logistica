package oracle.bitacora.service;

import java.time.Duration;
import java.time.LocalTime;

/** Regla pura del tiempo de solucion de una incidencia (sin acceso a datos). */
final class TiempoSolucion {

    private static final long SEGUNDOS_DIA = 86_400L;

    /**
     * Segundos entre la hora de inicio y la de fin. Si fin es anterior a inicio
     * la incidencia cruzo la medianoche (+24 h). Null si falta una hora.
     */
    static Integer segundos(LocalTime inicio, LocalTime fin) {
        if (inicio == null || fin == null) {
            return null;
        }
        long segundos = Duration.between(inicio, fin).getSeconds();
        if (segundos < 0) {
            segundos += SEGUNDOS_DIA;
        }
        return segundos >= SEGUNDOS_DIA ? null : (int) segundos;
    }

    private TiempoSolucion() {
    }
}
