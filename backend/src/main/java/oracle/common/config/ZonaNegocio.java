package oracle.common.config;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Zona horaria del negocio, declarada una sola vez en la configuracion
 * (app.zona-horaria). Las fechas se guardan en UTC; esta clase convierte lo
 * que el usuario ve (dia y hora del negocio) a instantes UTC. No depende de
 * la zona del servidor, de la JVM ni de la base de datos.
 */
@Component
public class ZonaNegocio {

    private final ZoneId zona;

    public ZonaNegocio(@Value("${app.zona-horaria}") String zona) {
        this.zona = ZoneId.of(zona);
    }

    /** Dia y hora del negocio como instante UTC. */
    public Instant instante(LocalDate dia, LocalTime hora) {
        return dia.atTime(hora).atZone(zona).toInstant();
    }

    /** Inicio (00:00 del negocio) de un dia, como instante UTC. */
    public Instant inicioDelDia(LocalDate dia) {
        return dia.atStartOfDay(zona).toInstant();
    }

    /** {@link #inicioDelDia} listo para parametros JDBC. */
    public Timestamp inicioDelDiaSql(LocalDate dia) {
        return Timestamp.from(inicioDelDia(dia));
    }
}
