package oracle.simulacionsanta.service;

import static oracle.simulacionsanta.service.SimulacionSantaPredeterminadosServiceTest.pedido;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import oracle.common.exception.ConflictoException;
import oracle.common.response.ApiResponse;
import oracle.simulacionsanta.response.ConfiguracionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * El candado contra PostgreSQL real (la misma base que usa contextLoads). Cada prueba corre en
 * una transacción que se revierte al terminar: no deja registros ni cambia los predeterminados.
 * Los servicios se unen a esa transacción, así que solo se lee por JDBC lo que también se
 * escribió por JDBC (lo de Hibernate se vuelve a leer con Hibernate).
 */
@SpringBootTest
@Transactional
class SimulacionSantaCandadoBaseDatosTest {

  @Autowired private SimulacionSantaPredeterminadosService predeterminados;
  @Autowired private SimulacionSantaConfiguracionService configuracion;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;

  @Test
  void bloquearAplicaALosPendientesYNoALosEnCursoYDesbloquearConservaLosValores()
      throws Exception {
    Long usuario = usuario();
    long pendiente = insertarRegistro(usuario, 0);
    long enCurso = insertarRegistro(usuario, 1);

    ApiResponse<ConfiguracionResponse> bloqueo = predeterminados.bloquear(pedido(97, 9876, 2));

    assertTrue(bloqueo.datos().bloqueado());
    assertTrue(bloqueo.mensaje().contains("registro(s) pendiente(s)."), bloqueo.mensaje());
    assertEquals(List.of(97, 9876), valores(pendiente));
    assertEquals(List.of(6, 120), valores(enCurso));
    assertEquals(
        Boolean.TRUE,
        jdbc.queryForObject(
            "SELECT bloqueado FROM simulacion_santa.predeterminados WHERE id = 1", Boolean.class));
    // Los registros nuevos nacen con todo lo guardado, también los clusters.
    assertEquals(
        Integer.valueOf(2),
        jdbc.queryForObject(
            "SELECT cluster FROM simulacion_santa.predeterminados_ubicaciones"
                + " WHERE tipo = 'RAMPA' AND numero = 15",
            Integer.class));

    ConfiguracionResponse delPendiente = configuracion.configuracion(pendiente).datos();
    assertTrue(delPendiente.bloqueado());
    assertEquals(97, delPendiente.operarios());
    assertTrue(json.writeValueAsString(delPendiente).contains("\"bloqueado\":true"));

    ConfiguracionResponse abiertos = predeterminados.desbloquear().datos();

    assertFalse(abiertos.bloqueado());
    assertEquals(97, abiertos.operarios());
    assertEquals(9876, abiertos.bultosHora());
    assertEquals(2, abiertos.rampas().getFirst().cluster());
    assertFalse(predeterminados.ver().datos().bloqueado());
    assertFalse(configuracion.configuracion(enCurso).datos().bloqueado());
    assertEquals(List.of(97, 9876), valores(pendiente));
  }

  @Test
  void conCandadoSeCambianClustersPeroNoOperarios() {
    long pendiente = insertarRegistro(usuario(), 0);
    predeterminados.bloquear(pedido(97, 9876, 1));

    ConfiguracionResponse guardada =
        configuracion.guardarConfiguracion(pendiente, pedido(97, 9876, 3)).datos();

    assertTrue(guardada.bloqueado());
    assertEquals(3, guardada.rampas().getFirst().cluster());
    assertEquals(
        Integer.valueOf(3),
        jdbc.queryForObject(
            "SELECT cluster FROM simulacion_santa.ubicaciones"
                + " WHERE registro_id = ? AND tipo = 'RAMPA' AND numero = 15",
            Integer.class,
            pendiente));

    // Al final: la excepción deja la transacción de la prueba marcada para revertir.
    ConflictoException error =
        assertThrows(
            ConflictoException.class,
            () -> configuracion.guardarConfiguracion(pendiente, pedido(96, 9876, 3)));
    assertEquals(SimulacionSantaPredeterminadosService.MENSAJE_BLOQUEADOS, error.getMessage());
  }

  // ------------------------------------------------------------------

  private Long usuario() {
    Long id = jdbc.queryForObject("SELECT min(id) FROM seguridad.usuarios", Long.class);
    assumeTrue(id != null, "La base no tiene usuarios para creado_por.");
    return id;
  }

  /** Un registro mínimo (1 paleta en C1) con 6 operarios a 120 bultos/hora. */
  private long insertarRegistro(Long usuario, int envio) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO simulacion_santa.registros
                (nombre, nombre_archivo, envio_actual, paletas, rutas, paletas_cluster1,
                 operarios, bultos_hora, orden_cluster1, orden_cluster2, orden_cluster3,
                 configurado_por, creado_por, actualizado_por)
            VALUES ('prueba candado', 'prueba.csv', ?, 1, 1, 1, 6, 120,
                    'RAMPAS_PRIMERO', 'PASILLOS_PRIMERO', 'PASILLOS_PRIMERO', ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            envio,
            usuario,
            usuario,
            usuario);
    assertNotNull(id);
    return id;
  }

  private List<Integer> valores(long registroId) {
    return jdbc.queryForObject(
        "SELECT operarios, bultos_hora FROM simulacion_santa.registros WHERE id = ?",
        (rs, i) -> List.of(rs.getInt(1), rs.getInt(2)),
        registroId);
  }
}
