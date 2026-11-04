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
import oracle.simulacion.service.SimulacionExcel.FilaRuta;
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

  @Test
  @SuppressWarnings("unchecked")
  void insertarRutasResuelveIdsPorClaveYGuardaElCodigoCanonico() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ArgumentCaptor<ParameterizedPreparedStatementSetter<FilaRuta>> setter =
        ArgumentCaptor.forClass(ParameterizedPreparedStatementSetter.class);
    ArgumentCaptor<Collection<FilaRuta>> filas = ArgumentCaptor.forClass(Collection.class);
    when(jdbc.batchUpdate(startsWith("INSERT INTO rutas_proyeccion"), filas.capture(), eq(2_000), setter.capture()))
        .thenReturn(new int[0][]);
    FilaRuta ruta =
        new FilaRuta(
            "R  30",
            "Cl 1",
            "Chuté 1",
            TipoRuta.B2C,
            new BigDecimal("1.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            3);

    new SimulacionLotesRepository(jdbc)
        .insertarRutas(9L, List.of(ruta), Map.of("CL 1", 11L), Map.of("CHUTE 1", 22L));

    PreparedStatement ps = mock(PreparedStatement.class);
    setter.getValue().setValues(ps, ruta);
    verify(ps).setLong(1, 9L);
    verify(ps).setLong(2, 22L);
    verify(ps).setLong(3, 22L);
    verify(ps).setLong(4, 11L);
    verify(ps).setString(5, "R 30");
    verify(ps).setString(6, "B2C");
    verify(ps).setBigDecimal(7, new BigDecimal("1.00"));
    verify(ps).setInt(10, 3);
    // El cluster original se congela igual que el chute.
    verify(ps).setLong(11, 11L);

    FilaRuta huerfana =
        new FilaRuta(
            "R1",
            "CL9",
            "Chuté 1",
            TipoRuta.B2B,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            1);
    assertThrows(IllegalStateException.class, () -> setter.getValue().setValues(ps, huerfana));
  }

  private static ResultSet fila(long id, String nombre) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("id")).thenReturn(id);
    when(rs.getString("nombre")).thenReturn(nombre);
    return rs;
  }
}
