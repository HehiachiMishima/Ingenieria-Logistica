package oracle.common.config;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Schedulers;

/**
 * Propaga el MDC (correlation-id) a los hilos del scheduler de Reactor.
 *
 * Los services ejecutan su trabajo bloqueante en Schedulers.boundedElastic();
 * sin esto, las lineas de log emitidas desde esos hilos no llevarian el
 * correlationId de la peticion. El hook captura el MDC del hilo que agenda la
 * tarea (el hilo de la peticion) y lo restaura en el hilo de ejecucion.
 */
@Configuration
public class ContextoReactivo {

    @PostConstruct
    void propagarMdcAScheduler() {
        Schedulers.onScheduleHook("mdc", tarea -> {
            Map<String, String> contexto = MDC.getCopyOfContextMap();
            return () -> {
                if (contexto != null) {
                    MDC.setContextMap(contexto);
                }
                try {
                    tarea.run();
                } finally {
                    MDC.clear();
                }
            };
        });
    }
}
