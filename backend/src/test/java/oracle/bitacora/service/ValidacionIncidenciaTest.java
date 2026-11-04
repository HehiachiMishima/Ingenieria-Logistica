package oracle.bitacora.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import oracle.bitacora.repository.ReferenciasIncidencia;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.RecursoNoEncontradoException;
import org.junit.jupiter.api.Test;

class ValidacionIncidenciaTest {

    private static final ReferenciasIncidencia VALIDAS = new ReferenciasIncidencia(true, true, true, 2L, 4L, false);

    private static void validar(ReferenciasIncidencia ref, boolean edicion) {
        ValidacionIncidencia.validar(1L, 2L, 3L, 4L, 5L, "T-1", edicion, ref);
    }

    @Test
    void referenciasCoherentesPasan() {
        assertThatCode(() -> validar(VALIDAS, false)).doesNotThrowAnyException();
    }

    @Test
    void catalogoInexistenteEs404() {
        assertThatThrownBy(() -> validar(new ReferenciasIncidencia(false, true, true, 2L, 4L, false), false))
                .isInstanceOf(RecursoNoEncontradoException.class).hasMessageContaining("centro con id 1");
        assertThatThrownBy(() -> validar(new ReferenciasIncidencia(true, true, true, null, 4L, false), false))
                .isInstanceOf(RecursoNoEncontradoException.class).hasMessageContaining("tipo de incidencia con id 3");
        assertThatThrownBy(() -> validar(new ReferenciasIncidencia(true, true, true, 2L, null, false), false))
                .isInstanceOf(RecursoNoEncontradoException.class).hasMessageContaining("responsable con id 5");
    }

    @Test
    void tipoDeOtroSistemaOResponsableDeOtraAreaEs409() {
        assertThatThrownBy(() -> validar(new ReferenciasIncidencia(true, true, true, 9L, 4L, false), false))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("no pertenece al sistema");
        assertThatThrownBy(() -> validar(new ReferenciasIncidencia(true, true, true, 2L, 9L, false), false))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("no pertenece al área");
    }

    @Test
    void ticketOcupadoEs409ConMensajeSegunAltaOEdicion() {
        ReferenciasIncidencia ocupado = new ReferenciasIncidencia(true, true, true, 2L, 4L, true);
        assertThatThrownBy(() -> validar(ocupado, false))
                .isInstanceOf(ConflictoException.class).hasMessage("Ya existe una incidencia con el ticket 'T-1'.");
        assertThatThrownBy(() -> validar(ocupado, true))
                .isInstanceOf(ConflictoException.class).hasMessage("Ya existe otra incidencia con el ticket 'T-1'.");
    }
}
