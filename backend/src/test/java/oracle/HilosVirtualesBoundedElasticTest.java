package oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * El arranque activa hilos virtuales en el boundedElastic de Reactor. Reactor lee
 * la propiedad al cargar Schedulers (una vez por JVM): surefire la fija desde el
 * inicio de la JVM de tests (ver pom.xml), igual que BackendApplication en producción.
 */
class HilosVirtualesBoundedElasticTest {

  @Test
  void elArranqueFijaLaPropiedadSinPisarUnValorExplicito() {
    String anterior = System.getProperty(BackendApplication.PROPIEDAD_BOUNDED_ELASTIC_VIRTUAL);
    try {
      System.setProperty(BackendApplication.PROPIEDAD_BOUNDED_ELASTIC_VIRTUAL, "false");
      BackendApplication.usarHilosVirtualesEnBoundedElastic();
      assertEquals("false", System.getProperty(BackendApplication.PROPIEDAD_BOUNDED_ELASTIC_VIRTUAL));

      System.clearProperty(BackendApplication.PROPIEDAD_BOUNDED_ELASTIC_VIRTUAL);
      BackendApplication.usarHilosVirtualesEnBoundedElastic();
      assertEquals("true", System.getProperty(BackendApplication.PROPIEDAD_BOUNDED_ELASTIC_VIRTUAL));
    } finally {
      if (anterior == null) {
        System.clearProperty(BackendApplication.PROPIEDAD_BOUNDED_ELASTIC_VIRTUAL);
      } else {
        System.setProperty(BackendApplication.PROPIEDAD_BOUNDED_ELASTIC_VIRTUAL, anterior);
      }
    }
  }

  @Test
  void conLaPropiedadElBoundedElasticCorreEnHilosVirtuales() throws Exception {
    BackendApplication.usarHilosVirtualesEnBoundedElastic();
    // Un scheduler nuevo de la misma fábrica que usa Schedulers.boundedElastic():
    // con la propiedad activa, sus hilos son virtuales.
    Callable<Boolean> enVirtual =
        () ->
            Mono.fromCallable(() -> Thread.currentThread().isVirtual())
                .subscribeOn(Schedulers.boundedElastic())
                .block();
    ExecutorService ejecutor = Executors.newSingleThreadExecutor();
    try {
      assertTrue(ejecutor.submit(enVirtual).get());
    } finally {
      ejecutor.shutdownNow();
    }
  }
}
