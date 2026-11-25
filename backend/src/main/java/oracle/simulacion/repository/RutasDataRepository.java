package oracle.simulacion.repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Escrituras de DATA, el bloque de Configuración con las rutas que no se balancean: son rutas del
 * escenario sin cluster ({@code cluster_id} NULL). Todo va acotado al escenario ({@code
 * archivo_id}) y por clave primaria o por el índice único (archivo, código, tipo).
 */
@Repository
public class RutasDataRepository {

  /** Lo que hace falta de una ruta para decidir si se mueve, se edita o se le asigna algo. */
  public record RutaData(
      long id, String codigo, String tipo, Long clusterId, long chuteId, String frecuencia, String grupo) {

    public boolean enData() {
      return clusterId == null;
    }

    /** Tiene tipo, frecuencia y grupo: puede pasar a un cluster. */
    public boolean completa() {
      return tipo != null && frecuencia != null && grupo != null;
    }
  }

  /** Totales del escenario: solo las rutas de los clusters (las de DATA no se balancean). */
  public record Totales(
      int rutas, int chutes, BigDecimal bultos, BigDecimal unidades, BigDecimal lineas, int cod) {}

  /** Un local de una ruta, ya sumado. */
  public record Local(String cod, int filas, BigDecimal bultos, BigDecimal unidades, BigDecimal lineas) {}

  /** Días de cada frecuencia (los que muestran la ficha y el Dashboard). */
  private static final String DIAS =
      "CASE ? WHEN 'LXV' THEN 'L-X-V' WHEN 'MJS' THEN 'M-J-S' ELSE 'L-M-X-J-V-S' END";

  private final JdbcTemplate jdbc;

  public RutasDataRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** «{1,2,3}»: arreglo de PostgreSQL para {@code = ANY (?::bigint[])}. */
  static String arreglo(Collection<Long> ids) {
    return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
  }

  public List<RutaData> rutas(Long archivoId, Collection<Long> ids) {
    // Q: PK de rutas_proyeccion (id = ANY), filtrada por escenario.
    return jdbc.query(
        """
        SELECT id, codigo_ruta, tipo, cluster_id, chute_id, frecuencia, grupo
        FROM simulacion.rutas_proyeccion
        WHERE archivo_id = ? AND id = ANY (?::bigint[])
        """,
        (rs, i) ->
            new RutaData(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getObject(4, Long.class),
                rs.getLong(5),
                rs.getString(6),
                rs.getString(7)),
        archivoId,
        arreglo(ids));
  }

  /** Ya hay otra ruta con ese número y tipo (sin tipo = DATA) en el escenario. */
  public boolean existe(Long archivoId, String codigo, String tipo, Long salvoId) {
    // Q: uq_rutas_archivo_ruta_tipo (archivo_id, codigo_ruta, tipo).
    Boolean hay =
        jdbc.queryForObject(
            """
            SELECT EXISTS (SELECT 1 FROM simulacion.rutas_proyeccion
                           WHERE archivo_id = ? AND codigo_ruta = ? AND tipo IS NOT DISTINCT FROM CAST(? AS varchar)
                             AND id <> COALESCE(CAST(? AS bigint), 0))
            """,
            Boolean.class,
            archivoId,
            codigo,
            tipo,
            salvoId);
    return Boolean.TRUE.equals(hay);
  }

  /** Crea la ruta en DATA, en ese chute, con sus totales. */
  public long insertar(
      Long archivoId, String codigo, long chuteId, BigDecimal bultos, BigDecimal unidades, BigDecimal lineas, int cod) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO simulacion.rutas_proyeccion
                (archivo_id, chute_id, chute_original_id, codigo_ruta, total_bultos, total_unidades,
                 total_lineas, conteo_cod)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            archivoId,
            chuteId,
            chuteId,
            codigo,
            bultos,
            unidades,
            lineas,
            cod);
    return id == null ? 0 : id;
  }

  /** Cambia número y totales de una ruta de DATA. Devuelve si existía en DATA. */
  public boolean actualizar(
      Long archivoId, long rutaId, String codigo, BigDecimal bultos, BigDecimal unidades, BigDecimal lineas, int cod) {
    // Q: PK de rutas_proyeccion.
    return jdbc.update(
            """
            UPDATE simulacion.rutas_proyeccion
            SET codigo_ruta = ?, total_bultos = ?, total_unidades = ?, total_lineas = ?, conteo_cod = ?
            WHERE id = ? AND archivo_id = ? AND cluster_id IS NULL
            """,
            codigo,
            bultos,
            unidades,
            lineas,
            cod,
            rutaId,
            archivoId)
        == 1;
  }

  /** Los locales de la ruta pasan a ser estos (en su orden). */
  public void reemplazarLocales(long rutaId, List<Local> locales) {
    // Q: pk_rutas_locales (ruta_id, orden).
    jdbc.update("DELETE FROM simulacion.rutas_locales WHERE ruta_id = ?", rutaId);
    List<Object[]> filas = new ArrayList<>(locales.size());
    int orden = 0;
    for (Local l : locales) {
      filas.add(new Object[] {rutaId, ++orden, l.cod(), l.filas(), l.bultos(), l.unidades(), l.lineas()});
    }
    jdbc.batchUpdate(
        """
        INSERT INTO simulacion.rutas_locales (ruta_id, orden, cod, filas, bultos, unidades, lineas)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        filas);
  }

  /** Borra una ruta de DATA (sus locales se van con ella). Devuelve si existía en DATA. */
  public boolean eliminar(Long archivoId, long rutaId) {
    return jdbc.update(
            "DELETE FROM simulacion.rutas_proyeccion WHERE id = ? AND archivo_id = ? AND cluster_id IS NULL",
            rutaId,
            archivoId)
        == 1;
  }

  /**
   * Números que quedarían repetidos si esas rutas pasan a tener {@code tipo}: ya hay otra con ese
   * número y tipo en el escenario, o dos de ellas tienen el mismo número.
   */
  public List<String> repetidasConTipo(Long archivoId, Collection<Long> ids, String tipo) {
    // Q: uq_rutas_archivo_ruta_tipo para el EXISTS; PK para las elegidas.
    return jdbc.queryForList(
        """
        SELECT DISTINCT e.codigo_ruta
        FROM simulacion.rutas_proyeccion e
        WHERE e.archivo_id = ? AND e.id = ANY (?::bigint[]) AND e.tipo IS DISTINCT FROM CAST(? AS varchar)
          AND EXISTS (SELECT 1 FROM simulacion.rutas_proyeccion o
                      WHERE o.archivo_id = e.archivo_id AND o.codigo_ruta = e.codigo_ruta
                        AND o.tipo IS NOT DISTINCT FROM CAST(? AS varchar) AND NOT (o.id = ANY (?::bigint[])))
        UNION
        SELECT codigo_ruta FROM simulacion.rutas_proyeccion
        WHERE archivo_id = ? AND id = ANY (?::bigint[]) GROUP BY codigo_ruta HAVING count(*) > 1
        ORDER BY 1
        """,
        String.class,
        archivoId,
        arreglo(ids),
        tipo,
        tipo,
        arreglo(ids),
        archivoId,
        arreglo(ids));
  }

  /** Tipo para las rutas de DATA de esa lista. Devuelve cuántas lo recibieron. */
  public int asignarTipo(Long archivoId, Collection<Long> ids, String tipo) {
    return jdbc.update(
        "UPDATE simulacion.rutas_proyeccion SET tipo = ?"
            + " WHERE archivo_id = ? AND id = ANY (?::bigint[]) AND cluster_id IS NULL",
        tipo,
        archivoId,
        arreglo(ids));
  }

  /** Frecuencia (y sus días) para las rutas de DATA de esa lista. Devuelve cuántas la recibieron. */
  public int asignarFrecuencia(Long archivoId, Collection<Long> ids, String frecuencia) {
    return jdbc.update(
        "UPDATE simulacion.rutas_proyeccion SET frecuencia = ?, dias = "
            + DIAS
            + " WHERE archivo_id = ? AND id = ANY (?::bigint[]) AND cluster_id IS NULL",
        frecuencia,
        frecuencia,
        archivoId,
        arreglo(ids));
  }

  /** Grupo para las rutas de DATA de esa lista. Devuelve cuántas lo recibieron. */
  public int asignarGrupo(Long archivoId, Collection<Long> ids, String grupo) {
    return jdbc.update(
        "UPDATE simulacion.rutas_proyeccion SET grupo = ?"
            + " WHERE archivo_id = ? AND id = ANY (?::bigint[]) AND cluster_id IS NULL",
        grupo,
        archivoId,
        arreglo(ids));
  }

  /** Las rutas van a DATA en ese chute: sin cluster; ese chute pasa a ser su original. */
  public int aData(Long archivoId, Collection<Long> ids, long chuteId) {
    return jdbc.update(
        """
        UPDATE simulacion.rutas_proyeccion
        SET cluster_id = NULL, cluster_original_id = NULL, chute_id = ?, chute_original_id = ?
        WHERE archivo_id = ? AND id = ANY (?::bigint[])
        """,
        chuteId,
        chuteId,
        archivoId,
        arreglo(ids));
  }

  /**
   * Las rutas de DATA entran a un cluster en ese chute: es su posición original (el «antes» del
   * Dashboard). Devuelve cuántas estaban en DATA.
   */
  public int aCluster(Long archivoId, Collection<Long> ids, long clusterId, long chuteId) {
    return jdbc.update(
        """
        UPDATE simulacion.rutas_proyeccion
        SET cluster_id = ?, cluster_original_id = ?, chute_id = ?, chute_original_id = ?
        WHERE archivo_id = ? AND id = ANY (?::bigint[]) AND cluster_id IS NULL
        """,
        clusterId,
        clusterId,
        chuteId,
        chuteId,
        archivoId,
        arreglo(ids));
  }

  /** Las rutas de esos clusters vuelven a DATA en el chute donde están. Devuelve sus ids. */
  public List<Long> devolverADataEnSuChute(Long archivoId, Collection<Long> clusterIds) {
    // Q: idx_rutas_archivo_cluster (archivo_id, cluster_id = ANY).
    return jdbc.queryForList(
        """
        UPDATE simulacion.rutas_proyeccion
        SET cluster_id = NULL, cluster_original_id = NULL, chute_original_id = chute_id
        WHERE archivo_id = ? AND cluster_id = ANY (?::bigint[])
        RETURNING id
        """,
        Long.class,
        archivoId,
        arreglo(clusterIds));
  }

  /** Lo que se hizo a mano con esas rutas en Configuración: al volver a DATA ya no aplica. */
  public void borrarHistorialDeRutas(Long archivoId, Collection<Long> ids) {
    if (ids.isEmpty()) {
      return;
    }
    // Q: idx_historial_ruta (ruta_id).
    jdbc.update(
        "DELETE FROM simulacion.historial_balanceo WHERE ruta_id = ANY (?::bigint[]) AND archivo_id = ?",
        arreglo(ids),
        archivoId);
  }

  /** Quita del escenario esos clusters (sin rutas): sus parámetros, grupos, operarios y lanzamientos. */
  public void quitarClusters(Long archivoId, Collection<Long> clusterIds) {
    String ids = arreglo(clusterIds);
    jdbc.update(
        "DELETE FROM simulacion.operarios_ola WHERE archivo_id = ? AND cluster_id = ANY (?::bigint[])",
        archivoId,
        ids);
    jdbc.update(
        "DELETE FROM simulacion.parametros_grupos WHERE archivo_id = ? AND cluster_id = ANY (?::bigint[])",
        archivoId,
        ids);
    // Q: idx_lanzamientos_archivo_balanceo; su historial se va en cascada.
    jdbc.update(
        "DELETE FROM simulacion.lanzamientos_balanceo WHERE archivo_id = ? AND cluster_id = ANY (?::bigint[])",
        archivoId,
        ids);
    jdbc.update(
        "DELETE FROM simulacion.parametros_clusters WHERE archivo_id = ? AND cluster_id = ANY (?::bigint[])",
        archivoId,
        ids);
  }

  /** Totales del escenario: solo las rutas de los clusters. */
  public Totales totales(Long archivoId) {
    // Q: idx_rutas_archivo_cluster (archivo_id, cluster_id IS NOT NULL).
    return jdbc.queryForObject(
        """
        SELECT count(*)::int, count(DISTINCT chute_id)::int, COALESCE(sum(total_bultos), 0),
               COALESCE(sum(total_unidades), 0), COALESCE(sum(total_lineas), 0),
               COALESCE(sum(conteo_cod), 0)::int
        FROM simulacion.rutas_proyeccion
        WHERE archivo_id = ? AND cluster_id IS NOT NULL
        """,
        (rs, i) ->
            new Totales(
                rs.getInt(1), rs.getInt(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getInt(6)),
        archivoId);
  }
}
