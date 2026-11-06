package oracle.common.support;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import oracle.common.exception.TiempoAgotadoException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ejecutor de consultas analiticas (dashboards).
 *
 * Cada dashboard lanza varias agregaciones independientes; aqui corren en
 * paralelo (latencia ~ la consulta mas lenta, no la suma), cada una en su hilo
 * virtual, pero con un semaforo: como maximo {@code app.analitica.paralelismo}
 * consultas analiticas simultaneas en todo el nodo. Asi un pico de dashboards
 * nunca agota el pool de conexiones que necesitan los CRUD de miles de usuarios.
 *
 * Uso:
 * <pre>
 * try (EjecutorAnalitica.Lote lote = ejecutor.lote()) {
 *   Future&lt;A&gt; a = lote.consulta(repo::a);
 *   Future&lt;B&gt; b = lote.consulta(repo::b);
 *   return new R(lote.esperar(a), lote.esperar(b));
 * }
 * </pre>
 * El lote tiene un tope TOTAL ({@code app.analitica.timeout-ms}); al cerrarse
 * cancela lo que siga corriendo (p. ej. si una consulta fallo).
 */
@Component
public class EjecutorAnalitica {

    private final ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore cupos;
    private final long limiteMs;

    public EjecutorAnalitica(
            @Value("${app.analitica.paralelismo:6}") int paralelismo,
            @Value("${app.analitica.timeout-ms:12000}") long timeoutMs) {
        this.cupos = new Semaphore(paralelismo, true);
        this.limiteMs = timeoutMs;
    }

    /** Un grupo de consultas en paralelo con un tope de tiempo comun. */
    public Lote lote() {
        return new Lote(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limiteMs));
    }

    @PreDestroy
    void cerrar() {
        hilos.shutdownNow();
    }

    public final class Lote implements AutoCloseable {

        private final long vence;
        private final List<Future<?>> lanzadas = new ArrayList<>();

        private Lote(long vence) {
            this.vence = vence;
        }

        /** Lanza la consulta en un hilo virtual; espera cupo si ya hay {@code paralelismo} corriendo. */
        public <T> Future<T> consulta(Supplier<T> trabajo) {
            // El correlation-id de la peticion acompaña a los logs de la consulta.
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            Future<T> futura = hilos.submit(() -> {
                cupos.acquire();
                try {
                    if (mdc != null) {
                        MDC.setContextMap(mdc);
                    }
                    return trabajo.get();
                } finally {
                    MDC.clear();
                    cupos.release();
                }
            });
            lanzadas.add(futura);
            return futura;
        }

        /** Resultado de una consulta del lote, dentro del tope de tiempo del lote. */
        public <T> T esperar(Future<T> futura) {
            long restante = vence - System.nanoTime();
            try {
                return futura.get(Math.max(0, restante), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                throw new TiempoAgotadoException("El analisis tardo demasiado en calcularse. Intente de nuevo.");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException causa) {
                    throw causa;
                }
                throw new IllegalStateException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TiempoAgotadoException("La consulta analitica se interrumpio.");
            }
        }

        @Override
        public void close() {
            for (Future<?> futura : lanzadas) {
                futura.cancel(true);
            }
        }
    }
}
