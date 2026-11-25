package oracle.simulacion.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import oracle.simulacion.repository.RutasDataRepository.Local;
import oracle.simulacion.repository.RutasDataRepository.RutaData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * El SQL de DATA contra un PostgreSQL de verdad, con el esquema de {@code postgresql.sql}. Solo
 * corre con una base de prueba (su URL debe contener «prueba»): nunca toca la del proyecto.
 *
 * <pre>
 * ./mvnw -o test -Dtest=RutasDataRepositoryIT -Dsurefire.failIfNoSpecifiedTests=false \
 *   "-Descenario.pg.url=jdbc:postgresql://127.0.0.1:54329/data_prueba?currentSchema=seguridad,bitacora,simulacion,malla,simulacion_santa,public" \
 *   -Descenario.pg.usuario=prueba
 * </pre>
 */
@EnabledIfSystemProperty(named = "escenario.pg.url", matches = ".*prueba.*")
class RutasDataRepositoryIT {

  private JdbcTemplate jdbc;
  private RutasDataRepository repo;
  private long archivo;
  private long cl1;
  private long chute1;
  private long chute2;

  @BeforeEach
  void preparar() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            System.getProperty("escenario.pg.url"), System.getProperty("escenario.pg.usuario", "prueba"), "");
    jdbc = new JdbcTemplate(ds);
    repo = new RutasDataRepository(jdbc);
    jdbc.execute("DELETE FROM simulacion.archivos_excel");
    jdbc.execute(
        "INSERT INTO seguridad.usuarios (usuario, contrasena, nombre, apellido, email, actualizado_por)"
            + " VALUES ('prueba', 'x', 'P', 'P', 'p@x', 0) ON CONFLICT DO NOTHING");
    long usuario = jdbc.queryForObject("SELECT id FROM seguridad.usuarios WHERE usuario = 'prueba'", Long.class);
    jdbc.execute("INSERT INTO simulacion.clusters (nombre) VALUES ('CL1'), ('CL2') ON CONFLICT DO NOTHING");
    jdbc.execute("INSERT INTO simulacion.chutes (nombre) VALUES ('1'), ('2') ON CONFLICT DO NOTHING");
    cl1 = jdbc.queryForObject("SELECT id FROM simulacion.clusters WHERE nombre = 'CL1'", Long.class);
    chute1 = jdbc.queryForObject("SELECT id FROM simulacion.chutes WHERE nombre = '1'", Long.class);
    chute2 = jdbc.queryForObject("SELECT id FROM simulacion.chutes WHERE nombre = '2'", Long.class);
    archivo =
        jdbc.queryForObject(
            "INSERT INTO simulacion.archivos_excel (nombre_registro, nombre_archivo, es_unico, creado_por, actualizado_por)"
                + " VALUES ('Simulación Titan', 'Escenario', TRUE, ?, ?) RETURNING id",
            Long.class,
            usuario,
            usuario);
    for (String b : List.of("LXV", "MJS", "DIA")) {
      jdbc.update(
          "INSERT INTO simulacion.parametros_clusters (archivo_id, balanceo, cluster_id, limite_horas) VALUES (?, ?, ?, 0)",
          archivo,
          b,
          cl1);
    }
  }

  @Test
  void unaRutaRecorreDataYUnCluster() {
    long id = repo.insertar(archivo, "2002", chute1, dec("15.50"), dec("100"), dec("3"), 2);
    repo.reemplazarLocales(id, List.of(local("A1", 2, "10.50"), local("B2", 1, "5")));

    RutaData enData = repo.rutas(archivo, List.of(id)).getFirst();
    assertTrue(enData.enData());
    assertFalse(enData.completa());
    assertTrue(repo.existe(archivo, "2002", null, null));
    assertFalse(repo.existe(archivo, "2002", null, id), "no choca consigo misma");
    assertFalse(repo.existe(archivo, "2002", "B2C", null));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM simulacion.rutas_locales WHERE ruta_id = ?", Integer.class, id));

    // Una ruta incompleta no puede quedar en un cluster (la base también lo exige).
    assertThrows(DataIntegrityViolationException.class, () -> repo.aCluster(archivo, List.of(id), cl1, chute2));

    assertEquals(1, repo.asignarTipo(archivo, List.of(id), "B2C"));
    assertEquals(1, repo.asignarFrecuencia(archivo, List.of(id), "LXV"));
    assertEquals(1, repo.asignarGrupo(archivo, List.of(id), "3"));
    assertEquals("L-X-V", jdbc.queryForObject("SELECT dias FROM simulacion.rutas_proyeccion WHERE id = ?", String.class, id));

    assertEquals(1, repo.aCluster(archivo, List.of(id), cl1, chute2));
    var fila =
        jdbc.queryForMap(
            "SELECT cluster_id, cluster_original_id, chute_id, chute_original_id, movida, cluster_cambiado"
                + " FROM simulacion.rutas_proyeccion WHERE id = ?",
            id);
    assertEquals(cl1, ((Number) fila.get("cluster_original_id")).longValue());
    assertEquals(chute2, ((Number) fila.get("chute_original_id")).longValue());
    assertEquals(false, fila.get("movida"));
    assertEquals(false, fila.get("cluster_cambiado"));
    RutasDataRepository.Totales t = repo.totales(archivo);
    assertEquals(1, t.rutas());
    assertEquals(0, dec("15.50").compareTo(t.bultos()));
    // Ya en un cluster: ni se edita ni se le asigna nada desde DATA.
    assertEquals(0, repo.asignarTipo(archivo, List.of(id), "B2B"));
    assertFalse(repo.actualizar(archivo, id, "2003", dec("1"), dec("1"), dec("1"), 1));
    assertFalse(repo.eliminar(archivo, id));

    // Vuelve a DATA (como al quitar su cluster) y se borra su historial manual.
    jdbc.update(
        "INSERT INTO simulacion.historial_balanceo (archivo_id, balanceo, tipo_accion, ruta_id, chute_origen_id,"
            + " chute_destino_id, motivo, creado_por) SELECT ?, 'LXV', 'MOVER_RUTA', ?, ?, ?, 'MANUAL', creado_por"
            + " FROM simulacion.archivos_excel WHERE id = ?",
        archivo,
        id,
        chute2,
        chute1,
        archivo);
    assertEquals(List.of(id), repo.devolverADataEnSuChute(archivo, List.of(cl1)));
    repo.borrarHistorialDeRutas(archivo, List.of(id));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM simulacion.historial_balanceo WHERE ruta_id = ?", Integer.class, id));
    RutaData deVuelta = repo.rutas(archivo, List.of(id)).getFirst();
    assertTrue(deVuelta.enData());
    assertTrue(deVuelta.completa(), "en DATA conserva tipo, frecuencia y grupo");
    assertEquals(0, repo.totales(archivo).rutas());

    repo.quitarClusters(archivo, List.of(cl1));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM simulacion.parametros_clusters WHERE archivo_id = ?", Integer.class, archivo));
    assertTrue(repo.eliminar(archivo, id));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM simulacion.rutas_locales WHERE ruta_id = ?", Integer.class, id));
  }

  @Test
  void elTipoNoDejaRutasRepetidas() {
    long a = repo.insertar(archivo, "2002", chute1, dec("1"), dec("1"), dec("1"), 1);
    long b = repo.insertar(archivo, "2015", chute1, dec("1"), dec("1"), dec("1"), 1);
    repo.asignarTipo(archivo, List.of(a), "B2C");
    long c = repo.insertar(archivo, "2002", chute2, dec("1"), dec("1"), dec("1"), 1);

    assertEquals(List.of("2002"), repo.repetidasConTipo(archivo, List.of(c), "B2C"));
    assertEquals(List.of(), repo.repetidasConTipo(archivo, List.of(c, b), "B2B"));
    assertEquals(List.of("2002"), repo.repetidasConTipo(archivo, List.of(a, c), "B2B"), "dos elegidas con el mismo número");
    // Dos sin tipo con el mismo número: la base lo impide.
    repo.asignarTipo(archivo, List.of(a), "B2B");
    assertThrows(
        DataIntegrityViolationException.class, () -> jdbc.update("UPDATE simulacion.rutas_proyeccion SET tipo = NULL WHERE id = ?", a));
    assertEquals(2, repo.aData(archivo, List.of(b, c), chute2));
  }

  private static BigDecimal dec(String valor) {
    return new BigDecimal(valor);
  }

  private static Local local(String cod, int filas, String bultos) {
    return new Local(cod, filas, dec(bultos), BigDecimal.ONE, BigDecimal.ONE);
  }
}
