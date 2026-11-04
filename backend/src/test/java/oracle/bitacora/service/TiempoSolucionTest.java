package oracle.bitacora.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class TiempoSolucionTest {

    @Test
    void restaFinMenosInicio() {
        assertThat(TiempoSolucion.segundos(LocalTime.of(8, 0), LocalTime.of(9, 30, 15))).isEqualTo(5_415);
    }

    @Test
    void cruzarLaMedianocheSumaUnDia() {
        assertThat(TiempoSolucion.segundos(LocalTime.of(23, 30), LocalTime.of(0, 15))).isEqualTo(45 * 60);
    }

    @Test
    void mismaHoraEsCeroYSinHoraEsNulo() {
        assertThat(TiempoSolucion.segundos(LocalTime.NOON, LocalTime.NOON)).isZero();
        assertThat(TiempoSolucion.segundos(null, LocalTime.NOON)).isNull();
        assertThat(TiempoSolucion.segundos(LocalTime.NOON, null)).isNull();
    }
}
