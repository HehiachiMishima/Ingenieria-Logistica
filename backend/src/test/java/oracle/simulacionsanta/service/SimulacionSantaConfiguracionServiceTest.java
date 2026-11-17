package oracle.simulacionsanta.service;

import static oracle.simulacionsanta.service.SimulacionSantaPredeterminadosServiceTest.pedido;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import oracle.simulacionsanta.model.RegistroSanta;
import oracle.simulacionsanta.repository.RegistroSantaRepository;
import oracle.simulacionsanta.repository.SimulacionSantaLotesRepository;
import oracle.simulacionsanta.repository.UbicacionGuardada;
import oracle.simulacionsanta.response.ConfiguracionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Configuración de un registro con el candado de operarios y productividad, sin BD. */
class SimulacionSantaConfiguracionServiceTest {

  private static final Long ID = 5L;

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
    when(lotes.predeterminadosBloqueados()).thenReturn(true);

    ConflictoException error =
        assertThrows(
            ConflictoException.class, () -> servicio.guardarConfiguracion(ID, pedido(7, 120, 1)));

    assertEquals(
        "Operarios y productividad están bloqueados: desbloquéalos para cambiarlos.",
        error.getMessage());
    assertEquals(6, registro.getOperarios());
    verify(registros, never()).save(any());
    verify(lotes, never()).guardarUbicaciones(anyLong(), anyList());
  }

  @Test
  void conCandadoNoSeCambiaLaProductividad() {
    when(lotes.predeterminadosBloqueados()).thenReturn(true);

    assertThrows(
        ConflictoException.class, () -> servicio.guardarConfiguracion(ID, pedido(6, 121, 1)));

    assertEquals(120, registro.getBultosHora());
    verify(registros, never()).save(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void conCandadoSiSeCambianLosClusters() {
    when(lotes.predeterminadosBloqueados()).thenReturn(true);

    ConfiguracionResponse datos = servicio.guardarConfiguracion(ID, pedido(6, 120, 3)).datos();

    ArgumentCaptor<List<UbicacionGuardada>> ubicaciones = ArgumentCaptor.forClass(List.class);
    verify(lotes).guardarUbicaciones(eq(ID), ubicaciones.capture());
    UbicacionGuardada rampa15 =
        ubicaciones.getValue().stream()
            .filter((u) -> "RAMPA".equals(u.tipo()) && u.numero() == 15)
            .findFirst()
            .orElseThrow();
    assertEquals(3, rampa15.cluster());
    assertTrue(datos.bloqueado());
    assertEquals(3, datos.rampas().getFirst().cluster());
    assertEquals(6, datos.operarios());
  }

  @Test
  void sinCandadoSeCambianLosOperarios() {
    when(lotes.predeterminadosBloqueados()).thenReturn(false);

    ConfiguracionResponse datos = servicio.guardarConfiguracion(ID, pedido(9, 150, 1)).datos();

    assertEquals(9, registro.getOperarios());
    assertEquals(150, registro.getBultosHora());
    assertFalse(datos.bloqueado());
    assertEquals(9, datos.operarios());
  }

  @Test
  void laConfiguracionDelRegistroDiceSiEstaBloqueada() {
    when(lotes.predeterminadosBloqueados()).thenReturn(true);

    ConfiguracionResponse datos = servicio.configuracion(ID).datos();

    assertTrue(datos.bloqueado());
    assertEquals(ID, datos.registroId());
    assertEquals(6, datos.operarios());

    when(lotes.predeterminadosBloqueados()).thenReturn(false);
    assertFalse(servicio.configuracion(ID).datos().bloqueado());
  }
}
