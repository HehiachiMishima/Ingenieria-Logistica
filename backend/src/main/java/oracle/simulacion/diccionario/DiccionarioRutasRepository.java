package oracle.simulacion.diccionario;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Cluster;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Local;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Ruta;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tablas del Registro v2 ({@code simulacion.diccionario_*}). Independientes del registro por
 * Excel: nada de aquí lee ni escribe {@code rutas_proyeccion}.
 */
@Repository
public class DiccionarioRutasRepository {

  /** Ruta con los totales y los códigos de sus locales (el LEFT JOIN deja las que no tienen). */
  private static final String RUTAS =
      """
      SELECT r.id, r.codigo_ruta, r.tipo, r.cluster_id, r.frecuencia, r.grupo,
             COALESCE(sum(l.bultos), 0), COALESCE(sum(l.unidades), 0), COALESCE(sum(l.lineas), 0),
             count(l.ruta_id), array_agg(l.cod ORDER BY l.orden) FILTER (WHERE l.cod IS NOT NULL)
      FROM simulacion.diccionario_rutas r
      LEFT JOIN simulacion.diccionario_locales l ON l.ruta_id = r.id
      """;

  private final JdbcTemplate jdbc;

  public DiccionarioRutasRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------------- Lectura ----------------

  public List<Cluster> clusters() {
    // Q: uq_dicc_clusters_orden.
    return jdbc.query(
        "SELECT id, orden, nombre FROM simulacion.diccionario_clusters ORDER BY orden",
        (rs, i) -> new Cluster(rs.getLong(1), rs.getInt(2), rs.getString(3)));
  }

  /** Todas las rutas por número y tipo, hasta {@code tope} (una de más indica que hay más). */
  public List<Ruta> rutas(int tope) {
    // Q: uq_dicc_rutas_codigo_tipo da el orden; pk_dicc_locales, la suma de cada ruta.
    return jdbc.query(
        RUTAS + " GROUP BY r.id ORDER BY r.codigo_ruta, r.tipo LIMIT ?", DiccionarioRutasRepository::ruta, tope);
  }

  /** Esas rutas (las que existan), por número y tipo. */
  public List<Ruta> rutasPorIds(Collection<Long> ids) {
    // Q: PK de diccionario_rutas (id = ANY) y pk_dicc_locales.
    return jdbc.query(
        RUTAS + " WHERE r.id = ANY (?::bigint[]) GROUP BY r.id ORDER BY r.codigo_ruta, r.tipo",
        DiccionarioRutasRepository::ruta,
        arreglo(ids));
  }

  /** Locales de una ruta en el orden en que se ingresaron. */
  public List<Local> locales(Long rutaId) {
    // Q: pk_dicc_locales (ruta_id, orden).
    return jdbc.query(
        """
        SELECT cod, filas, bultos, unidades, lineas FROM simulacion.diccionario_locales
        WHERE ruta_id = ? ORDER BY orden
        """,
        (rs, i) ->
            new Local(rs.getString(1), rs.getInt(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5)),
        rutaId);
  }

  /** Otra ruta (distinta de {@code salvoId}) ya tiene ese número y tipo (también «sin tipo»). */
  public boolean existe(String codigo, String tipo, Long salvoId) {
    // Q: uq_dicc_rutas_codigo_tipo.
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            SELECT EXISTS (SELECT 1 FROM simulacion.diccionario_rutas
                           WHERE codigo_ruta = ? AND tipo IS NOT DISTINCT FROM ? AND id IS DISTINCT FROM ?)
            """,
            Boolean.class,
            codigo,
            tipo,
            salvoId));
  }

  public boolean clusterExiste(Long clusterId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM simulacion.diccionario_clusters WHERE id = ?)", Boolean.class, clusterId));
  }

  /** Cuántas de esas rutas existen. */
  public int contar(Collection<Long> ids) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM simulacion.diccionario_rutas WHERE id = ANY (?::bigint[])",
            Integer.class,
            arreglo(ids));
    return n == null ? 0 : n;
  }

  // ---------------- Rutas ----------------

  /** Crea la ruta en Datos (sin cluster ni frecuencia) y devuelve su id. */
  public long insertarRuta(String codigo, String tipo, Long actor) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO simulacion.diccionario_rutas (codigo_ruta, tipo, creado_por, actualizado_por)
            VALUES (?, ?, ?, ?) RETURNING id
            """,
            Long.class,
            codigo,
            tipo,
            actor,
            actor);
    return id == null ? 0 : id;
  }

  /** Cambia número y tipo; cluster y frecuencia se conservan. False si la ruta no existe. */
  public boolean actualizarRuta(Long id, String codigo, String tipo, Long actor) {
    return jdbc.update(
            """
            UPDATE simulacion.diccionario_rutas
            SET codigo_ruta = ?, tipo = ?, actualizado_por = ?, actualizado_en = now()
            WHERE id = ?
            """,
            codigo,
            tipo,
            actor,
            id)
        > 0;
  }

  /** Reemplaza todos los locales de la ruta por estos (ya sumados por código). */
  public void reemplazarLocales(Long rutaId, List<Local> locales) {
    jdbc.update("DELETE FROM simulacion.diccionario_locales WHERE ruta_id = ?", rutaId);
    List<Object[]> filas = new ArrayList<>(locales.size());
    for (int i = 0; i < locales.size(); i++) {
      Local local = locales.get(i);
      filas.add(
          new Object[] {rutaId, i + 1, local.cod(), local.filas(), local.bultos(), local.unidades(), local.lineas()});
    }
    jdbc.batchUpdate(
        """
        INSERT INTO simulacion.diccionario_locales (ruta_id, orden, cod, filas, bultos, unidades, lineas)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        filas);
  }

  /** Borra la ruta y sus locales. False si no existía. */
  public boolean eliminar(Long id) {
    return jdbc.update("DELETE FROM simulacion.diccionario_rutas WHERE id = ?", id) > 0;
  }

  /**
   * Lleva las rutas a un cluster (conservan frecuencia y grupo) o a Datos ({@code clusterId} null:
   * pierden cluster, frecuencia y grupo). Devuelve cuántas existían.
   */
  public int mover(Collection<Long> ids, Long clusterId, Long actor) {
    // Q: PK de diccionario_rutas (id = ANY).
    if (clusterId == null) {
      return jdbc.update(
          """
          UPDATE simulacion.diccionario_rutas
          SET cluster_id = NULL, frecuencia = NULL, grupo = NULL, actualizado_por = ?, actualizado_en = now()
          WHERE id = ANY (?::bigint[])
          """,
          actor,
          arreglo(ids));
    }
    return jdbc.update(
        """
        UPDATE simulacion.diccionario_rutas
        SET cluster_id = ?, actualizado_por = ?, actualizado_en = now()
        WHERE id = ANY (?::bigint[])
        """,
        clusterId,
        actor,
        arreglo(ids));
  }

  /** Frecuencia solo para las que están en un cluster; devuelve los ids que la recibieron. */
  public List<Long> asignarFrecuencia(Collection<Long> ids, String frecuencia, Long actor) {
    return jdbc.queryForList(
        """
        UPDATE simulacion.diccionario_rutas
        SET frecuencia = ?, actualizado_por = ?, actualizado_en = now()
        WHERE id = ANY (?::bigint[]) AND cluster_id IS NOT NULL
        RETURNING id
        """,
        Long.class,
        frecuencia,
        actor,
        arreglo(ids));
  }

  /** Grupo solo para las que están en un cluster; devuelve los ids que lo recibieron. */
  public List<Long> asignarGrupo(Collection<Long> ids, String grupo, Long actor) {
    return jdbc.queryForList(
        """
        UPDATE simulacion.diccionario_rutas
        SET grupo = ?, actualizado_por = ?, actualizado_en = now()
        WHERE id = ANY (?::bigint[]) AND cluster_id IS NOT NULL
        RETURNING id
        """,
        Long.class,
        grupo,
        actor,
        arreglo(ids));
  }

  /**
   * Números de ruta que quedarían repetidos al pasar esas rutas a {@code tipo}: ya existe otra
   * con ese número y tipo, o hay dos elegidas con el mismo número.
   */
  public List<String> repetidasConTipo(Collection<Long> ids, String tipo) {
    // Q: uq_dicc_rutas_codigo_tipo para el EXISTS.
    return jdbc.queryForList(
        """
        SELECT DISTINCT e.codigo_ruta
        FROM simulacion.diccionario_rutas e
        WHERE e.id = ANY (?::bigint[]) AND e.tipo IS DISTINCT FROM ?
          AND EXISTS (SELECT 1 FROM simulacion.diccionario_rutas o
                      WHERE o.codigo_ruta = e.codigo_ruta AND o.tipo = ? AND NOT (o.id = ANY (?::bigint[])))
        UNION
        SELECT codigo_ruta FROM simulacion.diccionario_rutas
        WHERE id = ANY (?::bigint[]) GROUP BY codigo_ruta HAVING count(*) > 1
        ORDER BY 1
        """,
        String.class,
        arreglo(ids),
        tipo,
        tipo,
        arreglo(ids),
        arreglo(ids));
  }

  public int cambiarTipo(Collection<Long> ids, String tipo, Long actor) {
    return jdbc.update(
        """
        UPDATE simulacion.diccionario_rutas
        SET tipo = ?, actualizado_por = ?, actualizado_en = now()
        WHERE id = ANY (?::bigint[])
        """,
        tipo,
        actor,
        arreglo(ids));
  }

  /** Deja cada ruta con el cluster, la frecuencia y el grupo indicados. Devuelve cuántas existían. */
  public int restaurar(List<DiccionarioPeticiones.Ubicacion> rutas, Long actor) {
    int[][] filas =
        jdbc.batchUpdate(
            """
            UPDATE simulacion.diccionario_rutas
            SET cluster_id = ?, frecuencia = ?, grupo = ?, actualizado_por = ?, actualizado_en = now()
            WHERE id = ?
            """,
            rutas,
            500,
            (ps, u) -> {
              ps.setObject(1, u.clusterId(), java.sql.Types.BIGINT);
              ps.setString(2, u.frecuencia());
              ps.setString(3, u.grupo());
              ps.setLong(4, actor);
              ps.setLong(5, u.id());
            });
    int total = 0;
    for (int[] lote : filas) {
      for (int n : lote) {
        total += Math.max(n, 0);
      }
    }
    return total;
  }

  // ---------------- Clusters ----------------

  /** Serializa los cambios de la cantidad de clusters (dos «+» a la vez no repiten un orden). */
  public void bloquearClusters() {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtext('simulacion.diccionario_clusters'))::text", String.class);
  }

  /** Orden del último cluster (0 sin clusters): es la cantidad, porque se agregan y quitan al final. */
  public int ultimoOrden() {
    // Q: uq_dicc_clusters_orden (max).
    Integer n =
        jdbc.queryForObject("SELECT COALESCE(max(orden), 0) FROM simulacion.diccionario_clusters", Integer.class);
    return n == null ? 0 : n;
  }

  /** Agrega los clusters {@code desde}…{@code hasta} (CL4, CL5…). */
  public void agregarClusters(int desde, int hasta) {
    jdbc.update(
        """
        INSERT INTO simulacion.diccionario_clusters (orden, nombre)
        SELECT n, 'CL' || n FROM generate_series(?, ?) AS n
        """,
        desde,
        hasta);
  }

  /**
   * Quita los clusters después de {@code cantidad}: sus rutas vuelven a Datos sin frecuencia.
   * Devuelve cuántas rutas volvieron.
   */
  public int quitarClustersDespuesDe(int cantidad, Long actor) {
    // Q: ix_dicc_rutas_cluster (cluster_id IN …) y uq_dicc_clusters_orden (orden > ?).
    int rutas =
        jdbc.update(
            """
            UPDATE simulacion.diccionario_rutas
            SET cluster_id = NULL, frecuencia = NULL, grupo = NULL, actualizado_por = ?, actualizado_en = now()
            WHERE cluster_id IN (SELECT id FROM simulacion.diccionario_clusters WHERE orden > ?)
            """,
            actor,
            cantidad);
    jdbc.update("DELETE FROM simulacion.diccionario_clusters WHERE orden > ?", cantidad);
    return rutas;
  }

  // ---------------- Apoyo ----------------

  private static Ruta ruta(ResultSet rs, int fila) throws SQLException {
    long cluster = rs.getLong(4);
    Long clusterId = rs.wasNull() ? null : cluster;
    return new Ruta(
        rs.getLong(1),
        rs.getString(2),
        rs.getString(3),
        clusterId,
        rs.getString(5),
        rs.getString(6),
        escala(rs.getBigDecimal(7)),
        escala(rs.getBigDecimal(8)),
        escala(rs.getBigDecimal(9)),
        rs.getInt(10),
        codigos(rs.getArray(11)));
  }

  private static List<String> codigos(Array arreglo) throws SQLException {
    if (arreglo == null) {
      return List.of();
    }
    try {
      return List.of((String[]) arreglo.getArray());
    } finally {
      arreglo.free();
    }
  }

  private static BigDecimal escala(BigDecimal valor) {
    return valor == null ? BigDecimal.ZERO.setScale(2) : valor.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  /** Ids como literal de arreglo de PostgreSQL («{1,2,3}»): solo números, sin riesgo de inyección. */
  static String arreglo(Collection<Long> ids) {
    return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
  }
}
