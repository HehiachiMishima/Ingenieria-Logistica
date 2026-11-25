package oracle.simulacion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import oracle.common.config.CacheRespuestas;
import oracle.common.config.Transacciones;
import oracle.simulacion.diccionario.EscenarioDiccionario;
import oracle.simulacion.repository.SimulacionLotesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * El escenario de Configuración contra un PostgreSQL de verdad: solo rutas completas, chute al
 * azar fijo y fuera de los bloqueados, cambios del Registro y clusters cerrados. Apagada por
 * defecto; se corre con una base desechable con el esquema cargado (su nombre debe decir
 * «prueba», para no tocar nunca una base real):
 *
 * <pre>
 * ./mvnw -o test -Dtest=EscenarioDiccionarioIT \
 *   "-Descenario.pg.url=jdbc:postgresql://127.0.0.1:54329/escenario_prueba?currentSchema=seguridad,bitacora,simulacion,malla,simulacion_santa,public" \
 *   -Descenario.pg.usuario=prueba
 * </pre>
 */
@EnabledIfSystemProperty(named = "escenario.pg.url", matches = ".*prueba.*")
class EscenarioDiccionarioIT {

  private JdbcTemplate jdbc;
  private Transacciones transacciones;
  private EscenarioDiccionario escenario;
  private final Map<Integer, Long> chutes = new TreeMap<>();

  @BeforeEach
  void preparar() {
    var fuente =
        new DriverManagerDataSource(
            System.getProperty("escenario.pg.url"), System.getProperty("escenario.pg.usuario", "prueba"), "");
    jdbc = new JdbcTemplate(fuente);
    transacciones = new Transacciones(new DataSourceTransactionManager(fuente));
    jdbc.execute(
        "TRUNCATE simulacion.archivos_excel, simulacion.diccionario_rutas, simulacion.diccionario_clusters,"
            + " simulacion.chutes, simulacion.clusters RESTART IDENTITY CASCADE");
    jdbc.update(
        "INSERT INTO seguridad.usuarios (id, usuario, contrasena, nombre, apellido, email, actualizado_por)"
            + " OVERRIDING SYSTEM VALUE VALUES (1, 'prueba', 'x', 'Prueba', 'Prueba', 'p@x.pe', 1)"
            + " ON CONFLICT DO NOTHING");
    for (int n = 1; n <= 5; n++) {
      chutes.put(n, jdbc.queryForObject("INSERT INTO simulacion.chutes (nombre) VALUES (?) RETURNING id", Long.class, String.valueOf(n)));
    }
    // Plano de 5 chutes con el 3 bloqueado: nunca debe recibir rutas.
    PlanoChutes plano = PlanoChutes.fijo(1, 5, Set.of(3), chutes);
    escenario =
        new EscenarioDiccionario(
            jdbc, new SimulacionLotesRepository(jdbc), plano, transacciones, mock(CacheRespuestas.class));
  }

  private long cluster(int orden) {
    return jdbc.queryForObject(
        "INSERT INTO simulacion.diccionario_clusters (orden, nombre) VALUES (?, ?) RETURNING id",
        Long.class,
        orden,
        "CL" + orden);
  }

  private long ruta(String codigo, Long cluster, String tipo, String frecuencia, String grupo, String... locales) {
    long id =
        jdbc.queryForObject(
            "INSERT INTO simulacion.diccionario_rutas (codigo_ruta, tipo, cluster_id, frecuencia, grupo, creado_por,"
                + " actualizado_por) VALUES (?, ?, ?, ?, ?, 1, 1) RETURNING id",
            Long.class,
            codigo,
            tipo,
            cluster,
            frecuencia,
            grupo);
    for (int i = 0; i < locales.length; i++) {
      jdbc.update(
          "INSERT INTO simulacion.diccionario_locales (ruta_id, orden, cod, bultos, unidades, lineas)"
              + " VALUES (?, ?, ?, ?, 10, 1)",
          id,
          i + 1,
          locales[i],
          new BigDecimal("12.50"));
    }
    return id;
  }

  private Map<String, Object> fila(long rutaId) {
    List<Map<String, Object>> filas =
        jdbc.queryForList("SELECT * FROM simulacion.rutas_proyeccion WHERE diccionario_ruta_id = ?", rutaId);
    return filas.isEmpty() ? null : filas.getFirst();
  }

  @Test
  void elEscenarioSoloTieneLasRutasCompletasYSigueAlRegistro() {
    long cl1 = cluster(1);
    long cl2 = cluster(2);
    long completa = ruta("2001", cl1, "B2C", "LXV", "1", "4895", "4963");
    long sinFrecuencia = ruta("2002", cl1, "B2C", null, null, "5660");
    long enDatos = ruta("2003", null, null, null, null, "2514");
    long diaria = ruta("2004", cl2, "B2B", "DIARIA", "A", "2526");

    long archivo = transacciones.ejecutar(() -> escenario.asegurar(1L));

    // Solo entran las completas, con su chute al azar (que no es el bloqueado) y sus locales.
    assertThat(jdbc.queryForObject("SELECT count(*) FROM simulacion.rutas_proyeccion WHERE archivo_id = ?", Integer.class, archivo))
        .isEqualTo(2);
    assertThat(fila(sinFrecuencia)).isNull();
    assertThat(fila(enDatos)).isNull();
    Map<String, Object> f1 = fila(completa);
    assertThat(f1.get("chute_id")).isEqualTo(f1.get("chute_original_id")).isNotEqualTo(chutes.get(3));
    assertThat(f1.get("dias")).isEqualTo("L-X-V");
    assertThat((BigDecimal) f1.get("total_bultos")).isEqualByComparingTo("25.00");
    assertThat(f1.get("conteo_cod")).isEqualTo(2);
    assertThat(fila(diaria).get("dias")).isEqualTo("L-M-X-J-V-S");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM simulacion.rutas_locales", Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT total_rutas FROM simulacion.archivos_excel WHERE id = ?", Integer.class, archivo))
        .isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM simulacion.parametros_clusters WHERE archivo_id = ?", Integer.class, archivo))
        .isEqualTo(6);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM simulacion.parametros_chutes WHERE archivo_id = ?", Integer.class, archivo))
        .isEqualTo(4);
    // Crear otra vez no duplica.
    assertThat(transacciones.ejecutar(() -> escenario.asegurar(1L))).isEqualTo(archivo);

    // Se completa la 2002: entra; la 2001 conserva su chute.
    Object chute2001 = f1.get("chute_id");
    jdbc.update("UPDATE simulacion.diccionario_rutas SET frecuencia = 'MJS', grupo = '2' WHERE id = ?", sinFrecuencia);
    transacciones.ejecutar(() -> { escenario.sincronizar(archivo, List.of(sinFrecuencia), 1L); return null; });
    assertThat(fila(sinFrecuencia)).isNotNull();
    assertThat(fila(sinFrecuencia).get("dias")).isEqualTo("M-J-S");
    assertThat(fila(completa).get("chute_id")).isEqualTo(chute2001);

    // La 2004 pasa de CL2 a CL1 en el Registro: cambia de cluster y conserva su chute.
    Object chute2004 = fila(diaria).get("chute_id");
    jdbc.update("UPDATE simulacion.diccionario_rutas SET cluster_id = ? WHERE id = ?", cl1, diaria);
    transacciones.ejecutar(() -> { escenario.sincronizar(archivo, List.of(diaria), 1L); return null; });
    Long cl1Catalogo = jdbc.queryForObject("SELECT id FROM simulacion.clusters WHERE nombre = 'CL1'", Long.class);
    assertThat(fila(diaria).get("cluster_id")).isEqualTo(cl1Catalogo);
    assertThat(fila(diaria).get("cluster_original_id")).isEqualTo(cl1Catalogo);
    assertThat(fila(diaria).get("chute_id")).isEqualTo(chute2004);

    // La 2001 vuelve a Datos: sale del escenario.
    jdbc.update(
        "UPDATE simulacion.diccionario_rutas SET cluster_id = NULL, tipo = NULL, frecuencia = NULL, grupo = NULL WHERE id = ?",
        completa);
    transacciones.ejecutar(() -> { escenario.sincronizar(archivo, List.of(completa), 1L); return null; });
    assertThat(fila(completa)).isNull();

    // CL1 se cierra en Configuración: el Registro lo ve cerrado y sus filas no se tocan.
    jdbc.update(
        "UPDATE simulacion.parametros_clusters SET estado = 'CERRADO' WHERE archivo_id = ? AND cluster_id = ?",
        archivo,
        cl1Catalogo);
    assertThat(escenario.clustersCerrados(archivo)).containsExactly(cl1);
    jdbc.update("UPDATE simulacion.diccionario_rutas SET grupo = '9' WHERE id = ?", sinFrecuencia);
    transacciones.ejecutar(() -> { escenario.sincronizar(archivo, null, 1L); return null; });
    assertThat(fila(sinFrecuencia).get("grupo")).isEqualTo("2");

    // Eliminar en el Registro la saca del escenario (en cascada) y los totales se ponen al día.
    jdbc.update(
        "UPDATE simulacion.parametros_clusters SET estado = 'PENDIENTE' WHERE archivo_id = ?", archivo);
    jdbc.update("DELETE FROM simulacion.diccionario_rutas WHERE id = ?", diaria);
    transacciones.ejecutar(() -> { escenario.sincronizar(archivo, List.of(diaria), 1L); return null; });
    assertThat(fila(diaria)).isNull();
    assertThat(jdbc.queryForObject("SELECT total_rutas FROM simulacion.archivos_excel WHERE id = ?", Integer.class, archivo))
        .isEqualTo(1);
  }
}
