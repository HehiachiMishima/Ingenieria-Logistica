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
                   actualizado_por, actualizado_en, bloqueado
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
                  rs.getBoolean("bloqueado"),
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
        fila.bloqueado(),
        ubicaciones);
  }

  /**
   * Si operarios y bultos por hora están bloqueados para todos los registros (false si falta la
   * fila). Lectura simple, sin FOR SHARE: quien configura un registro ya tiene su fila FOR UPDATE
   * y bloquear espera por ella para aplicarle los valores, así que un bloqueo simultáneo igual
   * termina aplicándose (y no hay abrazo mortal entre las dos filas).
   */
  public boolean predeterminadosBloqueados() {
    Boolean bloqueado =
        jdbc.query(
            "SELECT bloqueado FROM simulacion_santa.predeterminados WHERE id = 1",
            rs -> rs.next() ? rs.getBoolean(1) : null);
    return Boolean.TRUE.equals(bloqueado);
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
   * Abre operarios y bultos por hora para cambiarlos; los valores se conservan. Sin efecto si ya
   * estaban abiertos o falta la fila (entonces no hay nada bloqueado).
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
   * Lleva operarios y bultos por hora a los registros PENDIENTE (envío 0) que tengan otros; los
   * EN_CURSO y COMPLETO conservan los suyos. Una sentencia, sin recorrer registros: cuenta como
   * un cambio de configuración de cada registro tocado. Si alguien está configurando o moviendo
   * el envío de un registro, la sentencia espera su commit y vuelve a evaluar el estado.
   *
   * @return cuántos registros recibieron los valores
   */
  public int aplicarPredeterminados(int operarios, int bultosHora, Long actor, Instant ahora) {
    OffsetDateTime en = OffsetDateTime.ofInstant(ahora, ZoneOffset.UTC);
    // Q: registros pendientes (idx_registros_estado): WHERE estado = 'PENDIENTE'.
    return jdbc.update(
        """
        UPDATE simulacion_santa.registros
        SET operarios = ?, bultos_hora = ?,
            configurado_por = ?, configurado_en = ?,
            actualizado_por = ?, actualizado_en = ?
        WHERE estado = 'PENDIENTE'
          AND (operarios <> ? OR bultos_hora <> ?)
        """,
        operarios,
        bultosHora,
        actor,
        en,
        actor,
        en,
        operarios,
        bultosHora);
  }

  private static UbicacionGuardada ubicacion(ResultSet rs) throws SQLException {
    short cluster = rs.getShort("cluster");
    Integer conCluster = rs.wasNull() ? null : (int) cluster;
    short posiciones = rs.getShort("posiciones");
    Integer conPosiciones = rs.wasNull() ? null : (int) posiciones;
    return new UbicacionGuardada(rs.getString("tipo"), rs.getInt("numero"), conCluster, conPosiciones);
  }
}
