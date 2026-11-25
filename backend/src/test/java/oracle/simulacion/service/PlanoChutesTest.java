package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import oracle.common.config.Transacciones;
import oracle.simulacion.model.TipoRuta;
import oracle.simulacion.repository.ArchivoExcelRepository;
import oracle.simulacion.repository.SimulacionLotesRepository;
import org.junit.jupiter.api.Test;

/** Plano de chutes 1–33 con 19, 20 y 21 bloqueados. */
class PlanoChutesTest {

  private final PlanoChutes plano =
      PlanoChutes.fijo(1, 33, Set.of(19, 20, 21), Map.of(1, 101L, 12, 112L, 19, 119L, 33, 133L));

  @Test
  void elNumeroSeLeeDelNombre() {
    assertEquals(19, PlanoChutes.numero("19"));
    assertEquals(19, PlanoChutes.numero("CHUTE 19"));
    assertEquals(19, PlanoChutes.numero(" chute  019 "));
    assertEquals(5, PlanoChutes.numero("Chute5"));
    assertNull(PlanoChutes.numero("RAMPA 3"));
    assertNull(PlanoChutes.numero(null));
  }

  @Test
  void bloqueadosYFueraDelPlanoNoSirven() {
    assertTrue(plano.bloqueado("CHUTE 20"));
    assertFalse(plano.bloqueado("18"));
    assertNull(plano.problema("CHUTE 22"));
    assertEquals("el CHUTE 21 está bloqueado", plano.problema("21"));
    assertEquals("no está entre el CHUTE 1 y el CHUTE 33", plano.problema("34"));
    assertEquals("no está entre el CHUTE 1 y el CHUTE 33", plano.problema("RAMPA"));
    assertEquals("5", plano.nombreCanonico("CHUTE 05"));
  }

  @Test
  void nombresConPrefijoYSeparadores() {
    assertEquals(5, PlanoChutes.numero("CH 5"));
    assertEquals(5, PlanoChutes.numero("CHUTE-5"));
    assertEquals(5, PlanoChutes.numero("CHUTE N° 5"));
    assertEquals(5, PlanoChutes.numero("Chute Nº 05"));
    assertEquals(5, PlanoChutes.numero("#5"));
  }

  @Test
  void losVisiblesNoIncluyenBloqueados() {
    assertEquals(List.of(101L, 112L, 133L), plano.idsVisibles());
  }

}
