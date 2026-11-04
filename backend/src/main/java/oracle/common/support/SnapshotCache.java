package oracle.common.support;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Snapshot inmutable en memoria con recarga perezosa.
 *
 * Las lecturas son lock-free (AtomicReference). Tras un CRUD se llama a
 * {@link #invalidar()} y la siguiente lectura recarga una sola vez (double
 * check), de modo que no hay thundering herd. Si una recarga programada falla
 * se conserva el anterior.
 *
 * Cada snapshot guarda la generacion en la que empezo su carga e
 * {@link #invalidar()} avanza la generacion: una carga que estaba en curso
 * cuando se invalido termina guardando un snapshot de generacion vieja, que
 * las lecturas descartan. Asi una recarga concurrente nunca pisa una
 * invalidacion con datos desactualizados.
 */
public final class SnapshotCache<T> {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCache.class);

    /** Valor cargado y generacion vigente al empezar su carga. */
    private record Entrada<T>(long generacion, T valor) {
    }

    private final String nombre;
    private final Supplier<T> cargador;
    private final AtomicLong generacion = new AtomicLong();
    private final AtomicReference<Entrada<T>> ref = new AtomicReference<>();

    public SnapshotCache(String nombre, Supplier<T> cargador) {
        this.nombre = nombre;
        this.cargador = cargador;
    }

    public T obtener() {
        Entrada<T> actual = vigente();
        if (actual != null) {
            return actual.valor();
        }
        synchronized (this) {
            actual = vigente();
            if (actual != null) {
                return actual.valor();
            }
            return publicar(generacion.get(), cargador.get());
        }
    }

    /** Descarta el snapshot: la siguiente lectura lo recarga. */
    public void invalidar() {
        generacion.incrementAndGet();
    }

    /** Recarga en segundo plano conservando el snapshot previo si falla. */
    public void refrescar() {
        long inicio = generacion.get();
        try {
            publicar(inicio, cargador.get());
        } catch (RuntimeException ex) {
            log.warn("No se pudo refrescar la cache '{}'; se conserva el snapshot previo.", nombre, ex);
        }
    }

    private Entrada<T> vigente() {
        Entrada<T> actual = ref.get();
        return actual != null && actual.generacion() == generacion.get() ? actual : null;
    }

    /** Guarda el valor salvo que ya haya uno de una generacion posterior. */
    private T publicar(long inicio, T valor) {
        Entrada<T> nueva = new Entrada<>(inicio, valor);
        ref.accumulateAndGet(nueva, (previa, candidata) ->
                previa != null && previa.generacion() > candidata.generacion() ? previa : candidata);
        return valor;
    }
}
