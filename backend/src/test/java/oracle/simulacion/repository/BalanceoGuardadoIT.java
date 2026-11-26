package oracle.simulacion.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * «Guardar balanceo de rutas» y «Cargar balanceo guardado» contra un PostgreSQL de verdad: se guarda
 * la posición de cada ruta (DATA o cluster y chute), esté o no balanceada, y al cargar vuelve ahí
 * con cada cluster como ya balanceado (solo pide operarios).
 * Solo corre con una base de prueba (su URL debe contener «prueba»): nunca toca la del proyecto.
 *
 * <pre>
 * ./mvnw -o test -Dtest=BalanceoGuardadoIT -Dsurefire.failIfNoSpecifiedTests=false \
 *   "-Descenario.pg.url=jdbc:postgresql://127.0.0.1:54329/data_prueba?currentSchema=seguridad,bitacora,simulacion,malla,simulacion_santa,public" \
 *   -Descenario.pg.usuario=prueba
 * </pre>
 */
@EnabledIfSystemProperty(named = "escenario.pg.url", matches = ".*prueba.*")
class BalanceoGuardadoIT {

  private JdbcTemplate jdbc;
  private RutasDataRepository data;
  private SimulacionLotesRepository lotes;
  private long archivo;
  private long cl1;
  private long cl2;
  private long chute1;
  private long chute2;

  @BeforeEach
  void preparar() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            System.getProperty("escenario.pg.url"), System.getProperty("escenario.pg.usuario", "prueba"), "");
    jdbc = new JdbcTemplate(ds);
    data = new RutasDataRepository(jdbc);
    lotes = new SimulacionLotesRepository(jdbc);
    jdbc.execute("DELETE FROM simulacion.archivos_excel");
    jdbc.execute(
        "INSERT INTO seguridad.usuarios (usuario, contrasena, nombre, apellido, email, actualizado_por)"
            + " VALUES ('prueba', 'x', 'P', 'P', 'p@x', 0) ON CONFLICT DO NOTHING");
    long usuario = jdbc.queryForObject("SELECT id FROM seguridad.usuarios WHERE usuario = 'prueba'", Long.class);
    jdbc.execute("INSERT INTO simulacion.clusters (nombre) VALUES ('CL1'), ('CL2') ON CONFLICT DO NOTHING");
    jdbc.execute("INSERT INTO simulacion.chutes (nombre) VALUES ('1'), ('2') ON CONFLICT DO NOTHING");
    cl1 = jdbc.queryForObject("SELECT id FROM simulacion.clusters WHERE nombre = 'CL1'", Long.class);
    cl2 = jdbc.queryForObject("SELECT id FROM simulacion.clusters WHERE nombre = 'CL2'", Long.class);
    chute1 = jdbc.queryForObject("SELECT id FROM simulacion.chutes WHERE nombre = '1'", Long.class);
    chute2 = jdbc.queryForObject("SELECT id FROM simulacion.chutes WHERE nombre = '2'", Long.class);
    archivo =
        jdbc.queryForObject(
            "INSERT INTO simulacion.archivos_excel (nombre_registro, nombre_archivo, es_unico, creado_por, actualizado_por)"
                + " VALUES ('Simulación Titan', 'Escenario', TRUE, ?, ?) RETURNING id",
            Long.class,
            usuario,
            usuario);
    lotes.asegurarParametrosCluster(archivo, List.of(cl1, cl2));
  }

  @Test
  void lasRutasVuelvenAdondeEstabanAlGuardarAunqueNoEstuvieranBalanceadas() {
    long a = ruta("2002");
    long b = ruta("2015");
    long c = ruta("2040");
    // Colocadas desde DATA, sin balancear: esa celda es su posición original.
    data.aCluster(archivo, List.of(a), cl1, chute1);
    data.aCluster(archivo, List.of(b), cl2, chute2);
    data.aData(archivo, List.of(c), chute2);
    lotes.guardarBalanceoRutas(archivo);

    // Después se reacomodan: A vuelve a DATA, B pasa a CL1 y C entra a CL1.
    data.aData(archivo, List.of(a), chute2);
    data.aData(archivo, List.of(b), chute1);
    data.aCluster(archivo, List.of(b), cl1, chute2);
    data.aCluster(archivo, List.of(c), cl1, chute1);

    lotes.cargarBalanceoRutas(archivo);

    assertPosicion(a, cl1, chute1, cl1, chute1);
    assertPosicion(b, cl2, chute2, cl2, chute2);
    assertPosicion(c, null, chute2, null, chute2);
    assertEquals(List.of("RUTAS"), estados(cl1));
    assertEquals(List.of("RUTAS"), estados(cl2));
  }

  @Test
  void unaRutaBalanceadaVuelveASuChuteYConservaSuOriginal() {
    long a = ruta("2002");
    data.aCluster(archivo, List.of(a), cl1, chute1);
    // El balanceo la pasó al chute 2 (su original sigue siendo el 1).
    jdbc.update("UPDATE simulacion.rutas_proyeccion SET chute_id = ? WHERE id = ?", chute2, a);
    lotes.guardarBalanceoRutas(archivo);
    jdbc.update("UPDATE simulacion.rutas_proyeccion SET chute_id = chute_original_id WHERE id = ?", a);

    lotes.cargarBalanceoRutas(archivo);

    assertPosicion(a, cl1, chute2, cl1, chute1);
  }

  @Test
  void aMedioBalancearYConUnaRutaMovidaAManoTambienSeGuardaYAlCargarSoloPideOperarios() {
    long a = ruta("2002");
    long b = ruta("2015");
    data.aCluster(archivo, List.of(a, b), cl1, chute1);
    // CL1: Diarias balanceadas, L-X-V y M-J-S no; B se movió a mano al chute 2 sin balancear.
    jdbc.update("UPDATE simulacion.parametros_clusters SET estado = 'RUTAS' WHERE archivo_id = ? AND cluster_id = ? AND balanceo = 'DIA'", archivo, cl1);
    jdbc.update("UPDATE simulacion.rutas_proyeccion SET chute_id = ? WHERE id = ?", chute2, b);
    lotes.guardarBalanceoRutas(archivo);
    // El trabajo actual no cambia al guardar.
    assertEquals(List.of("PENDIENTE", "RUTAS"), estadosOrdenados(cl1));

    // Se restablece todo (como exige cargar) y se carga.
    jdbc.update("UPDATE simulacion.parametros_clusters SET estado = 'PENDIENTE' WHERE archivo_id = ?", archivo);
    jdbc.update("UPDATE simulacion.rutas_proyeccion SET chute_id = chute_original_id WHERE archivo_id = ?", archivo);
    lotes.cargarBalanceoRutas(archivo);

    assertPosicion(a, cl1, chute1, cl1, chute1);
    assertPosicion(b, cl1, chute2, cl1, chute1);
    assertEquals(List.of("RUTAS"), estados(cl1), "CL1 queda como balanceado en los tres: solo pide operarios");
  }

  @Test
  void unClusterConRutasQueNoEstabaEnLaFotoTambienQuedaListoParaOperarios() {
    long a = ruta("2002");
    data.aCluster(archivo, List.of(a), cl1, chute1);
    jdbc.update("DELETE FROM simulacion.parametros_clusters WHERE archivo_id = ? AND cluster_id = ?", archivo, cl2);
    lotes.guardarBalanceoRutas(archivo);
    // Después de guardar se agrega CL2 y recibe una ruta.
    lotes.asegurarParametrosCluster(archivo, List.of(cl2));
    long b = ruta("2015");
    data.aCluster(archivo, List.of(b), cl2, chute2);

    lotes.cargarBalanceoRutas(archivo);

    assertEquals(List.of("RUTAS"), estados(cl1));
    assertEquals(List.of("RUTAS"), estados(cl2));
    assertPosicion(b, cl2, chute2, cl2, chute2);
  }

  @Test
  void unaRutaDeUnClusterQuitadoDespuesDeGuardarSeQuedaDondeEsta() {
    long a = ruta("2002");
    data.aCluster(archivo, List.of(a), cl2, chute1);
    lotes.guardarBalanceoRutas(archivo);
    data.devolverADataEnSuChute(archivo, List.of(cl2));
    data.quitarClusters(archivo, List.of(cl2));

    lotes.cargarBalanceoRutas(archivo);

    assertPosicion(a, null, chute1, null, chute1);
  }

  @Test
  void unGuardadoAnteriorSoloConLasRutasMovidasSigueCargando() {
    long a = ruta("2002");
    data.aCluster(archivo, List.of(a), cl1, chute1);
    jdbc.update(
        "UPDATE simulacion.archivos_excel SET balanceo_guardado = jsonb_build_object("
            + "'rutas', jsonb_build_array(jsonb_build_object('id', ?::bigint, 'cluster_id', ?::bigint, 'chute_id', ?::bigint)),"
            + " 'clusters', '[]'::jsonb, 'grupos', '[]'::jsonb, 'lanzamientos', '[]'::jsonb, 'historial', '[]'::jsonb)"
            + " WHERE id = ?",
        a,
        cl1,
        chute2,
        archivo);

    lotes.cargarBalanceoRutas(archivo);

    assertPosicion(a, cl1, chute2, cl1, chute1);
  }

  private long ruta(String codigo) {
    long id = data.insertar(archivo, codigo, chute1, dec("10"), dec("1"), dec("1"), 1);
    data.asignarTipo(archivo, List.of(id), "B2C");
    data.asignarFrecuencia(archivo, List.of(id), "LXV");
    data.asignarGrupo(archivo, List.of(id), "1");
    return id;
  }

  private void assertPosicion(long id, Long cluster, long chute, Long clusterOriginal, long chuteOriginal) {
    Map<String, Object> fila =
        jdbc.queryForMap(
            "SELECT cluster_id, chute_id, cluster_original_id, chute_original_id FROM simulacion.rutas_proyeccion WHERE id = ?",
            id);
    if (cluster == null) {
      assertNull(fila.get("cluster_id"), "ruta " + id + " en DATA");
      assertNull(fila.get("cluster_original_id"));
    } else {
      assertEquals(cluster, ((Number) fila.get("cluster_id")).longValue(), "cluster de la ruta " + id);
      assertEquals(clusterOriginal, ((Number) fila.get("cluster_original_id")).longValue(), "cluster original de " + id);
    }
    assertEquals(chute, ((Number) fila.get("chute_id")).longValue(), "chute de la ruta " + id);
    assertEquals(chuteOriginal, ((Number) fila.get("chute_original_id")).longValue(), "chute original de " + id);
  }

  private List<String> estados(long cluster) {
    return jdbc.queryForList(
        "SELECT DISTINCT estado FROM simulacion.parametros_clusters WHERE archivo_id = ? AND cluster_id = ?",
        String.class,
        archivo,
        cluster);
  }

  private List<String> estadosOrdenados(long cluster) {
    return jdbc.queryForList(
        "SELECT DISTINCT estado FROM simulacion.parametros_clusters WHERE archivo_id = ? AND cluster_id = ? ORDER BY estado",
        String.class,
        archivo,
        cluster);
  }

  private static BigDecimal dec(String valor) {
    return new BigDecimal(valor);
  }
}
