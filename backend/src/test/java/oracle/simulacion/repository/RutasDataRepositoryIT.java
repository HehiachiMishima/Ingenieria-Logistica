package oracle.simulacion.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import oracle.simulacion.repository.RutasDataRepository.Local;
import oracle.simulacion.repository.RutasDataRepository.LocalGuardado;
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
    // En un cluster: tipo, número y locales se pueden cambiar (el servicio valida que no esté
    // cerrado); eliminar, solo en DATA.
    assertEquals(1, repo.asignarTipo(archivo, List.of(id), "B2B"));
    repo.asignarTipo(archivo, List.of(id), "B2C");
    assertTrue(repo.actualizar(archivo, id, "2002", dec("15.50"), dec("100"), dec("3"), 2));
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

    // Movida a mano: entra a la consulta de rutas cruzadas y puede tomar su cluster como original.
    long otra = repo.insertar(archivo, "2077", chute1, dec("1"), dec("1"), dec("1"), 1);
    repo.asignarTipo(archivo, List.of(otra), "B2C");
    repo.asignarFrecuencia(archivo, List.of(otra), "MJS");
    repo.asignarGrupo(archivo, List.of(otra), "1");
    repo.aCluster(archivo, List.of(otra), cl1, chute1);
    long cl2 = jdbc.queryForObject("SELECT id FROM simulacion.clusters WHERE nombre = 'CL2'", Long.class);
    jdbc.update("UPDATE simulacion.rutas_proyeccion SET cluster_id = ? WHERE id = ?", cl2, otra);
    RutaData cruzada = repo.rutasCruzadas(archivo, List.of(cl1)).getFirst();
    assertEquals(cl1, cruzada.clusterOriginalId());
    assertEquals(cl2, cruzada.clusterId());
    repo.fijarOrigenEnSuCluster(archivo, List.of(otra));
    assertTrue(repo.rutasCruzadas(archivo, List.of(cl1, cl2)).isEmpty());

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
    assertEquals(2, repo.aDataEnSuChute(archivo, List.of(b, c)));
  }

  @Test
  void enDataLaRutaQuedaEnElBloqueDondeSeSuelta() {
    long id = repo.insertar(archivo, "2002", chute1, dec("1"), dec("1"), dec("1"), 1);
    repo.asignarTipo(archivo, List.of(id), "B2C");
    repo.asignarFrecuencia(archivo, List.of(id), "LXV");
    repo.asignarGrupo(archivo, List.of(id), "1");

    // De un bloque a otro de DATA.
    assertEquals(1, repo.aData(archivo, List.of(id), chute2));
    RutaData movida = repo.rutas(archivo, List.of(id)).getFirst();
    assertTrue(movida.enData());
    assertEquals(chute2, movida.chuteId());

    // De un cluster a un bloque de DATA: conserva tipo, frecuencia y grupo; no cuenta como movida.
    repo.aCluster(archivo, List.of(id), cl1, chute2);
    assertEquals(1, repo.aData(archivo, List.of(id), chute1));
    var fila =
        jdbc.queryForMap(
            "SELECT cluster_id, cluster_original_id, chute_id, chute_original_id, movida, tipo, frecuencia, grupo"
                + " FROM simulacion.rutas_proyeccion WHERE id = ?",
            id);
    assertEquals(null, fila.get("cluster_id"));
    assertEquals(null, fila.get("cluster_original_id"));
    assertEquals(chute1, ((Number) fila.get("chute_id")).longValue());
    assertEquals(chute1, ((Number) fila.get("chute_original_id")).longValue());
    assertEquals(false, fila.get("movida"));
    assertEquals("B2C", fila.get("tipo"));
    assertEquals("LXV", fila.get("frecuencia"));
    assertEquals("1", fila.get("grupo"));
  }

  @Test
  void eliminarVariasBorraSoloLasDeDataConSusLocales() {
    long a = repo.insertar(archivo, "2002", chute1, dec("1"), dec("1"), dec("1"), 1);
    repo.reemplazarLocales(a, List.of(local("A1", 1, "1")));
    long b = repo.insertar(archivo, "2015", chute2, dec("1"), dec("1"), dec("1"), 1);
    long c = repo.insertar(archivo, "2040", chute1, dec("1"), dec("1"), dec("1"), 1);
    repo.asignarTipo(archivo, List.of(c), "B2C");
    repo.asignarFrecuencia(archivo, List.of(c), "LXV");
    repo.asignarGrupo(archivo, List.of(c), "1");
    repo.aCluster(archivo, List.of(c), cl1, chute1);

    assertEquals(2, repo.eliminarVarias(archivo, List.of(a, b, c)), "la del cluster no se borra");
    assertEquals(List.of(c), repo.rutas(archivo, List.of(a, b, c)).stream().map(RutaData::id).toList());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM simulacion.rutas_locales WHERE ruta_id = ?", Integer.class, a));
  }

  @Test
  void losLocalesPasanDeUnaRutaAOtraYLosTotalesSeRecalculan() {
    long a = repo.insertar(archivo, "2002", chute1, dec("17"), dec("3"), dec("3"), 3);
    repo.reemplazarLocales(a, List.of(local("A1", 1, "10"), local("B2", 1, "5"), local("C3", 2, "2")));
    long b = repo.insertar(archivo, "2015", chute2, dec("7"), dec("1"), dec("1"), 1);
    repo.reemplazarLocales(b, List.of(local("A1", 1, "7")));

    // A1 se suma a la A1 de 2015; C3 pasa al final de 2015 sin reescribirse.
    repo.fijarLocales(b, List.of(new LocalGuardado(1, "A1", 2, dec("17"), dec("2"), dec("2"))));
    repo.quitarLocales(a, List.of(1));
    repo.pasarLocales(a, b, List.<int[]>of(new int[] {3, 2}));
    repo.recalcularTotales(archivo, List.of(a, b));

    assertEquals(List.of("B2"), repo.locales(a).stream().map(LocalGuardado::cod).toList());
    List<LocalGuardado> enB = repo.locales(b);
    assertEquals(List.of("A1", "C3"), enB.stream().map(LocalGuardado::cod).toList());
    assertEquals(List.of(1, 2), enB.stream().map(LocalGuardado::orden).toList());
    assertEquals(2, enB.get(0).filas());
    assertEquals(2, enB.get(1).filas(), "C3 conserva sus filas");
    var ta = jdbc.queryForMap("SELECT total_bultos, total_unidades, conteo_cod FROM simulacion.rutas_proyeccion WHERE id = ?", a);
    assertEquals(0, dec("5").compareTo((BigDecimal) ta.get("total_bultos")));
    assertEquals(0, dec("1").compareTo((BigDecimal) ta.get("total_unidades")));
    assertEquals(1, ((Number) ta.get("conteo_cod")).intValue());
    var tb = jdbc.queryForMap("SELECT total_bultos, total_unidades, conteo_cod FROM simulacion.rutas_proyeccion WHERE id = ?", b);
    assertEquals(0, dec("19").compareTo((BigDecimal) tb.get("total_bultos")));
    assertEquals(0, dec("3").compareTo((BigDecimal) tb.get("total_unidades")));
    assertEquals(2, ((Number) tb.get("conteo_cod")).intValue());
  }

  private static BigDecimal dec(String valor) {
    return new BigDecimal(valor);
  }

  private static Local local(String cod, int filas, String bultos) {
    return new Local(cod, filas, dec(bultos), BigDecimal.ONE, BigDecimal.ONE);
  }
}
