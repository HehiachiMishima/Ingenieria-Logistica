package oracle.simulacionsanta.service;

import static oracle.simulacionsanta.service.SimulacionSantaPredeterminadosServiceTest.pedido;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import oracle.common.config.Transacciones;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.simulacionsanta.model.RegistroSanta;
import oracle.simulacionsanta.repository.Candado;
import oracle.simulacionsanta.repository.RegistroSantaRepository;
import oracle.simulacionsanta.repository.SimulacionSantaLotesRepository;
import oracle.simulacionsanta.request.EnvioRequest;
import oracle.simulacionsanta.response.ConfiguracionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Configuración de un registro con el candado de la configuración, sin BD. */
class SimulacionSantaConfiguracionServiceTest {

  private static final Long ID = 5L;
  private static final Instant EN = Instant.parse("2026-11-17T15:00:00Z");
  private static final Candado BLOQUEADA = new Candado(true, "Ana Pérez", EN);
  private static final String MENSAJE =
      "La configuración está bloqueada: desbloquéala para cambiarla.";

  private RegistroSantaRepository registros;
  private SimulacionSantaLotesRepository lotes;
  private SimulacionSantaConfiguracionService servicio;
  private RegistroSanta registro;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void preparar() {
    registros = mock(RegistroSantaRepository.class);
    lotes = mock(SimulacionSantaLotesRepository.class);
    Transacciones transacciones = mock(Transacciones.class);
    when(transacciones.ejecutar(any())).thenAnswer((i) -> ((Supplier<Object>) i.getArgument(0)).get());
    when(transacciones.consultar(any())).thenAnswer((i) -> ((Supplier<Object>) i.getArgument(0)).get());
    servicio = new SimulacionSantaConfiguracionService(registros, lotes, transacciones);

    registro = new RegistroSanta();
    registro.setId(ID);
    registro.setOperarios(6);
    registro.setBultosHora(120);
    registro.setOrdenCluster1("RAMPAS_PRIMERO");
    registro.setOrdenCluster2("PASILLOS_PRIMERO");
    registro.setOrdenCluster3("PASILLOS_PRIMERO");
    registro.setConfiguradoPor(1L);
    registro.setConfiguradoEn(Instant.parse("2026-11-17T10:00:00Z"));
    when(registros.bloquear(ID)).thenReturn(Optional.of(registro));
    when(registros.findById(ID)).thenReturn(Optional.of(registro));
    when(registros.save(any())).thenAnswer((i) -> i.getArgument(0));
    when(lotes.ubicaciones(ID)).thenReturn(List.of());
  }

  @Test
  void conCandadoNoSeCambianLosOperarios() {
    when(lotes.candado()).thenReturn(BLOQUEADA);

    ConflictoException error =
        assertThrows(
            ConflictoException.class, () -> servicio.guardarConfiguracion(ID, pedido(7, 120, 1)));

    assertEquals(MENSAJE, error.getMessage());
    assertEquals(6, registro.getOperarios());
    verify(registros, never()).save(any());
    verify(lotes, never()).guardarUbicaciones(anyLong(), anyList());
  }

  @Test
  void conCandadoTampocoSeCambianSoloLosClusters() {
    when(lotes.candado()).thenReturn(BLOQUEADA);

    // Mismos operarios y bultos por hora: igual es 409, ya no se compara campo a campo.
    ConflictoException error =
        assertThrows(
            ConflictoException.class, () -> servicio.guardarConfiguracion(ID, pedido(6, 120, 3)));

    assertEquals(MENSAJE, error.getMessage());
    verify(registros, never()).save(any());
    verify(lotes, never()).guardarUbicaciones(anyLong(), anyList());
  }

  @Test
  void conCandadoNiSiquieraLaMismaConfiguracion() {
    when(lotes.candado()).thenReturn(BLOQUEADA);

    assertThrows(
        ConflictoException.class, () -> servicio.guardarConfiguracion(ID, pedido(6, 120, 1)));

    verify(registros, never()).save(any());
  }

  @Test
  void unRegistroQueNoExisteSigueSiendo404ConCandado() {
    when(lotes.candado()).thenReturn(BLOQUEADA);
    when(registros.bloquear(99L)).thenReturn(Optional.empty());

    assertThrows(
        RecursoNoEncontradoException.class,
        () -> servicio.guardarConfiguracion(99L, pedido(6, 120, 1)));
  }

  @Test
  void conCandadoElEnvioSiCambia() {
    when(lotes.candado()).thenReturn(BLOQUEADA);

    servicio.cambiarEnvio(ID, new EnvioRequest(1));

    assertEquals(1, registro.getEnvioActual());
  }

  @Test
  void sinCandadoSeGuardaTodo() {
    when(lotes.candado()).thenReturn(Candado.ABIERTO);

    ConfiguracionResponse datos = servicio.guardarConfiguracion(ID, pedido(9, 150, 3)).datos();

    assertEquals(9, registro.getOperarios());
    assertEquals(150, registro.getBultosHora());
    verify(lotes).guardarUbicaciones(eq(ID), anyList());
    assertFalse(datos.bloqueado());
    assertNull(datos.bloqueadoPor());
    assertNull(datos.bloqueadoEn());
    assertEquals(3, datos.rampas().getFirst().cluster());
  }

  @Test
  void laConfiguracionDelRegistroDiceQuienYCuandoLaBloqueo() {
    when(lotes.candado()).thenReturn(BLOQUEADA);

    ConfiguracionResponse datos = servicio.configuracion(ID).datos();

    assertTrue(datos.bloqueado());
    assertEquals("Ana Pérez", datos.bloqueadoPor());
    assertEquals(EN, datos.bloqueadoEn());
    assertEquals(ID, datos.registroId());

    when(lotes.candado()).thenReturn(Candado.ABIERTO);
    ConfiguracionResponse abierta = servicio.configuracion(ID).datos();
    assertFalse(abierta.bloqueado());
    assertNull(abierta.bloqueadoPor());
    assertNull(abierta.bloqueadoEn());
  }
}
