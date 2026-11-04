package oracle.common.support;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Calculos puros de rangos de dias que comparten los dashboards. */
public final class Periodos {

    /** Rango de dias del negocio, ambos extremos incluidos. */
    public record Rango(LocalDate desde, LocalDate hasta) {
    }

    /**
     * Periodo inmediatamente anterior de igual duracion (para comparar contra
     * el periodo previo). Null si falta un extremo o el rango esta invertido.
     */
    public static Rango anterior(LocalDate desde, LocalDate hasta) {
        if (desde == null || hasta == null || hasta.isBefore(desde)) {
            return null;
        }
        long dias = ChronoUnit.DAYS.between(desde, hasta) + 1;
        LocalDate hastaPrevio = desde.minusDays(1);
        return new Rango(hastaPrevio.minusDays(dias - 1), hastaPrevio);
    }

    private Periodos() {
    }
}
