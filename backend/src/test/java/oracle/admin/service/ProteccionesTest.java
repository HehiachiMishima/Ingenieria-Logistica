package oracle.admin.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import oracle.common.exception.OperacionNoPermitidaException;
import org.junit.jupiter.api.Test;

class ProteccionesTest {

    @Test
    void permiteGestionarAOtroUsuario() {
        assertThatCode(() -> Protecciones.verificarNoAutoGestion(2L, 1L))
                .doesNotThrowAnyException();
    }

    @Test
    void bloqueaLaAutoGestion() {
        assertThatThrownBy(() -> Protecciones.verificarNoAutoGestion(1L, 1L))
                .isInstanceOf(OperacionNoPermitidaException.class);
    }

    @Test
    void eximeAlSistema() {
        assertThatCode(() -> Protecciones.verificarNoAutoGestion(1L, 0L))
                .doesNotThrowAnyException();
    }
}
