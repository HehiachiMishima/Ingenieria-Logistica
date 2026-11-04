package oracle.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LimiteSolicitudesTest {

    @Test
    void admiteHastaElMaximoYLuegoDiceCuantoEsperar() {
        LimiteSolicitudes limite = new LimiteSolicitudes();

        for (int i = 0; i < 3; i++) {
            assertThat(limite.consumir("ana", 3, 60_000)).isZero();
        }

        assertThat(limite.consumir("ana", 3, 60_000)).isBetween(1L, 60_000L);
    }

    @Test
    void cadaClaveCuentaPorSeparado() {
        LimiteSolicitudes limite = new LimiteSolicitudes();

        assertThat(limite.consumir("ana", 1, 60_000)).isZero();

        assertThat(limite.consumir("luis", 1, 60_000)).isZero();
        assertThat(limite.consumir("ana", 1, 60_000)).isPositive();
    }

    @Test
    void laVentanaVencidaLiberaLugar() throws InterruptedException {
        LimiteSolicitudes limite = new LimiteSolicitudes();
        assertThat(limite.consumir("ana", 1, 50)).isZero();

        Thread.sleep(80);

        assertThat(limite.consumir("ana", 1, 50)).isZero();
    }

    @Test
    void alPasarElTopeDeClavesPodaLasVencidas() throws InterruptedException {
        LimiteSolicitudes limite = new LimiteSolicitudes(0);
        for (int i = 0; i <= 10_000; i++) {
            limite.consumir("vieja" + i, 5, 20);
        }
        Thread.sleep(40);

        limite.consumir("nueva", 5, 20);

        assertThat(limite.claves()).isLessThan(10);
    }

    @Test
    void noPodaEnCadaSolicitudSinoUnaVezPorIntervalo() throws InterruptedException {
        LimiteSolicitudes limite = new LimiteSolicitudes(60_000);
        for (int i = 0; i <= 10_000; i++) {
            limite.consumir("vieja" + i, 5, 20);
        }
        Thread.sleep(40);

        limite.consumir("nueva", 5, 20);

        // La poda de este intervalo ya ocurrio al cruzar el tope: no se repite.
        assertThat(limite.claves()).isGreaterThan(10_000);
    }
}
