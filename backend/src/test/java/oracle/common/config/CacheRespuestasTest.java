package oracle.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CacheRespuestasTest {

    private CacheRespuestas cache(boolean habilitado) {
        return new CacheRespuestas(habilitado, 60_000L, 100L);
    }

    @Test
    void calculaUnaVezYReutiliza() {
        AtomicInteger contador = new AtomicInteger();
        CacheRespuestas cache = cache(true);

        String primero = cache.obtener("k", () -> "v" + contador.incrementAndGet());
        String segundo = cache.obtener("k", () -> "v" + contador.incrementAndGet());

        assertThat(primero).isEqualTo("v1");
        assertThat(segundo).isEqualTo("v1");
        assertThat(contador.get()).isEqualTo(1);
    }

    @Test
    void invalidarTodoLimpiaLaCache() {
        AtomicInteger contador = new AtomicInteger();
        CacheRespuestas cache = cache(true);

        cache.obtener("k", () -> contador.incrementAndGet());
        cache.invalidarTodo();
        cache.obtener("k", () -> contador.incrementAndGet());

        assertThat(contador.get()).isEqualTo(2);
    }

    @Test
    void unCalculoEnCursoNoSobreviveAInvalidarTodo() throws Exception {
        CacheRespuestas cache = cache(true);
        AtomicInteger datos = new AtomicInteger(1);
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch salida = new CountDownLatch(1);

        CompletableFuture<Integer> lento = CompletableFuture.supplyAsync(() -> cache.obtener("k", () -> {
            int leido = datos.get();
            dentro.countDown();
            try {
                salida.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return leido;
        }));
        assertThat(dentro.await(5, TimeUnit.SECONDS)).isTrue();

        // Commit de una incidencia mientras el dashboard se calculaba con los datos viejos.
        datos.set(2);
        cache.invalidarTodo();
        salida.countDown();

        assertThat(lento.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(cache.obtener("k", datos::get)).isEqualTo(2);
    }

    @Test
    void invalidarEspacioSoloDescartaSusClaves() {
        AtomicInteger bitacora = new AtomicInteger();
        AtomicInteger simulacion = new AtomicInteger();
        CacheRespuestas cache = cache(true);

        cache.obtener("bitacora-analitica:f1", bitacora::incrementAndGet);
        cache.obtener("simulacion-dashboard:f1", simulacion::incrementAndGet);
        cache.invalidarEspacio("bitacora-analitica");
        cache.obtener("bitacora-analitica:f1", bitacora::incrementAndGet);
        cache.obtener("simulacion-dashboard:f1", simulacion::incrementAndGet);

        assertThat(bitacora.get()).isEqualTo(2);
        assertThat(simulacion.get()).isEqualTo(1);
    }

    @Test
    void unCalculoEnCursoNoSobreviveAInvalidarSuEspacio() throws Exception {
        CacheRespuestas cache = cache(true);
        AtomicInteger datos = new AtomicInteger(1);
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch salida = new CountDownLatch(1);

        CompletableFuture<Integer> lento = CompletableFuture.supplyAsync(() -> cache.obtener("esp:k", () -> {
            int leido = datos.get();
            dentro.countDown();
            try {
                salida.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return leido;
        }));
        assertThat(dentro.await(5, TimeUnit.SECONDS)).isTrue();

        datos.set(2);
        cache.invalidarEspacio("esp");
        salida.countDown();

        assertThat(lento.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(cache.obtener("esp:k", datos::get)).isEqualTo(2);
    }

    @Test
    void deshabilitadaCalculaSiempre() {
        AtomicInteger contador = new AtomicInteger();
        CacheRespuestas cache = cache(false);

        cache.obtener("k", () -> contador.incrementAndGet());
        cache.obtener("k", () -> contador.incrementAndGet());

        assertThat(contador.get()).isEqualTo(2);
    }
}
