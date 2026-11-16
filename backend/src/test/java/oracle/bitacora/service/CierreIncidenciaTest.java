package oracle.bitacora.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import oracle.common.exception.SolicitudInvalidaException;
import org.junit.jupiter.api.Test;

class CierreIncidenciaTest {

    private static final LocalDate DIA = LocalDate.of(2026, 11, 13);
    private static final Instant INICIO = Instant.parse("2026-11-13T13:00:00Z");
    private static final Instant AHORA = Instant.parse("2026-11-16T15:00:00Z");

    @Test
    void sinFinQuedaAbierta() {
        CierreIncidencia.Cierre c = CierreIncidencia.calcular(INICIO, null, AHORA);
        assertThat(c.abierta()).isTrue();
        assertThat(c.segundos()).isNull();
        assertThat(CierreIncidencia.fechaFin(DIA, LocalTime.of(8, 0), null, null)).isNull();
    }

    @Test
    void duracionDePuntaAPuntaAunqueDureVariosDias() {
        Instant fin = Instant.parse("2026-11-15T15:30:00Z");
        CierreIncidencia.Cierre c = CierreIncidencia.calcular(INICIO, fin, AHORA);
        assertThat(c.cerradoEn()).isEqualTo(fin);
        assertThat(c.segundos()).isEqualTo(50 * 3600 + 30 * 60);
    }

    @Test
    void sinFechaDeFinSeDeduceComoSiempre() {
        assertThat(CierreIncidencia.fechaFin(DIA, LocalTime.of(8, 0), null, LocalTime.of(9, 30))).isEqualTo(DIA);
        assertThat(CierreIncidencia.fechaFin(DIA, LocalTime.of(23, 30), null, LocalTime.of(0, 15)))
                .isEqualTo(DIA.plusDays(1));
        assertThat(CierreIncidencia.fechaFin(DIA, LocalTime.of(8, 0), DIA.plusDays(2), LocalTime.of(7, 0)))
                .isEqualTo(DIA.plusDays(2));
    }

    @Test
    void fechaSinHoraEsInvalida() {
        assertThatThrownBy(() -> CierreIncidencia.fechaFin(DIA, LocalTime.of(8, 0), DIA, null))
                .isInstanceOf(SolicitudInvalidaException.class);
    }

    @Test
    void finAnteriorAlInicioOFuturoEsInvalido() {
        assertThatThrownBy(() -> CierreIncidencia.calcular(INICIO, INICIO.minusSeconds(60), AHORA))
                .isInstanceOf(SolicitudInvalidaException.class).hasMessageContaining("anterior al inicio");
        assertThatThrownBy(() -> CierreIncidencia.calcular(INICIO, AHORA.plusSeconds(3600), AHORA))
                .isInstanceOf(SolicitudInvalidaException.class).hasMessageContaining("futuro");
        assertThat(CierreIncidencia.calcular(INICIO, AHORA.plusSeconds(60), AHORA).abierta()).isFalse();
    }

    @Test
    void inicioFuturoEsInvalidoAunqueEsteAbierta() {
        Instant manana = AHORA.plusSeconds(86_400);
        assertThatThrownBy(() -> CierreIncidencia.calcular(manana, null, AHORA))
                .isInstanceOf(SolicitudInvalidaException.class).hasMessageContaining("inicio");
        assertThat(CierreIncidencia.calcular(AHORA.plusSeconds(60), null, AHORA).abierta()).isTrue();
    }

    @Test
    void mismoInstanteEsCero() {
        assertThat(CierreIncidencia.calcular(INICIO, INICIO, AHORA).segundos()).isZero();
    }
}
