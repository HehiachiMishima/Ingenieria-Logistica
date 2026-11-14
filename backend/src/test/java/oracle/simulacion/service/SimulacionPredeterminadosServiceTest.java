package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import oracle.common.config.CacheRespuestas;
import oracle.common.config.Transacciones;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import oracle.simulacion.model.Chute;
import oracle.simulacion.model.Predeterminados;
import oracle.simulacion.repository.ChuteRepository;
import oracle.simulacion.repository.SimulacionLotesRepository;
import oracle.simulacion.request.PredeterminadosRequest;
import oracle.simulacion.response.PredeterminadosResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Velocidad y buff predeterminados: bloquear, desbloquear y validar los chutes, sin BD. */
class SimulacionPredeterminadosServiceTest {

  private SimulacionLotesRepository lotes;
  private ChuteRepository chuteRepo;
  private SimulacionPredeterminadosService servicio;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void preparar() {
    lotes = mock(SimulacionLotesRepository.class);
    chuteRepo = mock(ChuteRepository.class);
    Transacciones transacciones = mock(Transacciones.class);
    when(transacciones.ejecutar(any())).thenAnswer((i) -> ((Supplier<Object>) i.getArgument(0)).get());
    when(transacciones.consultar(any())).thenAnswer((i) -> ((Supplier<Object>) i.getArgument(0)).get());
    servicio =
        new SimulacionPredeterminadosService(
            lotes,
            chuteRepo,
            PlanoChutes.fijo(1, 33, Set.of(19, 20, 21), Map.of()),
            transacciones,
            new CacheRespuestas(false, 1, 1));
  }

  @Test
  void bloquearGuardaLosValoresYLosLlevaALosRegistrosSinBalancear() {
    when(chuteRepo.findAllById(any())).thenReturn(List.of(chute(2L, "CHUTE 2"), chute(5L, "5")));
    when(lotes.aplicarPredeterminados(any(), anyCollection())).thenReturn(3);

    ApiResponse<PredeterminadosResponse> respuesta =
        servicio.bloquear(new PredeterminadosRequest(new BigDecimal("120.00"), List.of(5L, 2L, 5L)));

    // Sin repetidos y en orden.
    verify(lotes).guardarPredeterminados(new BigDecimal("120.00"), List.of(2L, 5L), true);
    verify(lotes).aplicarPredeterminados(new BigDecimal("120.00"), List.of(2L, 5L));
    assertTrue(respuesta.datos().bloqueado());
    assertEquals(List.of(2L, 5L), respuesta.datos().chutesBuff());
    assertTrue(respuesta.mensaje().contains("3 registro(s) sin balancear"), respuesta.mensaje());
  }

  @Test
  void unChuteBloqueadoOInexistenteNoPuedeTenerBuff() {
    when(chuteRepo.findAllById(List.of(19L))).thenReturn(List.of(chute(19L, "CHUTE 19")));
    when(chuteRepo.findAllById(List.of(99L))).thenReturn(List.of());

    SolicitudInvalidaException bloqueado =
        assertThrows(
            SolicitudInvalidaException.class,
            () -> servicio.bloquear(new PredeterminadosRequest(new BigDecimal("100"), List.of(19L))));
    SolicitudInvalidaException inexistente =
        assertThrows(
            SolicitudInvalidaException.class,
            () -> servicio.bloquear(new PredeterminadosRequest(new BigDecimal("100"), List.of(99L))));

    assertTrue(bloqueado.getMessage().contains("bloqueado"), bloqueado.getMessage());
    assertTrue(inexistente.getMessage().contains("no existe"), inexistente.getMessage());
    verify(lotes, never()).guardarPredeterminados(any(), anyCollection(), anyBoolean());
    verify(lotes, never()).aplicarPredeterminados(any(), anyCollection());
  }

  @Test
  void desbloquearConservaLosValores() {
    when(lotes.leerPredeterminados())
        .thenReturn(new Predeterminados(new BigDecimal("120.00"), List.of(2L), false));

    PredeterminadosResponse datos = servicio.desbloquear().datos();

    verify(lotes).desbloquearPredeterminados();
    assertFalse(datos.bloqueado());
    assertEquals(new BigDecimal("120.00"), datos.velocidad());
    assertEquals(List.of(2L), datos.chutesBuff());
  }

  @Test
  void sinLaFilaSonLosDeSiempre() {
    assertSame(Predeterminados.INICIALES, SimulacionPredeterminadosService.actuales(lotes));
    assertEquals(0, new BigDecimal("100").compareTo(servicio.ver().datos().velocidad()));
    assertFalse(servicio.ver().datos().bloqueado());
  }

  private static Chute chute(Long id, String nombre) {
    Chute chute = new Chute();
    chute.setId(id);
    chute.setNombre(nombre);
    return chute;
  }
}
