package oracle.simulacion.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import oracle.simulacion.model.TipoRuta;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Escrituras por lotes con un JdbcTemplate simulado (sin BD). */
class SimulacionLotesRepositoryTest {

  @Test
  @SuppressWarnings("unchecked")
  void insertaNombresCanonicosSinDuplicarPorEspaciosNiMayusculas() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ArgumentCaptor<Collection<String>> insertados = ArgumentCaptor.forClass(Collection.class);
    when(jdbc.batchUpdate(startsWith("INSERT INTO chutes"), insertados.capture(), anyInt(), any()))
        .thenReturn(new int[0][]);
    // La BD responde con la fila que ya existía con otra capitalización/tilde (colación ci/ai).
    doAnswer(
            (i) -> {
              RowCallbackHandler manejador = i.getArgument(1);
              manejador.processRow(fila(5L, "Chuté 1"));
              manejador.processRow(fila(6L, "CHUTE 2"));
              return null;
            })
        .when(jdbc)
        .query(startsWith("SELECT id, nombre FROM chutes"), any(RowCallbackHandler.class), any(Object[].class));

    Map<String, Long> ids =
        new SimulacionLotesRepository(jdbc)
            .asegurarChutes(List.of("CHUTE  1", " chute 1 ", "CHUTE 2", "Chute 2"));

    assertEquals(List.of("CHUTE 1", "CHUTE 2"), new ArrayList<>(insertados.getValue()));
    assertEquals(Map.of("CHUTE 1", 5L, "CHUTE 2", 6L), ids);
  }

  @Test
  void faltaUnIdDelCatalogoEsUnErrorClaro() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(), any())).thenReturn(new int[0][]);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> new SimulacionLotesRepository(jdbc).asegurarClusters(List.of("CL1")));

    assertEquals("No se pudo registrar clusters CL1", error.getMessage());
  }

  private static ResultSet fila(long id, String nombre) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("id")).thenReturn(id);
    when(rs.getString("nombre")).thenReturn(nombre);
    return rs;
  }
}
