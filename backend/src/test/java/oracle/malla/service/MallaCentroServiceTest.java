package oracle.malla.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import oracle.common.config.Transacciones;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.malla.repository.MallaCentroRepository;
import oracle.malla.request.CentroMallaRequest;
import oracle.malla.request.CentroMallaRequest.Capacidad;
import oracle.malla.request.CentroMallaRequest.Dotacion;
import oracle.malla.request.CentroMallaRequest.DotacionReal;
import oracle.malla.request.CentroMallaRequest.Ola;
import oracle.malla.request.CentroMallaRequest.Turno;
import oracle.malla.request.CentroMallaRequest.Zona;
import org.junit.jupiter.api.Test;

class MallaCentroServiceTest {

    private final MallaCentroRepository repo = mock(MallaCentroRepository.class);
    private final MallaCentroService servicio = new MallaCentroService(repo, mock(Transacciones.class));

    private static final Turno T1 = new Turno("T1", "Turno 1", "07:00", "16:00", null, null, null, true);

    @Test
    void unCodigoDeCentroRepetidoSeRechaza() {
        when(repo.idPorCodigo("9040")).thenReturn(Optional.of(1L));

        ConflictoException error = assertThrows(ConflictoException.class,
                () -> servicio.validar(malla(List.of(T1), List.of(), List.of(), List.of()), 2L));

        assertTrue(error.getMessage().contains("9040"));
    }

    @Test
    void guardarElMismoCentroConSuCodigoEsValido() {
        when(repo.idPorCodigo("9040")).thenReturn(Optional.of(1L));

        servicio.validar(malla(List.of(T1), List.of(), List.of(), List.of()), 1L);
    }

    @Test
    void laDotacionSoloUsaTurnosDeLaLista() {
        when(repo.idPorCodigo("9040")).thenReturn(Optional.empty());

        SolicitudInvalidaException error = assertThrows(SolicitudInvalidaException.class,
                () -> servicio.validar(malla(List.of(T1), List.of(new Dotacion("T2", "REC", 3)),
                        List.of(), List.of()), null));

        assertTrue(error.getMessage().contains("T2"));
    }

    @Test
    void turnosOlasYZonasNoSeRepiten() {
        when(repo.idPorCodigo("9040")).thenReturn(Optional.empty());

        assertThrows(SolicitudInvalidaException.class, () -> servicio.validar(
                malla(List.of(T1, new Turno("t1", "Otro", "08:00", "17:00", null, null, null, false)),
                        List.of(), List.of(), List.of()), null));
        Ola ola = new Ola("1", "Ola 1", null, "07:00", "13:00", null, false, null);
        assertThrows(SolicitudInvalidaException.class, () -> servicio.validar(
                malla(List.of(T1), List.of(), List.of(ola, ola), List.of()), null));
        assertThrows(SolicitudInvalidaException.class, () -> servicio.validar(
                malla(List.of(T1), List.of(), List.of(),
                        List.of(new Zona("GTP", 10, null), new Zona("gtp", 20, null))), null));
    }

    private static CentroMallaRequest malla(List<Turno> turnos, List<Dotacion> dotacion, List<Ola> olas,
            List<Zona> zonas) {
        return new CentroMallaRequest("9040", "Punta Negra", turnos, List.of(), olas, zonas,
                new Capacidad(null, null, null, null), dotacion,
                new DotacionReal(null, null, null, null, null));
    }
}
