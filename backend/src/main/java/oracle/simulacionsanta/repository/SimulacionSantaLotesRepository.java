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
    PredeterminadosGuardados fila =
        jdbc.query(
            """
            SELECT operarios, bultos_hora, orden_cluster1, orden_cluster2, orden_cluster3,
                   actualizado_por, actualizado_en
            FROM simulacion_santa.predeterminados
            WHERE id = 1
            """
                + (compartido ? " FOR SHARE" : ""),
            rs -> {
              if (!rs.next()) {
                return null;
              }
              OffsetDateTime en = rs.getObject("actualizado_en", OffsetDateTime.class);
              return new PredeterminadosGuardados(
                  rs.getInt("operarios"),
                  rs.getInt("bultos_hora"),
                  List.of(
                      rs.getString("orden_cluster1"),
                      rs.getString("orden_cluster2"),
                      rs.getString("orden_cluster3")),
                  rs.getObject("actualizado_por", Long.class),
                  en == null ? null : en.toInstant(),
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
        ubicaciones);
  }

  /**
   * Guarda los predeterminados (crea la fila si falta: el UPSERT la bloquea hasta el commit) y
   * reemplaza sus 43 ubicaciones.
   */
  public void guardarPredeterminados(
      int operarios,
      int bultosHora,
      List<String> ordenClusters,
      List<UbicacionGuardada> ubicaciones,
      Long actor,
      Instant ahora) {
    jdbc.update(
        """
        INSERT INTO simulacion_santa.predeterminados
            (id, operarios, bultos_hora, orden_cluster1, orden_cluster2, orden_cluster3,
             actualizado_por, actualizado_en)
        VALUES (1, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (id) DO UPDATE SET
            operarios = EXCLUDED.operarios,
            bultos_hora = EXCLUDED.bultos_hora,
            orden_cluster1 = EXCLUDED.orden_cluster1,
            orden_cluster2 = EXCLUDED.orden_cluster2,
            orden_cluster3 = EXCLUDED.orden_cluster3,
            actualizado_por = EXCLUDED.actualizado_por,
            actualizado_en = EXCLUDED.actualizado_en
        """,
        operarios,
        bultosHora,
        ordenClusters.get(0),
        ordenClusters.get(1),
        ordenClusters.get(2),
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

  private static UbicacionGuardada ubicacion(ResultSet rs) throws SQLException {
    short cluster = rs.getShort("cluster");
    Integer conCluster = rs.wasNull() ? null : (int) cluster;
    short posiciones = rs.getShort("posiciones");
    Integer conPosiciones = rs.wasNull() ? null : (int) posiciones;
    return new UbicacionGuardada(rs.getString("tipo"), rs.getInt("numero"), conCluster, conPosiciones);
  }
}
