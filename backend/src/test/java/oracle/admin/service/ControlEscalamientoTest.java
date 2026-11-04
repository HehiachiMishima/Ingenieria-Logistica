package oracle.admin.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import oracle.admin.repository.ResolucionRepository;
import oracle.common.exception.OperacionNoPermitidaException;
import org.junit.jupiter.api.Test;

class ControlEscalamientoTest {

    private final ResolucionRepository resolucion = mock(ResolucionRepository.class);
    private final ControlEscalamiento control = new ControlEscalamiento(resolucion);

    @Test
    void noSePuedeOtorgarUnPermisoQueNoSeTiene() {
        when(resolucion.nombresPermitidos(7L)).thenReturn(List.of("PANEL_BITACORA", "ASIGNAR_PERMISOS"));

        assertThatCode(() -> control.verificarPuedeOtorgar(7L, List.of("panel_bitacora"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> control.verificarPuedeOtorgar(7L, List.of("PANEL_BITACORA", "CREAR_USUARIOS")))
                .isInstanceOf(OperacionNoPermitidaException.class)
                .hasMessageContaining("CREAR_USUARIOS")
                .hasMessageNotContaining("PANEL_BITACORA");
    }

    @Test
    void noSeAdministraAQuienTieneMasPermisos() {
        when(resolucion.nombresPermitidos(7L)).thenReturn(List.of("VER_USUARIOS", "MODIFICAR_USUARIOS"));
        when(resolucion.nombresPermitidos(8L)).thenReturn(List.of("VER_USUARIOS"));
        when(resolucion.nombresPermitidos(9L)).thenReturn(List.of("VER_USUARIOS", "ASIGNAR_ROLES"));

        assertThatCode(() -> control.verificarPuedeAdministrar(7L, 8L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> control.verificarPuedeAdministrar(7L, 9L))
                .isInstanceOf(OperacionNoPermitidaException.class);
    }

    @Test
    void elSistemaQuedaExentoPeroUnActorNuloNo() {
        when(resolucion.nombresPermitidos(9L)).thenReturn(List.of("ASIGNAR_ROLES"));

        assertThatCode(() -> control.verificarPuedeOtorgar(0L, List.of("CUALQUIERA"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> control.verificarPuedeOtorgar(null, List.of("CUALQUIERA")))
                .isInstanceOf(OperacionNoPermitidaException.class);
        assertThatThrownBy(() -> control.verificarPuedeAdministrar(null, 9L))
                .isInstanceOf(OperacionNoPermitidaException.class);
    }
}
