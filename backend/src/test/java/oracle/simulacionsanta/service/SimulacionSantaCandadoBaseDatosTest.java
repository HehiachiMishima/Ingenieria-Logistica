package oracle.simulacionsanta.service;

import static oracle.simulacionsanta.service.SimulacionSantaPredeterminadosServiceTest.pedido;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;
import oracle.common.exception.ConflictoException;
import oracle.common.response.ApiResponse;
import oracle.simulacionsanta.repository.SimulacionSantaLotesRepository;
import oracle.simulacionsanta.request.EnvioRequest;
import oracle.simulacionsanta.response.ConfiguracionResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * El candado contra PostgreSQL real (la misma base que usa contextLoads). Cada prueba corre en
 * una transacción que se revierte al terminar: no deja registros ni cambia los predeterminados.
 * Los servicios se unen a esa transacción, así que solo se lee por JDBC lo que también se
 * escribió por JDBC (lo de Hibernate se vuelve a leer con Hibernate).
 */
@SpringBootTest
@AutoConfigureMockMvc // mismo contexto que SimulacionSantaDashboardBaseDatosTest
@Transactional
class SimulacionSantaCandadoBaseDatosTest {

  private static final List<String> ORDEN_NUEVO =
      List.of("PASILLOS_PRIMERO", "RAMPAS_PRIMERO", "RAMPAS_PRIMERO");
  private static final Pattern INSTANTE_UTC =
      Pattern.compile("\"bloqueadoEn\":\"\\d{4}-\\d{2}-\\d{2}T[^\"]*Z\"");

  @Autowired private SimulacionSantaPredeterminadosService predeterminados;
  @Autowired private SimulacionSantaConfiguracionService configuracion;
  @Autowired private SimulacionSantaLotesRepository lotes;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;

  private Long usuario;
  private String nombreUsuario;

  @BeforeEach
  void autenticar() {
    List<Long> ids = jdbc.queryForList("SELECT id FROM seguridad.usuarios ORDER BY id LIMIT 1", Long.class);
    assumeTrue(!ids.isEmpty(), "La base no tiene usuarios para creado_por.");
    usuario = ids.getFirst();
    // El nombre que muestra la app: nombre y apellido.
    nombreUsuario =
        jdbc.queryForObject(
            "SELECT nombre, apellido, usuario FROM seguridad.usuarios WHERE id = ?",
            (rs, i) -> {
              String visible = (rs.getString(1) + " " + rs.getString(2)).strip();
              return visible.isEmpty() ? rs.getString(3) : visible;
            },
            usuario);
    Jwt jwt =
        Jwt.withTokenValue("prueba").header("alg", "none").subject(String.valueOf(usuario)).build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
  }

  @AfterEach
  void cerrarSesion() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void bloquearAplicaLaConfiguracionCompletaALosPendientesYNoALosEnCurso() throws Exception {
    long pendiente = insertarRegistro(0);
    long enCurso = insertarRegistro(1);

    ApiResponse<ConfiguracionResponse> bloqueo =
        predeterminados.bloquear(pedido(97, 9876, 2, ORDEN_NUEVO, 5));

    ConfiguracionResponse datos = bloqueo.datos();
    assertTrue(datos.bloqueado());
    assertEquals(nombreUsuario, datos.bloqueadoPor());
    assertEquals(actualizadoEnPredeterminados(), datos.bloqueadoEn());
    assertTrue(bloqueo.mensaje().startsWith("Configuración bloqueada para todos los registros; se aplicó a "),
        bloqueo.mensaje());
    // PENDIENTE: toda la configuración (valores, orden, cluster y posiciones de cada rampa).
    assertEquals(List.of("97", "9876", "PASILLOS_PRIMERO", "RAMPAS_PRIMERO", "RAMPAS_PRIMERO", String.valueOf(usuario)),
        fila(pendiente));
    assertEquals(List.of(2, 5), rampa15(pendiente));
    // EN_CURSO: conserva la suya.
    assertEquals(List.of("6", "120", "RAMPAS_PRIMERO", "PASILLOS_PRIMERO", "PASILLOS_PRIMERO", "0"),
        fila(enCurso));
    assertEquals(List.of(1, 10), rampa15(enCurso));

    ConfiguracionResponse delPendiente = configuracion.configuracion(pendiente).datos();
    assertTrue(delPendiente.bloqueado());
    assertEquals(nombreUsuario, delPendiente.bloqueadoPor());
    assertNotNull(delPendiente.bloqueadoEn());
    assertEquals(97, delPendiente.operarios());
    String cuerpo = json.writeValueAsString(delPendiente);
    assertTrue(cuerpo.contains("\"bloqueado\":true"), cuerpo);
    assertTrue(cuerpo.contains("\"bloqueadoPor\":" + json.writeValueAsString(nombreUsuario)), cuerpo);
    assertTrue(INSTANTE_UTC.matcher(cuerpo).find(), cuerpo);

    ConfiguracionResponse abierta = predeterminados.desbloquear().datos();

    assertFalse(abierta.bloqueado());
    assertNull(abierta.bloqueadoPor());
    assertNull(abierta.bloqueadoEn());
    assertEquals(97, abierta.operarios());
    assertEquals(5, abierta.rampas().getFirst().posiciones());
    String cuerpoAbierta = json.writeValueAsString(configuracion.configuracion(enCurso).datos());
    assertTrue(cuerpoAbierta.contains("\"bloqueado\":false"), cuerpoAbierta);
    assertTrue(cuerpoAbierta.contains("\"bloqueadoPor\":null"), cuerpoAbierta);
    assertTrue(cuerpoAbierta.contains("\"bloqueadoEn\":null"), cuerpoAbierta);
  }

  @Test
  void conCandadoCualquierGuardadoDeConfiguracionDa409PeroElEnvioSiCambia() {
    long pendiente = insertarRegistro(0);
    predeterminados.bloquear(pedido(97, 9876, 1));

    // Los envíos no tienen candado.
    assertEquals(1, configuracion.cambiarEnvio(pendiente, new EnvioRequest(1)).datos().envioActual());

    // Al final: la excepción deja la transacción de la prueba marcada para revertir.
    ConflictoException error =
        assertThrows(
            ConflictoException.class,
            () -> configuracion.guardarConfiguracion(pendiente, pedido(97, 9876, 3)));
    assertEquals("La configuración está bloqueada: desbloquéala para cambiarla.", error.getMessage());
  }

  // ------------------------------------------------------------------

  /** Un registro mínimo (1 paleta en C1) con la configuración de fábrica y actor 0. */
  private long insertarRegistro(int envio) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO simulacion_santa.registros
                (nombre, nombre_archivo, envio_actual, paletas, rutas, paletas_cluster1,
                 operarios, bultos_hora, orden_cluster1, orden_cluster2, orden_cluster3,
                 configurado_por, creado_por, actualizado_por)
            VALUES ('prueba candado', 'prueba.csv', ?, 1, 1, 1, 6, 120,
                    'RAMPAS_PRIMERO', 'PASILLOS_PRIMERO', 'PASILLOS_PRIMERO', 0, ?, 0)
            RETURNING id
            """,
            Long.class,
            envio,
            usuario);
    assertNotNull(id);
    lotes.guardarUbicaciones(id, ConfiguracionSanta.fabrica().ubicaciones());
    return id;
  }

  /** operarios, bultos_hora, orden de C1..C3 y configurado_por, como texto. */
  private List<String> fila(long registroId) {
    return jdbc.queryForObject(
        """
        SELECT operarios, bultos_hora, orden_cluster1, orden_cluster2, orden_cluster3, configurado_por
        FROM simulacion_santa.registros WHERE id = ?
        """,
        (rs, i) ->
            List.of(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6)),
        registroId);
  }

  /** Cluster y posiciones de la rampa 15 del registro. */
  private List<Integer> rampa15(long registroId) {
    return jdbc.queryForObject(
        """
        SELECT cluster, posiciones FROM simulacion_santa.ubicaciones
        WHERE registro_id = ? AND tipo = 'RAMPA' AND numero = 15
        """,
        (rs, i) -> List.of(rs.getInt(1), rs.getInt(2)),
        registroId);
  }

  private Instant actualizadoEnPredeterminados() {
    return jdbc.queryForObject(
            "SELECT actualizado_en FROM simulacion_santa.predeterminados WHERE id = 1",
            OffsetDateTime.class)
        .toInstant();
  }
}
