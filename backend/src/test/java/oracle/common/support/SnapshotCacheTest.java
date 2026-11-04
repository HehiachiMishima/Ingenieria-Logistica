package oracle.common.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SnapshotCacheTest {

    @Test
    void cargaUnaSolaVezHastaInvalidar() {
        AtomicInteger cargas = new AtomicInteger();
        SnapshotCache<Integer> cache = new SnapshotCache<>("test", cargas::incrementAndGet);

        assertEquals(1, cache.obtener());
        assertEquals(1, cache.obtener());
        assertEquals(1, cargas.get());

        cache.invalidar();
        assertEquals(2, cache.obtener());
        assertEquals(2, cargas.get());
    }

    @Test
    void refrescoFallidoConservaElSnapshotPrevio() {
        AtomicInteger cargas = new AtomicInteger();
        SnapshotCache<Integer> cache = new SnapshotCache<>("test", () -> {
            if (cargas.incrementAndGet() > 1) {
                throw new IllegalStateException("bd caida");
            }
            return 42;
        });

        assertEquals(42, cache.obtener());
        cache.refrescar();
        assertEquals(42, cache.obtener());
    }

    @Test
    void unaCargaEnCursoNoPisaUnaInvalidacionConcurrente() throws Exception {
        CargadorControlado bd = new CargadorControlado();
        SnapshotCache<Integer> cache = new SnapshotCache<>("test", bd::cargar);

        bd.bloquearSiguienteCarga();
        CompletableFuture<Integer> lectorLento = CompletableFuture.supplyAsync(cache::obtener);
        bd.esperarCargaBloqueada();

        // Commit de un CRUD mientras la carga lee los datos viejos.
        bd.version.set(2);
        cache.invalidar();
        bd.liberar();

        assertEquals(1, lectorLento.get(5, TimeUnit.SECONDS));
        assertEquals(2, cache.obtener(), "la siguiente lectura debe ver los datos posteriores al commit");
    }

    @Test
    void unRefrescoEnCursoNoPisaUnaInvalidacionConcurrente() throws Exception {
        CargadorControlado bd = new CargadorControlado();
        SnapshotCache<Integer> cache = new SnapshotCache<>("test", bd::cargar);
        assertEquals(1, cache.obtener());

        bd.bloquearSiguienteCarga();
        CompletableFuture<Void> refresco = CompletableFuture.runAsync(cache::refrescar);
        bd.esperarCargaBloqueada();

        bd.version.set(2);
        cache.invalidar();
        bd.liberar();
        refresco.get(5, TimeUnit.SECONDS);

        assertEquals(2, cache.obtener(), "el refresco con datos viejos no debe ocultar la invalidacion");
    }

    /** "Base de datos" de prueba cuya proxima carga se puede detener a mitad. */
    private static final class CargadorControlado {

        final AtomicInteger version = new AtomicInteger(1);
        private volatile CountDownLatch dentro = new CountDownLatch(0);
        private volatile CountDownLatch salida = new CountDownLatch(0);

        void bloquearSiguienteCarga() {
            dentro = new CountDownLatch(1);
            salida = new CountDownLatch(1);
        }

        Integer cargar() {
            int leida = version.get();
            dentro.countDown();
            try {
                assertTrue(salida.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return leida;
        }

        void esperarCargaBloqueada() throws InterruptedException {
            assertTrue(dentro.await(5, TimeUnit.SECONDS));
        }

        void liberar() {
            salida.countDown();
        }
    }
}
