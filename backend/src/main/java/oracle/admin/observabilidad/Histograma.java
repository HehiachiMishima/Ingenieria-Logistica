package oracle.admin.observabilidad;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Histograma acumulado de duraciones: cada columna {@code hasta_N} cuenta las peticiones
 * que tardaron N ms o menos. Las columnas se suman entre horas y APIs, así el p95 de
 * cualquier período sale del resumen por hora sin recorrer las trazas.
 */
final class Histograma {
    /** Límites superiores de cada intervalo, en ms. Por encima del último: "más de 5 s". */
    static final long[] BORDES = {50, 100, 200, 300, 500, 750, 1000, 2000, 5000};

    private Histograma() {}

    /** {@code hasta_50, hasta_100, …} */
    static String columnas() {
        return Arrays.stream(BORDES).mapToObj(b -> "hasta_" + b).collect(Collectors.joining(", "));
    }

    /** Conteos acumulados sobre la columna de duración de las trazas. */
    static String conteos(String duracion) {
        return Arrays.stream(BORDES)
                .mapToObj(b -> "count(*) FILTER (WHERE " + duracion + " <= " + b + ")")
                .collect(Collectors.joining(", "));
    }

    /** Suma de cada columna del resumen, con su mismo nombre. */
    static String sumas() {
        return Arrays.stream(BORDES)
                .mapToObj(b -> "coalesce(sum(hasta_" + b + "), 0) AS hasta_" + b)
                .collect(Collectors.joining(", "));
    }

    /** Suma lo nuevo a lo que ya había en la fila de la hora (ON CONFLICT … DO UPDATE). */
    static String acumular(String alias) {
        return Arrays.stream(BORDES)
                .mapToObj(b -> "hasta_" + b + " = " + alias + ".hasta_" + b + " + EXCLUDED.hasta_" + b)
                .collect(Collectors.joining(", "));
    }

    static long[] leer(java.sql.ResultSet rs) throws java.sql.SQLException {
        long[] hasta = new long[BORDES.length];
        for (int i = 0; i < BORDES.length; i++) hasta[i] = rs.getLong("hasta_" + BORDES[i]);
        return hasta;
    }

    /**
     * Percentil {@code q} (0–1) interpolando dentro del intervalo donde cae, como
     * histogram_quantile de Prometheus. 0 sin peticiones; el último borde si cae por encima.
     */
    static long percentil(long total, long[] hasta, double q) {
        if (total <= 0) return 0;
        double objetivo = Math.ceil(q * total);
        for (int i = 0; i < BORDES.length; i++) {
            if (hasta[i] >= objetivo) {
                long antes = i == 0 ? 0 : hasta[i - 1];
                long desde = i == 0 ? 0 : BORDES[i - 1];
                long enIntervalo = hasta[i] - antes;
                if (enIntervalo <= 0) return BORDES[i];
                return Math.round(desde + (BORDES[i] - desde) * (objetivo - antes) / enIntervalo);
            }
        }
        return BORDES[BORDES.length - 1];
    }
}
