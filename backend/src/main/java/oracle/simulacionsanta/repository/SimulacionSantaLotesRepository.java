package oracle.simulacionsanta.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import oracle.simulacionsanta.service.ExcelSantaAnita;
import oracle.simulacionsanta.service.PaletaSanta;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Filas, ubicaciones y predeterminados de Santa Anita con JdbcTemplate: las entidades con id
 * IDENTITY desactivan el batching de Hibernate (5 mil filas serían 5 mil INSERT); con
 * reWriteBatchedInserts cada lote viaja como un INSERT multi-fila. Toda sentencia escribe el
 * esquema: simulacion.predeterminados está antes en el search_path.
 */
@Repository
public class SimulacionSantaLotesRepository {

  private static final int LOTE = 1_000;

  /**
   * Nombre visible de quien bloqueó la configuración (nombre y apellido, como lo muestra la app;
   * el login si ambos están vacíos), por la PK de usuarios. Null si está abierta o el usuario no
   * existe. Va como subconsulta de la misma lectura de los predeterminados (alias p).
   */
  private static final String NOMBRE_ACTOR =
      "(SELECT COALESCE(NULLIF(btrim(concat_ws(' ', u.nombre, u.apellido)), ''), u.usuario)"
          + " FROM seguridad.usuarios u WHERE u.id = p.actualizado_por AND p.bloqueado)";

  private final JdbcTemplate jdbc;

  public SimulacionSantaLotesRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------------- Filas (paletas) ----------------

  /** Las filas válidas de un registro nuevo, en lotes. */
  public void insertarFilas(Long registroId, List<ExcelSantaAnita.Fila> filas) {
    jdbc.batchUpdate(
        """
        INSERT INTO simulacion_santa.filas
            (registro_id, orden, ruta, local, cluster, envio1, envio2, envio3)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        filas,
        LOTE,
        (ps, fila) -> {
          ps.setLong(1, registroId);
          ps.setInt(2, fila.orden());
          ps.setString(3, fila.ruta());
          ps.setString(4, fila.local());
          ps.setShort(5, (short) fila.cluster());
          ps.setInt(6, fila.envio1());
          ps.setInt(7, fila.envio2());
          ps.setInt(8, fila.envio3());
        });
  }

  /** Paletas de un registro en el orden del Excel (índice uq_filas_registro_orden). */
  public List<PaletaSanta> paletas(Long registroId) {
    return jdbc.query(
        """
        SELECT id, orden, ruta, local, cluster, envio1, envio2, envio3
        FROM simulacion_santa.filas
        WHERE registro_id = ?
        ORDER BY orden, id
        """,
        (rs, i) ->
            new PaletaSanta(
                rs.getLong("id"),
                rs.getInt("orden"),
                rs.getString("ruta"),
                rs.getString("local"),
                rs.getInt("cluster"),
                rs.getInt("envio1"),
                rs.getInt("envio2"),
                rs.getInt("envio3")),
        registroId);
  }

  /**
   * Paletas, bultos por envío y paletas completas por envío de cada cluster del registro, en una
   * sola consulta (GROUP BY cluster; los acumulados salen de envio1..3). Solo los clusters con
   * filas, en orden.
   */
  public List<AgregadoCluster> agregadosPorCluster(Long registroId) {
    // Q: filas de un registro (uq_filas_registro_orden: WHERE registro_id = ?).
    return jdbc.query(
        """
        SELECT cluster,
               count(*) AS paletas,
               coalesce(sum(envio1), 0) AS bultos1,
               coalesce(sum(envio2), 0) AS bultos2,
               coalesce(sum(envio3), 0) AS bultos3,
               count(*) FILTER (WHERE envio1 >= envio1 + envio2 + envio3) AS completas1,
               count(*) FILTER (WHERE envio1 + envio2 >= envio1 + envio2 + envio3) AS completas2,
               count(*) AS completas3 -- hasta el envío 3, el acumulado es el total
        FROM simulacion_santa.filas
        WHERE registro_id = ?
        GROUP BY cluster
        ORDER BY cluster
        """,
        (rs, i) ->
            new AgregadoCluster(
                rs.getInt("cluster"),
                rs.getInt("paletas"),
                List.of(rs.getInt("bultos1"), rs.getInt("bultos2"), rs.getInt("bultos3")),
                List.of(rs.getInt("completas1"), rs.getInt("completas2"), rs.getInt("completas3"))),
        registroId);
  }

  // ---------------- Ubicaciones de un registro ----------------

  /** Rampas y pasillos de un registro (por su PK). */
  public List<UbicacionGuardada> ubicaciones(Long registroId) {
    return jdbc.query(
        """
        SELECT tipo, numero, cluster, posiciones
        FROM simulacion_santa.ubicaciones
        WHERE registro_id = ?
        ORDER BY tipo, numero
        """,
        (rs, i) -> ubicacion(rs),
        registroId);
  }

  /** Crea o reemplaza las 43 ubicaciones de un registro (un lote). */
  public void guardarUbicaciones(Long registroId, List<UbicacionGuardada> ubicaciones) {
    jdbc.batchUpdate(
        """
        INSERT INTO simulacion_santa.ubicaciones (registro_id, tipo, numero, cluster, posiciones)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (registro_id, tipo, numero)
        DO UPDATE SET cluster = EXCLUDED.cluster, posiciones = EXCLUDED.posiciones
        """,
        ubicaciones,
        LOTE,
        (ps, u) -> {
          ps.setLong(1, registroId);
          ps.setString(2, u.tipo());
          ps.setShort(3, (short) u.numero());
          ps.setObject(4, u.cluster() == null ? null : u.cluster().shortValue(), Types.SMALLINT);
          ps.setObject(5, u.posiciones() == null ? null : u.posiciones().shortValue(), Types.SMALLINT);
        });
  }

  // ---------------- Predeterminados ----------------

  /**
   * Los predeterminados; null si falta la fila. Con {@code compartido} la fila se lee FOR SHARE:
   * un registro que se está creando copia valores coherentes aunque alguien los cambie a la vez
   * (el cambio espera a que la carga confirme).
   */
  public PredeterminadosGuardados predeterminados(boolean compartido) {
    // La subconsulta del nombre no entra en el FOR SHARE (solo bloquea la fila de p).
    PredeterminadosGuardados fila =
        jdbc.query(
            """
            SELECT p.operarios, p.bultos_hora, p.orden_cluster1, p.orden_cluster2, p.orden_cluster3,
                   p.actualizado_por, p.actualizado_en, p.bloqueado,
            """
                + NOMBRE_ACTOR
                + """
                   AS bloqueado_por
            FROM simulacion_santa.predeterminados p
            WHERE p.id = 1
            """
                + (compartido ? " FOR SHARE" : ""),
            rs -> {
              if (!rs.next()) {
                return null;
              }
              Instant en = instante(rs, "actualizado_en");
              return new PredeterminadosGuardados(
                  rs.getInt("operarios"),
                  rs.getInt("bultos_hora"),
                  List.of(
                      rs.getString("orden_cluster1"),
                      rs.getString("orden_cluster2"),
                      rs.getString("orden_cluster3")),
                  rs.getObject("actualizado_por", Long.class),
                  en,
                  candado(rs.getBoolean("bloqueado"), rs.getString("bloqueado_por"), en),
                  List.of());
            });
    if (fila == null) {
      return null;
    }
    List<UbicacionGuardada> ubicaciones =
        jdbc.query(
            """
            SELECT tipo, numero, cluster, posiciones
            FROM simulacion_santa.predeterminados_ubicaciones
            ORDER BY tipo, numero
            """,
            (rs, i) -> ubicacion(rs));
    return new PredeterminadosGuardados(
        fila.operarios(),
        fila.bultosHora(),
        fila.ordenClusters(),
        fila.actualizadoPor(),
        fila.actualizadoEn(),
        fila.candado(),
        ubicaciones);
  }

  /**
   * El candado de la configuración (abierto si falta la fila), con el nombre de quien bloqueó en
   * la misma consulta. Lectura simple, sin FOR SHARE: quien configura un registro ya tiene su
   * fila FOR UPDATE y bloquear espera por ella para aplicarle la configuración, así que un
   * bloqueo simultáneo igual termina aplicándose (y no hay abrazo mortal entre las dos filas).
   */
  public Candado candado() {
    Candado candado =
        jdbc.query(
            "SELECT p.bloqueado, p.actualizado_en, "
                + NOMBRE_ACTOR
                + " AS bloqueado_por FROM simulacion_santa.predeterminados p WHERE p.id = 1",
            rs ->
                rs.next()
                    ? candado(
                        rs.getBoolean("bloqueado"),
                        rs.getString("bloqueado_por"),
                        instante(rs, "actualizado_en"))
                    : null);
    return candado == null ? Candado.ABIERTO : candado;
  }

  /**
   * Guarda los predeterminados con su candado (crea la fila si falta: el UPSERT la bloquea hasta
   * el commit) y reemplaza sus 43 ubicaciones.
   */
  public void guardarPredeterminados(
      int operarios,
      int bultosHora,
      List<String> ordenClusters,
      List<UbicacionGuardada> ubicaciones,
      boolean bloqueado,
      Long actor,
      Instant ahora) {
    jdbc.update(
        """
        INSERT INTO simulacion_santa.predeterminados
            (id, operarios, bultos_hora, orden_cluster1, orden_cluster2, orden_cluster3,
             bloqueado, actualizado_por, actualizado_en)
        VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (id) DO UPDATE SET
            operarios = EXCLUDED.operarios,
            bultos_hora = EXCLUDED.bultos_hora,
            orden_cluster1 = EXCLUDED.orden_cluster1,
            orden_cluster2 = EXCLUDED.orden_cluster2,
            orden_cluster3 = EXCLUDED.orden_cluster3,
            bloqueado = EXCLUDED.bloqueado,
            actualizado_por = EXCLUDED.actualizado_por,
            actualizado_en = EXCLUDED.actualizado_en
        """,
        operarios,
        bultosHora,
        ordenClusters.get(0),
        ordenClusters.get(1),
        ordenClusters.get(2),
        bloqueado,
        actor,
        OffsetDateTime.ofInstant(ahora, ZoneOffset.UTC));
    jdbc.batchUpdate(
        """
        INSERT INTO simulacion_santa.predeterminados_ubicaciones (tipo, numero, cluster, posiciones)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (tipo, numero)
        DO UPDATE SET cluster = EXCLUDED.cluster, posiciones = EXCLUDED.posiciones
        """,
        ubicaciones,
        LOTE,
        (ps, u) -> {
          ps.setString(1, u.tipo());
          ps.setShort(2, (short) u.numero());
          ps.setObject(3, u.cluster() == null ? null : u.cluster().shortValue(), Types.SMALLINT);
          ps.setObject(4, u.posiciones() == null ? null : u.posiciones().shortValue(), Types.SMALLINT);
        });
  }

  /**
   * Abre la configuración para cambiarla; los valores se conservan. Sin efecto si ya estaba
   * abierta o falta la fila (entonces no hay nada bloqueado).
   *
   * @return si la fila estaba bloqueada y se abrió
   */
  public boolean desbloquearPredeterminados(Long actor, Instant ahora) {
    return jdbc.update(
            """
            UPDATE simulacion_santa.predeterminados
            SET bloqueado = false, actualizado_por = ?, actualizado_en = ?
            WHERE id = 1 AND bloqueado
            """,
            actor,
            OffsetDateTime.ofInstant(ahora, ZoneOffset.UTC))
        > 0;
  }

  /**
   * Lleva la configuración completa de los predeterminados (ya guardados en esta transacción) a
   * los registros PENDIENTE (envío 0): operarios, bultos por hora, orden de los 3 clusters y el
   * cluster y las posiciones de cada rampa y pasillo. Los EN_CURSO y COMPLETO conservan la suya.
   * Tres sentencias, sin recorrer registros:
   *
   * <ol>
   *   <li>bloquea los pendientes en orden de id (FOR UPDATE): quien esté configurando o moviendo
   *       el envío de uno termina antes, y si lo sacó de PENDIENTE ya no entra;
   *   <li>copia las 43 ubicaciones de los predeterminados a esos registros, solo las distintas;
   *   <li>actualiza la fila de cada registro que cambió en algo (valores o alguna ubicación), con
   *       su configurado_por/en y actualizado_por/en.
   * </ol>
   *
   * @return cuántos registros cambiaron
   */
  public int aplicarPredeterminados(
      int operarios, int bultosHora, List<String> ordenClusters, Long actor, Instant ahora) {
    // Q: registros pendientes (idx_registros_estado): WHERE estado = 'PENDIENTE'.
    List<Long> pendientes =
        jdbc.queryForList(
            """
            SELECT id FROM simulacion_santa.registros
            WHERE estado = 'PENDIENTE'
            ORDER BY id
            FOR UPDATE
            """,
            Long.class);
    if (pendientes.isEmpty()) {
      return 0;
    }
    Long[] ids = pendientes.toArray(Long[]::new);
    // Q: ubicaciones de esos registros por su PK (registro_id, tipo, numero).
    List<Long> conUbicacionesNuevas =
        jdbc.query(
            conexion -> {
              var ps =
                  conexion.prepareStatement(
                      """
                      INSERT INTO simulacion_santa.ubicaciones AS u
                          (registro_id, tipo, numero, cluster, posiciones)
                      SELECT r.id, p.tipo, p.numero, p.cluster, p.posiciones
                      FROM unnest(?::bigint[]) AS r(id)
                      CROSS JOIN simulacion_santa.predeterminados_ubicaciones p
                      ON CONFLICT (registro_id, tipo, numero) DO UPDATE
                      SET cluster = EXCLUDED.cluster, posiciones = EXCLUDED.posiciones
                      WHERE (u.cluster, u.posiciones)
                          IS DISTINCT FROM (EXCLUDED.cluster, EXCLUDED.posiciones)
                      RETURNING u.registro_id
                      """);
              ps.setArray(1, conexion.createArrayOf("bigint", ids));
              return ps;
            },
            (rs, i) -> rs.getLong(1));
    Long[] cambiados = conUbicacionesNuevas.stream().distinct().toArray(Long[]::new);
    OffsetDateTime en = OffsetDateTime.ofInstant(ahora, ZoneOffset.UTC);
    // Q: registros por su PK (id = ANY).
    return jdbc.update(
        conexion -> {
          var ps =
              conexion.prepareStatement(
                  """
                  UPDATE simulacion_santa.registros
                  SET operarios = ?, bultos_hora = ?,
                      orden_cluster1 = ?, orden_cluster2 = ?, orden_cluster3 = ?,
                      configurado_por = ?, configurado_en = ?,
                      actualizado_por = ?, actualizado_en = ?
                  WHERE id = ANY(?::bigint[])
                    AND (operarios <> ? OR bultos_hora <> ?
                         OR orden_cluster1 <> ? OR orden_cluster2 <> ? OR orden_cluster3 <> ?
                         OR id = ANY(?::bigint[]))
                  """);
          int i = 1;
          ps.setInt(i++, operarios);
          ps.setInt(i++, bultosHora);
          for (String orden : ordenClusters) {
            ps.setString(i++, orden);
          }
          ps.setObject(i++, actor, Types.BIGINT);
          ps.setObject(i++, en);
          ps.setObject(i++, actor, Types.BIGINT);
          ps.setObject(i++, en);
          ps.setArray(i++, conexion.createArrayOf("bigint", ids));
          ps.setInt(i++, operarios);
          ps.setInt(i++, bultosHora);
          for (String orden : ordenClusters) {
            ps.setString(i++, orden);
          }
          ps.setArray(i, conexion.createArrayOf("bigint", cambiados));
          return ps;
        });
  }

  private static Candado candado(boolean bloqueado, String bloqueadoPor, Instant en) {
    return bloqueado ? new Candado(true, bloqueadoPor, en) : Candado.ABIERTO;
  }

  private static Instant instante(ResultSet rs, String columna) throws SQLException {
    OffsetDateTime valor = rs.getObject(columna, OffsetDateTime.class);
    return valor == null ? null : valor.toInstant();
  }

  private static UbicacionGuardada ubicacion(ResultSet rs) throws SQLException {
    short cluster = rs.getShort("cluster");
    Integer conCluster = rs.wasNull() ? null : (int) cluster;
    short posiciones = rs.getShort("posiciones");
    Integer conPosiciones = rs.wasNull() ? null : (int) posiciones;
    return new UbicacionGuardada(rs.getString("tipo"), rs.getInt("numero"), conCluster, conPosiciones);
  }
}
