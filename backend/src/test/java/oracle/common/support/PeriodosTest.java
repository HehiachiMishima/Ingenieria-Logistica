package oracle.common.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PeriodosTest {

    @Test
    void elPeriodoAnteriorTieneLaMismaDuracionYTerminaElDiaPrevio() {
        Periodos.Rango previo = Periodos.anterior(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(previo.desde()).isEqualTo(LocalDate.of(2026, 1, 29));
        assertThat(previo.hasta()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void unSoloDiaComparaContraElDiaAnterior() {
        Periodos.Rango previo = Periodos.anterior(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));

        assertThat(previo).isEqualTo(new Periodos.Rango(LocalDate.of(2025, 12, 31), LocalDate.of(2025, 12, 31)));
    }

    @Test
    void sinRangoCompletoOInvertidoNoHayPeriodoAnterior() {
        assertThat(Periodos.anterior(null, LocalDate.of(2026, 1, 1))).isNull();
        assertThat(Periodos.anterior(LocalDate.of(2026, 1, 1), null)).isNull();
        assertThat(Periodos.anterior(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1))).isNull();
    }
}
