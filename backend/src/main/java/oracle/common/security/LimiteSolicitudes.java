package oracle.common.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Limite de solicitudes por clave (p. ej. usuario + tipo de operacion) con
 * ventana deslizante en memoria. Suficiente para un nodo; con varios, cada uno
 * cuenta lo suyo.
 */
@Component
public class LimiteSolicitudes {

    /** Claves a partir de las cuales se poda (una por usuario + panel + categoria). */
    private static final int MAX_CLAVES = 10_000;
    /** Como mucho una poda cada 10 s: podar recorre todo el mapa. */
    private static final long INTERVALO_PODA_MS = 10_000L;

    private final long intervaloPodaMs;

    private final ConcurrentHashMap<String, Deque<Long>> solicitudes = new ConcurrentHashMap<>();
    /** Ventana mas larga usada: una clave sin solicitudes en ella ya no limita nada. */
    private final AtomicLong ventanaMaxima = new AtomicLong();
    private final AtomicLong ultimaPoda = new AtomicLong();

    public LimiteSolicitudes() {
        this(INTERVALO_PODA_MS);
    }

    LimiteSolicitudes(long intervaloPodaMs) {
        this.intervaloPodaMs = intervaloPodaMs;
    }

    /**
     * Registra una solicitud si cabe en la ventana. Devuelve 0 si se admitio o,
     * si no, los milisegundos hasta que se libere un lugar.
     */
    public long consumir(String clave, int maximo, long ventanaMs) {
        long ahora = System.currentTimeMillis();
        Deque<Long> cola = solicitudes.computeIfAbsent(clave, k -> new ArrayDeque<>());
        long espera;
        synchronized (cola) {
            while (!cola.isEmpty() && cola.peekFirst() <= ahora - ventanaMs) {
                cola.pollFirst();
            }
            if (cola.size() >= maximo) {
                espera = cola.peekFirst() + ventanaMs - ahora;
            } else {
                cola.addLast(ahora);
                espera = 0;
            }
        }
        ventanaMaxima.accumulateAndGet(ventanaMs, Math::max);
        if (solicitudes.size() > MAX_CLAVES) {
            podarVencidas(ahora);
        }
        return espera;
    }

    /**
     * Descarta las claves sin solicitudes en la ventana mas larga (acota la memoria).
     * Antes se podaba en CADA solicitud mientras hubiera mas de MAX_CLAVES: con miles
     * de usuarios activos casi ninguna vence y cada peticion recorria el mapa entero.
     */
    private void podarVencidas(long ahora) {
        long anterior = ultimaPoda.get();
        if (ahora - anterior < intervaloPodaMs || !ultimaPoda.compareAndSet(anterior, ahora)) {
            return;
        }
        long limite = ahora - ventanaMaxima.get();
        solicitudes.entrySet().removeIf(entrada -> {
            Deque<Long> cola = entrada.getValue();
            synchronized (cola) {
                return cola.isEmpty() || cola.peekLast() <= limite;
            }
        });
    }

    /** Claves en memoria (para pruebas y metricas). */
    int claves() {
        return solicitudes.size();
    }
}
