package oracle.common.support;

import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Helper de trazabilidad: envuelve una operacion de service, registra su
 * duracion (DEBUG, o WARN si supera el umbral de lentitud) y propaga el error.
 * En produccion evita una linea INFO por operacion: el acceso ya lo traza
 * RegistroPeticiones.
 */
public final class Trazas {

    /** Operaciones por encima de este umbral se registran como WARN. */
    private static final long UMBRAL_LENTO_MS = 800;

    public static <T> T medir(Logger log, String operacion, Supplier<T> bloque) {
        long inicio = System.nanoTime();
        try {
            T resultado = bloque.get();
            long ms = milisegundos(inicio);
            if (ms >= UMBRAL_LENTO_MS) {
                log.warn("{} -> LENTO ({} ms)", operacion, ms);
            } else if (log.isDebugEnabled()) {
                log.debug("{} -> OK ({} ms)", operacion, ms);
            }
            return resultado;
        } catch (RuntimeException ex) {
            log.warn("{} -> ERROR ({} ms): {}", operacion, milisegundos(inicio), ex.getMessage());
            throw ex;
        }
    }

    private static long milisegundos(long inicio) {
        return (System.nanoTime() - inicio) / 1_000_000L;
    }

    private Trazas() {
    }
}
