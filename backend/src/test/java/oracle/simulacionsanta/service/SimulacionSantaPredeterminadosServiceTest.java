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
import oracle.simulacionsanta.repository.Candado;
import oracle.simulacionsanta.repository.PredeterminadosGuardados;
import oracle.simulacionsanta.repository.SimulacionSantaLotesRepository;
import oracle.simulacionsanta.request.ConfiguracionRequest;
import oracle.simulacionsanta.request.ConfiguracionRequest.PasilloRequest;
import oracle.simulacionsanta.request.ConfiguracionRequest.RampaRequest;
import oracle.simulacionsanta.response.ConfiguracionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Candado de la configuración de Santa Anita: ver, bloquear y desbloquear, sin BD. */
class SimulacionSantaPredeterminadosServiceTest {

  private static final Instant EN = Instant.parse("2026-11-17T10:00:00Z");
  private static final List<String> ORDEN =
      List.of("RAMPAS_PRIMERO", "PASILLOS_PRIMERO", "PASILLOS_PRIMERO");

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
    when(lotes.aplicarPredeterminados(eq(9), eq(150), eq(ORDEN), any(), any())).thenReturn(2);
    when(lotes.predeterminados(false))
        .thenReturn(guardados(9, 150, new Candado(true, "Ana Pérez", EN)));

    ApiResponse<ConfiguracionResponse> respuesta = servicio.bloquear(pedido(9, 150, 2));

    // Toda la configuración queda como predeterminada, con candado, y se aplica completa.
    verify(lotes)
        .guardarPredeterminados(eq(9), eq(150), eq(ORDEN), any(), eq(true), any(), any());
    verify(lotes).aplicarPredeterminados(eq(9), eq(150), eq(ORDEN), any(), any());
    ConfiguracionResponse datos = respuesta.datos();
    assertNull(datos.registroId());
    assertTrue(datos.bloqueado());
    assertEquals("Ana Pérez", datos.bloqueadoPor());
    assertEquals(EN, datos.bloqueadoEn());
    assertEquals(9, datos.operarios());
    assertEquals(
        "Configuración bloqueada para todos los registros;"
            + " se aplicó a 2 registro(s) pendiente(s).",
        respuesta.mensaje());
  }

  @Test
  void bloquearSinPendientesNoMencionaLaCuenta() {
    when(lotes.aplicarPredeterminados(anyInt(), anyInt(), anyList(), any(), any())).thenReturn(0);
    when(lotes.predeterminados(false))
        .thenReturn(guardados(6, 120, new Candado(true, "Ana Pérez", EN)));

    ApiResponse<ConfiguracionResponse> respuesta = servicio.bloquear(pedido(6, 120, 1));

    assertEquals("Configuración bloqueada para todos los registros.", respuesta.mensaje());
  }

  @Test
  void bloquearValidaAntesDeEscribir() {
    assertThrows(SolicitudInvalidaException.class, () -> servicio.bloquear(pedido(0, 120, 1)));

    verify(lotes, never())
        .guardarPredeterminados(anyInt(), anyInt(), anyList(), anyList(), anyBoolean(), any(), any());
    verify(lotes, never()).aplicarPredeterminados(anyInt(), anyInt(), anyList(), any(), any());
  }

  @Test
  void desbloquearConservaLosValoresYVaciaQuienYCuando() {
    when(lotes.predeterminados(false))
        .thenReturn(
            new PredeterminadosGuardados(
                9,
                150,
                List.of("PASILLOS_PRIMERO", "PASILLOS_PRIMERO", "RAMPAS_PRIMERO"),
                7L,
                EN,
                Candado.ABIERTO,
                List.of()));

    ApiResponse<ConfiguracionResponse> respuesta = servicio.desbloquear();

    verify(lotes).desbloquearPredeterminados(any(), any());
    verify(lotes, never())
        .guardarPredeterminados(anyInt(), anyInt(), anyList(), anyList(), anyBoolean(), any(), any());
    verify(lotes, never()).aplicarPredeterminados(anyInt(), anyInt(), anyList(), any(), any());
    ConfiguracionResponse datos = respuesta.datos();
    assertFalse(datos.bloqueado());
    assertNull(datos.bloqueadoPor());
    assertNull(datos.bloqueadoEn());
    assertNull(datos.registroId());
    assertEquals(9, datos.operarios());
    assertEquals(150, datos.bultosHora());
    assertEquals(OrdenLlenado.RAMPAS_PRIMERO, datos.ordenClusters().get(2));
    assertEquals("Configuración desbloqueada: cámbiala y vuelve a bloquearla.", respuesta.mensaje());
  }

  @Test
  void verDevuelveElCandadoConQuienYCuando() {
    when(lotes.predeterminados(false))
        .thenReturn(guardados(8, 130, new Candado(true, "Ana Pérez", EN)));

    ConfiguracionResponse datos = servicio.ver().datos();

    assertTrue(datos.bloqueado());
    assertEquals("Ana Pérez", datos.bloqueadoPor());
    assertEquals(EN, datos.bloqueadoEn());
    assertEquals(8, datos.operarios());
  }

  @Test
  void sinLaFilaSonLosDeFabricaAbiertos() {
    ConfiguracionResponse datos = servicio.ver().datos();

    assertFalse(datos.bloqueado());
    assertNull(datos.bloqueadoPor());
    assertNull(datos.bloqueadoEn());
    assertEquals(ConfiguracionSanta.OPERARIOS_FABRICA, datos.operarios());
    assertEquals(ConfiguracionSanta.BULTOS_HORA_FABRICA, datos.bultosHora());
    assertNull(datos.actualizadoPor());
  }

  private static PredeterminadosGuardados guardados(int operarios, int bultosHora, Candado candado) {
    return new PredeterminadosGuardados(operarios, bultosHora, ORDEN, 3L, EN, candado, List.of());
  }

  /** Configuración de fábrica con otros operarios, bultos por hora y cluster de las rampas. */
  static ConfiguracionRequest pedido(int operarios, int bultosHora, int clusterRampas) {
    return pedido(operarios, bultosHora, clusterRampas, ORDEN, null);
  }

  /**
   * Configuración de fábrica con otros operarios, bultos por hora, cluster de las rampas, orden
   * de llenado y, si no es null, otras posiciones en todas las rampas.
   */
  static ConfiguracionRequest pedido(
      int operarios, int bultosHora, int clusterRampas, List<String> orden, Integer posiciones) {
    ConfiguracionSanta fabrica = ConfiguracionSanta.fabrica();
    List<RampaRequest> rampas =
        fabrica.rampas().stream()
            .map(
                (r) ->
                    new RampaRequest(
                        r.numero(), clusterRampas, posiciones == null ? r.posiciones() : posiciones))
            .toList();
    List<PasilloRequest> pasillos =
        fabrica.pasillos().stream().map((p) -> new PasilloRequest(p.numero(), p.cluster())).toList();
    return new ConfiguracionRequest(operarios, bultosHora, orden, rampas, pasillos);
  }
}
