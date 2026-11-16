package oracle.bitacora.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import oracle.common.exception.SolicitudInvalidaException;

/**
 * Regla pura del fin de una incidencia (sin acceso a datos ni a la zona: recibe
 * los instantes ya convertidos).
 *
 * - Sin fecha ni hora de fin: abierta (cierre y duración nulos).
 * - Con hora y sin fecha: la fecha se deduce como siempre (fin anterior al
 *   inicio = cruzó la medianoche, día siguiente). Mantiene compatibles a los
 *   clientes que solo envían la hora.
 * - Fecha sin hora: inválido.
 * - Ni el inicio ni el fin pueden estar en el futuro (con una tolerancia por
 *   relojes desfasados) y el fin no puede ser anterior al inicio.
 */
final class CierreIncidencia {

    /** Margen ante relojes de cliente/servidor desfasados. */
    static final Duration TOLERANCIA_FUTURO = Duration.ofMinutes(5);

    /** Resultado: instante de cierre y duración en segundos (ambos nulos si está abierta). */
    record Cierre(Instant cerradoEn, Integer segundos) {
        static final Cierre ABIERTA = new Cierre(null, null);

        boolean abierta() {
            return cerradoEn == null;
        }
    }

    /** Fecha del fin: la indicada o la deducida de las horas. Null si no hay hora de fin. */
    static LocalDate fechaFin(LocalDate fechaInicio, LocalTime horaInicio, LocalDate fechaFin, LocalTime horaFin) {
        if (horaFin == null) {
            if (fechaFin != null) {
                throw new SolicitudInvalidaException("Indique la hora de fin o deje vacía también la fecha de fin.");
            }
            return null;
        }
        if (fechaFin != null) {
            return fechaFin;
        }
        return horaFin.isBefore(horaInicio) ? fechaInicio.plusDays(1) : fechaInicio;
    }

    /**
     * @param inicio instante de inicio (fecha + hora de inicio del negocio).
     * @param fin instante de fin, o null si la incidencia sigue abierta.
     * @param ahora instante actual (inyectado para poder probarlo).
     */
    static Cierre calcular(Instant inicio, Instant fin, Instant ahora) {
        if (inicio.isAfter(ahora.plus(TOLERANCIA_FUTURO))) {
            throw new SolicitudInvalidaException("El inicio no puede estar en el futuro.");
        }
        if (fin == null) {
            return Cierre.ABIERTA;
        }
        if (fin.isBefore(inicio)) {
            throw new SolicitudInvalidaException("El fin no puede ser anterior al inicio.");
        }
        if (fin.isAfter(ahora.plus(TOLERANCIA_FUTURO))) {
            throw new SolicitudInvalidaException("El fin no puede estar en el futuro.");
        }
        long segundos = Duration.between(inicio, fin).getSeconds();
        if (segundos > Integer.MAX_VALUE) {
            throw new SolicitudInvalidaException("La duración de la incidencia es demasiado larga.");
        }
        return new Cierre(fin, (int) segundos);
    }

    private CierreIncidencia() {
    }
}
