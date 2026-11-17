package oracle.simulacionsanta.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import oracle.common.config.Transacciones;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import oracle.simulacionsanta.model.OrdenLlenado;
import oracle.simulacionsanta.repository.PredeterminadosGuardados;
import oracle.simulacionsanta.repository.SimulacionSantaLotesRepository;
import oracle.simulacionsanta.request.ConfiguracionRequest;
import oracle.simulacionsanta.request.ConfiguracionRequest.PasilloRequest;
import oracle.simulacionsanta.request.ConfiguracionRequest.RampaRequest;
import oracle.simulacionsanta.response.ConfiguracionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Candado de operarios y productividad de Santa Anita: ver, bloquear y desbloquear, sin BD. */
class SimulacionSantaPredeterminadosServiceTest {

  private SimulacionSantaLotesRepository lotes;
  private SimulacionSantaPredeterminadosService servicio;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void preparar() {
    lotes = mock(SimulacionSantaLotesRepository.class);
    Transacciones transacciones = mock(Transacciones.class);
    when(transacciones.ejecutar(any())).thenAnswer((i) -> ((Supplier<Object>) i.getArgument(0)).get());
    when(transacciones.consultar(any())).thenAnswer((i) -> ((Supplier<Object>) i.getArgument(0)).get());
    servicio = new SimulacionSantaPredeterminadosService(lotes, transacciones);
  }

  @Test
  void bloquearGuardaTodoConCandadoYLoLlevaALosPendientes() {
    when(lotes.aplicarPredeterminados(eq(9), eq(150), any(), any())).thenReturn(2);

    ApiResponse<ConfiguracionResponse> respuesta = servicio.bloquear(pedido(9, 150, 2));

    // Toda la configuración queda como predeterminada (también clusters y orden), con candado.
    verify(lotes)
        .guardarPredeterminados(
            eq(9),
            eq(150),
            eq(List.of("RAMPAS_PRIMERO", "PASILLOS_PRIMERO", "PASILLOS_PRIMERO")),
            any(),
            eq(true),
            any(),
            any());
    verify(lotes).aplicarPredeterminados(eq(9), eq(150), any(), any());
    ConfiguracionResponse datos = respuesta.datos();
    assertNull(datos.registroId());
    assertTrue(datos.bloqueado());
    assertEquals(9, datos.operarios());
    assertEquals(150, datos.bultosHora());
    assertEquals(2, datos.rampas().getFirst().cluster());
    assertEquals(
        "Operarios y productividad bloqueados para todos los registros;"
            + " se aplicaron a 2 registro(s) pendiente(s).",
        respuesta.mensaje());
  }

  @Test
  void bloquearSinPendientesNoMencionaLaCuenta() {
    when(lotes.aplicarPredeterminados(anyInt(), anyInt(), any(), any())).thenReturn(0);

    ApiResponse<ConfiguracionResponse> respuesta = servicio.bloquear(pedido(6, 120, 1));

    assertEquals("Operarios y productividad bloqueados para todos los registros.", respuesta.mensaje());
  }

  @Test
  void bloquearValidaAntesDeEscribir() {
    assertThrows(SolicitudInvalidaException.class, () -> servicio.bloquear(pedido(0, 120, 1)));

    verify(lotes, never())
        .guardarPredeterminados(anyInt(), anyInt(), anyList(), anyList(), anyBoolean(), any(), any());
    verify(lotes, never()).aplicarPredeterminados(anyInt(), anyInt(), any(), any());
  }

  @Test
  void desbloquearConservaLosValores() {
    Instant en = Instant.parse("2026-11-17T10:00:00Z");
    when(lotes.predeterminados(false))
        .thenReturn(
            new PredeterminadosGuardados(
                9,
                150,
                List.of("PASILLOS_PRIMERO", "PASILLOS_PRIMERO", "RAMPAS_PRIMERO"),
                7L,
                en,
                false,
                List.of()));

    ApiResponse<ConfiguracionResponse> respuesta = servicio.desbloquear();

    verify(lotes).desbloquearPredeterminados(any(), any());
    verify(lotes, never())
        .guardarPredeterminados(anyInt(), anyInt(), anyList(), anyList(), anyBoolean(), any(), any());
    verify(lotes, never()).aplicarPredeterminados(anyInt(), anyInt(), any(), any());
    ConfiguracionResponse datos = respuesta.datos();
    assertFalse(datos.bloqueado());
    assertNull(datos.registroId());
    assertEquals(9, datos.operarios());
    assertEquals(150, datos.bultosHora());
    assertEquals(OrdenLlenado.RAMPAS_PRIMERO, datos.ordenClusters().get(2));
    assertEquals(7L, datos.actualizadoPor());
    assertEquals(en, datos.actualizadoEn());
  }

  @Test
  void verDevuelveElCandado() {
    when(lotes.predeterminados(false))
        .thenReturn(
            new PredeterminadosGuardados(
                8, 130, List.of("RAMPAS_PRIMERO", "PASILLOS_PRIMERO", "PASILLOS_PRIMERO"), 3L,
                Instant.parse("2026-11-17T10:00:00Z"), true, List.of()));

    ConfiguracionResponse datos = servicio.ver().datos();

    assertTrue(datos.bloqueado());
    assertEquals(8, datos.operarios());
  }

  @Test
  void sinLaFilaSonLosDeFabricaAbiertos() {
    ConfiguracionResponse datos = servicio.ver().datos();

    assertFalse(datos.bloqueado());
    assertEquals(ConfiguracionSanta.OPERARIOS_FABRICA, datos.operarios());
    assertEquals(ConfiguracionSanta.BULTOS_HORA_FABRICA, datos.bultosHora());
    assertNull(datos.actualizadoPor());
  }

  /** Configuración de fábrica con otros operarios, bultos por hora y cluster de las rampas. */
  static ConfiguracionRequest pedido(int operarios, int bultosHora, int clusterRampas) {
    ConfiguracionSanta fabrica = ConfiguracionSanta.fabrica();
    List<RampaRequest> rampas =
        fabrica.rampas().stream()
            .map((r) -> new RampaRequest(r.numero(), clusterRampas, r.posiciones()))
            .toList();
    List<PasilloRequest> pasillos =
        fabrica.pasillos().stream().map((p) -> new PasilloRequest(p.numero(), p.cluster())).toList();
    return new ConfiguracionRequest(
        operarios,
        bultosHora,
        List.of("RAMPAS_PRIMERO", "PASILLOS_PRIMERO", "PASILLOS_PRIMERO"),
        rampas,
        pasillos);
  }
}
