package oracle.common.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cache en memoria (Caffeine) para respuestas de solo lectura costosas, como
 * los agregados del dashboard (varias agregaciones full-table por request).
 *
 * No cachea datos por usuario: la clave la construye quien consulta. Se
 * invalida por completo con invalidarTodo() o solo un espacio con
 * invalidarEspacio(): el espacio de una clave es el texto antes del primer
 * ':' (p. ej. "bitacora-analitica:filtro" pertenece a "bitacora-analitica"),
 * asi una escritura de Bitacora no descarta los dashboards de Simulacion.
 *
 * La clave interna incluye una generacion global (que invalidarTodo() avanza)
 * y la del espacio (que avanza invalidarEspacio()): un calculo que estaba en
 * curso al invalidar se guarda bajo la generacion vieja y ya no se sirve
 * (invalidateAll de Caffeine no descarta cargas en vuelo).
 */
@Component
public class CacheRespuestas {

    /** Clave interna: generaciones vigentes al empezar el calculo + clave del llamador. */
    private record Clave(long generacion, long generacionEspacio, String clave) {
    }

    private final boolean habilitado;
    private final Cache<Clave, Object> cache;
    private final AtomicLong generacion = new AtomicLong();
    /** Solo contiene los espacios invalidados alguna vez (no crece con cada clave). */
    private final ConcurrentHashMap<String, AtomicLong> generacionesEspacio = new ConcurrentHashMap<>();

    public CacheRespuestas(
            @Value("${app.stats-cache.enabled:true}") boolean habilitado,
            @Value("${app.stats-cache.ttl-ms:60000}") long ttlMs,
            @Value("${app.stats-cache.max-size:500}") long maxSize) {
        this.habilitado = habilitado;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofMillis(ttlMs))
                .build();
    }

    /** Devuelve el valor cacheado o lo calcula con {@code calculo} y lo guarda. */
    @SuppressWarnings("unchecked")
    public <T> T obtener(String clave, Supplier<T> calculo) {
        if (!habilitado) {
            return calculo.get();
        }
        Clave interna = new Clave(generacion.get(), generacionDe(espacio(clave)), clave);
        return (T) cache.get(interna, k -> calculo.get());
    }

    public void invalidarTodo() {
        generacion.incrementAndGet();
        cache.invalidateAll();
    }

    /** Invalida solo las claves del espacio indicado (texto antes del primer ':'). */
    public void invalidarEspacio(String espacio) {
        generacionesEspacio.computeIfAbsent(espacio, e -> new AtomicLong()).incrementAndGet();
        cache.asMap().keySet().removeIf(k -> espacio.equals(espacio(k.clave())));
    }

    private long generacionDe(String espacio) {
        AtomicLong actual = generacionesEspacio.get(espacio);
        return actual == null ? 0L : actual.get();
    }

    private static String espacio(String clave) {
        int separador = clave.indexOf(':');
        return separador < 0 ? clave : clave.substring(0, separador);
    }
}
