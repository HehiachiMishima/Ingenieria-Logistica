package oracle.common.support;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Ejecutor de consultas analiticas (dashboards).
 *
 * Cada dashboard lanza varias agregaciones independientes; aqui corren en
 * paralelo (latencia ~ la consulta mas lenta, no la suma) pero sobre un
 * scheduler ACOTADO: como maximo {@code app.analitica.paralelismo} consultas
 * analiticas simultaneas en todo el nodo. Asi un pico de dashboards nunca
 * agota el pool de conexiones que necesitan los CRUD de miles de usuarios.
 */
@Component
public class EjecutorAnalitica {

    private final Scheduler scheduler;
    private final Duration limite;

    public EjecutorAnalitica(
            @Value("${app.analitica.paralelismo:6}") int paralelismo,
            @Value("${app.analitica.timeout-ms:12000}") long timeoutMs) {
        this.scheduler = Schedulers.newBoundedElastic(paralelismo, 10_000, "analitica");
        this.limite = Duration.ofMillis(timeoutMs);
    }

    /** Consulta bloqueante envuelta en un Mono que corre en el scheduler analitico. */
    public <T> Mono<T> consulta(Supplier<T> trabajo) {
        return Mono.fromSupplier(trabajo).subscribeOn(scheduler);
    }

    /**
     * Espera el resultado combinado. Debe invocarse desde un hilo que admita
     * bloqueo (boundedElastic), p. ej. dentro del calculo de CacheRespuestas.
     */
    public <T> T esperar(Mono<T> resultado) {
        return resultado.block(limite);
    }

    @PreDestroy
    void cerrar() {
        scheduler.dispose();
    }
}
