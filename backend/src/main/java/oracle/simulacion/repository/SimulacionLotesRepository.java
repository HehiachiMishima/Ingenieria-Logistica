package oracle.simulacion.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import oracle.simulacion.model.Balanceo;
import oracle.simulacion.model.HistorialBalanceo;
import oracle.simulacion.response.LocalesRutaResponse;
import oracle.simulacion.service.SimulacionExcel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Escrituras masivas del registro de un archivo de simulación.
 *
 * Se usa JdbcTemplate en lugar de JPA porque las entidades con id IDENTITY
 * desactivan el batching de Hibernate (100k rutas serían 100k INSERT) y las
 * de clave asignada harían un SELECT previo por fila. Con
 * reWriteBatchedInserts (URL de la conexión) cada lote viaja como un INSERT
 * multi-fila.
 */
@Repository
public class SimulacionLotesRepository {

  private static final int LOTE = 2_000;
  private static final int LOTE_IN = 500;
  private static final int LOTE_UPDATE = 10_000;

  private final JdbcTemplate jdbc;

  public SimulacionLotesRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Al aceptar la proyección original, sus rutas quedan listas para operarios solo en la copia. */
  public void guardarBalanceoRutas(Long id, boolean distribucionOriginal) {
    jdbc.update("""
        UPDATE simulacion.archivos_excel SET balanceo_guardado = jsonb_build_object(
            'rutas', (SELECT coalesce(jsonb_agg(to_jsonb(s)), '[]'::jsonb) FROM (
                SELECT id, cluster_id, chute_id
                FROM simulacion.rutas_proyeccion WHERE archivo_id = ? AND (movida OR cluster_cambiado)) s),
            'clusters', (SELECT coalesce(jsonb_agg(to_jsonb(s)), '[]'::jsonb) FROM (
                SELECT cluster_id, balanceo, chute_desde_id, chute_hasta_id,
                       CASE WHEN ? OR estado <> 'PENDIENTE' THEN 'RUTAS' ELSE 'PENDIENTE' END AS estado
                FROM simulacion.parametros_clusters WHERE archivo_id = ?) s),
            'grupos', (SELECT coalesce(jsonb_agg(to_jsonb(s)), '[]'::jsonb) FROM (
                SELECT archivo_id, cluster_id, grupo, prioridad, chute_desde_id, chute_hasta_id
                FROM simulacion.parametros_grupos WHERE archivo_id = ?) s),
            'lanzamientos', (SELECT coalesce(jsonb_agg(to_jsonb(s)), '[]'::jsonb) FROM (
                SELECT id, archivo_id, balanceo, cluster_id, fase, mensaje, rutas, separaciones, creado_por,
                       creado_en
                FROM simulacion.lanzamientos_balanceo WHERE archivo_id = ? AND fase = 'RUTAS') s),
            'historial', (SELECT coalesce(jsonb_agg(to_jsonb(s)), '[]'::jsonb) FROM (
                SELECT id, archivo_id, balanceo, lanzamiento_id, tipo_accion, ruta_id, chute_origen_id,
                       chute_destino_id, cluster_origen_id, cluster_destino_id, cluster_trabajo_id, motivo,
                       creado_por, creado_en
                FROM simulacion.historial_balanceo WHERE archivo_id = ? AND tipo_accion IN ('MOVER_RUTA', 'CAMBIAR_CLUSTER')) s)) WHERE id = ?
        """, id, distribucionOriginal, id, id, id, id, id);
  }

  public Optional<String> clusterSinRestablecer(Long id) {
    return jdbc.queryForList("""
        SELECT c.nombre FROM simulacion.clusters c
        WHERE c.id IN (
          SELECT cluster_id FROM simulacion.parametros_clusters
           WHERE archivo_id = ? AND estado <> 'PENDIENTE'
          UNION ALL SELECT cluster_id FROM simulacion.rutas_proyeccion
           WHERE archivo_id = ? AND (movida OR cluster_cambiado)
          UNION ALL SELECT cluster_id FROM simulacion.operarios_ola WHERE archivo_id = ?
        ) ORDER BY c.id LIMIT 1
        """, String.class, id, id, id).stream().findFirst();
  }

  // El archivo ya está bloqueado por el servicio. Las filas de historial conservan sus ids
  // porque restablecer un cluster deshace los movimientos en orden inverso.
  public void cargarBalanceoRutas(Long id) {
    jdbc.update("DELETE FROM simulacion.historial_balanceo WHERE archivo_id = ?", id);
    jdbc.update("DELETE FROM simulacion.lanzamientos_balanceo WHERE archivo_id = ?", id);
    jdbc.update("DELETE FROM simulacion.operarios_ola WHERE archivo_id = ?", id);
    jdbc.update("DELETE FROM simulacion.parametros_grupos WHERE archivo_id = ?", id);
    jdbc.update("""
        UPDATE simulacion.rutas_proyeccion SET cluster_id = cluster_original_id, chute_id = chute_original_id
        WHERE archivo_id = ? AND (movida OR cluster_cambiado)
        """, id);
    jdbc.update("""
        UPDATE simulacion.rutas_proyeccion r SET cluster_id = s.cluster_id, chute_id = s.chute_id
        FROM simulacion.archivos_excel a,
             jsonb_populate_recordset(NULL::simulacion.rutas_proyeccion, a.balanceo_guardado -> 'rutas') s
        WHERE a.id = ? AND r.archivo_id = a.id AND r.id = s.id
        """, id);
    jdbc.update("""
        UPDATE simulacion.parametros_clusters p
        SET estado = s.estado,
            chute_desde_id = s.chute_desde_id, chute_hasta_id = s.chute_hasta_id, operarios_ola = NULL
        FROM simulacion.archivos_excel a,
             jsonb_populate_recordset(NULL::simulacion.parametros_clusters, a.balanceo_guardado -> 'clusters') s
        WHERE a.id = ? AND p.archivo_id = a.id AND p.cluster_id = s.cluster_id AND p.balanceo = s.balanceo
        """, id);
    jdbc.update("""
        INSERT INTO simulacion.parametros_grupos (
            archivo_id, cluster_id, grupo, prioridad, chute_desde_id, chute_hasta_id)
        SELECT s.archivo_id, s.cluster_id, s.grupo, s.prioridad, s.chute_desde_id, s.chute_hasta_id
        FROM simulacion.archivos_excel a,
             jsonb_populate_recordset(NULL::simulacion.parametros_grupos, a.balanceo_guardado -> 'grupos') s
        WHERE a.id = ?
        """, id);
    jdbc.update("""
        INSERT INTO simulacion.lanzamientos_balanceo (
            id, archivo_id, balanceo, cluster_id, fase, mensaje, rutas, separaciones, creado_por,
            creado_en)
        OVERRIDING SYSTEM VALUE
        SELECT s.id, s.archivo_id, s.balanceo, s.cluster_id, s.fase, s.mensaje, s.rutas,
               s.separaciones, s.creado_por, s.creado_en
        FROM simulacion.archivos_excel a,
             jsonb_populate_recordset(NULL::simulacion.lanzamientos_balanceo, a.balanceo_guardado -> 'lanzamientos') s
        WHERE a.id = ?
        """, id);
    jdbc.update("""
        INSERT INTO simulacion.historial_balanceo (
            id, archivo_id, balanceo, lanzamiento_id, tipo_accion, ruta_id, chute_origen_id,
            chute_destino_id, cluster_origen_id, cluster_destino_id, cluster_trabajo_id, motivo,
            creado_por, creado_en)
        OVERRIDING SYSTEM VALUE
        SELECT s.id, s.archivo_id, s.balanceo, s.lanzamiento_id, s.tipo_accion, s.ruta_id,
               s.chute_origen_id, s.chute_destino_id, s.cluster_origen_id, s.cluster_destino_id,
               s.cluster_trabajo_id, s.motivo, s.creado_por, s.creado_en
        FROM simulacion.archivos_excel a,
             jsonb_populate_recordset(NULL::simulacion.historial_balanceo, a.balanceo_guardado -> 'historial') s
        WHERE a.id = ?
        """, id);
  }

  /** Ids de los clusters por clave de nombre; crea los que falten. */
  public Map<String, Long> asegurarClusters(Collection<String> nombres) {
    return asegurarCatalogo("clusters", nombres);
  }

  /** Ids de los chutes por clave de nombre; crea los que falten. */
  public Map<String, Long> asegurarChutes(Collection<String> nombres) {
    return asegurarCatalogo("chutes", nombres);
  }

  public void insertarConfig(
      Long archivoId, BigDecimal velocidad, int operariosMaximos, Long actor) {
    jdbc.update(
        """
        INSERT INTO simulacion_config
            (archivo_id, velocidad_base, operarios_maximos, creado_por, actualizado_por)
        VALUES (?, ?, ?, ?, ?)
        """,
        archivoId,
        velocidad,
        operariosMaximos,
        actor,
        actor);
  }

  /** Cada cluster arranca pendiente en los dos balanceos (L-X-V y M-J-S). */
  public void insertarParametrosCluster(Long archivoId, Collection<Long> clusterIds) {
    List<Object[]> filas = new ArrayList<>();
    for (Balanceo balanceo : Balanceo.values()) {
      clusterIds.forEach((clusterId) -> filas.add(new Object[] {balanceo.name(), clusterId}));
    }
    jdbc.batchUpdate(
        "INSERT INTO parametros_clusters (archivo_id, balanceo, cluster_id, limite_horas)"
            + " VALUES (?, ?, ?, 0)",
        filas,
        LOTE,
        (ps, fila) -> {
          ps.setLong(1, archivoId);
          ps.setString(2, (String) fila[0]);
          ps.setLong(3, (Long) fila[1]);
        });
  }

  /** Los operarios iniciales son la base de cada chute hasta que el usuario la cambie. */
  public void insertarParametrosChute(Long archivoId, Collection<Long> chuteIds, int operarios) {
    insertarParametrosChute(archivoId, chuteIds, operarios, java.util.Set.of());
  }

  /** Los chutes del registro; los de {@code buff} nacen con buff (los predeterminados). */
  public void insertarParametrosChute(
      Long archivoId, Collection<Long> chuteIds, int operarios, Collection<Long> buff) {
    jdbc.batchUpdate(
        "INSERT INTO parametros_chutes (archivo_id, chute_id, operarios, buff) VALUES (?, ?, ?, ?)",
        new ArrayList<>(chuteIds),
        LOTE,
        (ps, chuteId) -> {
          ps.setLong(1, archivoId);
          ps.setLong(2, chuteId);
          ps.setInt(3, operarios);
          ps.setBoolean(4, buff.contains(chuteId));
        });
  }

  // ---------------- Predeterminados (velocidad y buff de todos los registros) ----------------

  /** Registros sin balancear: ningún cluster salió de PENDIENTE en ningún balanceo. */
  private static final String SIN_BALANCEAR =
      "NOT EXISTS (SELECT 1 FROM parametros_clusters p"
          + " WHERE p.archivo_id = %s AND p.estado <> 'PENDIENTE')";

  /**
   * La fila de predeterminados; null si la base aún no la tiene. Si falta la tabla (backend
   * desplegado antes de correr migracion-predeterminados.sql), avisa y sigue con los de siempre:
   * cargar un registro no se cae por eso.
   */
  public oracle.simulacion.model.Predeterminados leerPredeterminados() {
    try {
      return consultarPredeterminados();
    } catch (org.springframework.jdbc.BadSqlGrammarException e) {
      org.slf4j.LoggerFactory.getLogger(SimulacionLotesRepository.class)
          .warn("Falta simulacion.predeterminados: corre backend/migracion-predeterminados.sql ({})", e.getMessage());
      return null;
    }
  }

  private oracle.simulacion.model.Predeterminados consultarPredeterminados() {
    return jdbc.query(
        "SELECT velocidad_base, chutes_buff, bloqueado FROM predeterminados WHERE id = 1",
        rs -> {
          if (!rs.next()) {
            return null;
          }
          Long[] buff = (Long[]) rs.getArray(2).getArray();
          return new oracle.simulacion.model.Predeterminados(
              rs.getBigDecimal(1), List.of(buff), rs.getBoolean(3));
        });
  }

  /** Guarda velocidad, buff y bloqueo (crea la fila si falta). */
  public void guardarPredeterminados(BigDecimal velocidad, Collection<Long> buff, boolean bloqueado) {
    Long[] ids = buff.toArray(Long[]::new);
    jdbc.update(
        conexion -> {
          var ps =
              conexion.prepareStatement(
                  "INSERT INTO predeterminados (id, velocidad_base, chutes_buff, bloqueado)"
                      + " VALUES (1, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET"
                      + " velocidad_base = EXCLUDED.velocidad_base,"
                      + " chutes_buff = EXCLUDED.chutes_buff, bloqueado = EXCLUDED.bloqueado");
          ps.setBigDecimal(1, velocidad);
          ps.setArray(2, conexion.createArrayOf("bigint", ids));
          ps.setBoolean(3, bloqueado);
          return ps;
        });
  }

  /** Abre los predeterminados para cambiarlos (conserva los valores). */
  public void desbloquearPredeterminados() {
    jdbc.update(
        "INSERT INTO predeterminados (id, bloqueado) VALUES (1, FALSE)"
            + " ON CONFLICT (id) DO UPDATE SET bloqueado = FALSE");
  }

  /**
   * Lleva velocidad y buff a los registros sin balancear (los ya balanceados conservan los suyos:
   * el buff ya decidió dónde quedaron sus rutas y la velocidad, sus operarios). Tres sentencias
   * en lote, sin recorrer registros. Devuelve cuántos registros recibieron los valores.
   */
  public int aplicarPredeterminados(BigDecimal velocidad, Collection<Long> buff) {
    Long[] ids = buff.toArray(Long[]::new);
    // Se bloquean los registros sin balancear (en orden, sin abrazos mortales): un balanceo que
    // empieza a la vez espera; si termina antes, su registro ya no cuenta como sin balancear.
    jdbc.query(
        "SELECT a.id FROM archivos_excel a WHERE "
            + SIN_BALANCEAR.formatted("a.id")
            + " ORDER BY a.id FOR UPDATE",
        (rs) -> null);
    int registros =
        jdbc.update(
            "UPDATE simulacion_config c SET velocidad_base = ? WHERE "
                + SIN_BALANCEAR.formatted("c.archivo_id"),
            velocidad);
    // Buff: los elegidos (con su fila aunque el registro no la tuviera)…
    jdbc.update(
        conexion -> {
          var ps =
              conexion.prepareStatement(
                  "INSERT INTO parametros_chutes (archivo_id, chute_id, operarios, buff)"
                      + " SELECT c.archivo_id, b.chute_id, 0, TRUE"
                      + " FROM simulacion_config c CROSS JOIN unnest(?::bigint[]) AS b(chute_id)"
                      + " WHERE "
                      + SIN_BALANCEAR.formatted("c.archivo_id")
                      + " ON CONFLICT (archivo_id, chute_id) DO UPDATE SET buff = TRUE"
                      + " WHERE parametros_chutes.buff = FALSE");
          ps.setArray(1, conexion.createArrayOf("bigint", ids));
          return ps;
        });
    // …y los demás, sin buff.
    jdbc.update(
        conexion -> {
          var ps =
              conexion.prepareStatement(
                  "UPDATE parametros_chutes pc SET buff = FALSE"
                      + " WHERE pc.buff AND NOT (pc.chute_id = ANY (?)) AND "
                      + SIN_BALANCEAR.formatted("pc.archivo_id"));
          ps.setArray(1, conexion.createArrayOf("bigint", ids));
          return ps;
        });
    return registros;
  }

  /**
   * Chute nuevo de cada ruta movida por el balanceo (id → chute). Un solo UPDATE por
   * bloque con los pares en dos arrays: restablecer un cluster de 100 mil rutas eran
   * ~26 mil UPDATE de una fila cada uno.
   */
  public void moverRutas(Map<Long, Long> chutes) {
    actualizarEnLote(chutes, "chute_id");
  }

  /** Cluster de cada ruta (id → cluster), en lote: deshacer cambios de cluster al restablecer. */
  public void cambiarClusters(Map<Long, Long> clusters) {
    actualizarEnLote(clusters, "cluster_id");
  }

  /** Prioridad y último tramo de los grupos de cada cluster del registro, en orden de prioridad. */
  public List<GrupoGuardado> grupos(Long archivoId) {
    return jdbc.query(
        """
        SELECT cluster_id, grupo, prioridad, chute_desde_id, chute_hasta_id
        FROM parametros_grupos
        WHERE archivo_id = ?
        ORDER BY cluster_id, prioridad
        """,
        (rs, fila) ->
            new GrupoGuardado(
                rs.getLong("cluster_id"),
                rs.getString("grupo"),
                rs.getInt("prioridad"),
                rs.getObject("chute_desde_id", Long.class),
                rs.getObject("chute_hasta_id", Long.class)),
        archivoId);
  }

  /** Reemplaza la prioridad y los tramos de los grupos de un cluster. */
  public void guardarGrupos(Long archivoId, Long clusterId, List<GrupoGuardado> grupos) {
    jdbc.update(
        "DELETE FROM parametros_grupos WHERE archivo_id = ? AND cluster_id = ?", archivoId, clusterId);
    jdbc.batchUpdate(
        """
        INSERT INTO parametros_grupos
            (archivo_id, cluster_id, grupo, prioridad, chute_desde_id, chute_hasta_id)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        grupos,
        LOTE,
        (ps, grupo) -> {
          ps.setLong(1, archivoId);
          ps.setLong(2, clusterId);
          ps.setString(3, grupo.grupo());
          ps.setInt(4, grupo.prioridad());
          ps.setObject(5, grupo.chuteDesdeId(), Types.BIGINT);
          ps.setObject(6, grupo.chuteHastaId(), Types.BIGINT);
        });
  }

  /**
   * Restablecer esos clusters: sus grupos conservan la prioridad (el modal la
   * propone) pero ya no tienen tramo, porque sus rutas volvieron atrás.
   */
  public void quitarTramos(Long archivoId, Collection<Long> clusterIds) {
    if (clusterIds.isEmpty()) {
      return;
    }
    Long[] ids = clusterIds.toArray(Long[]::new);
    jdbc.update(
        conexion -> {
          var ps =
              conexion.prepareStatement(
                  "UPDATE parametros_grupos SET chute_desde_id = NULL, chute_hasta_id = NULL"
                      + " WHERE archivo_id = ? AND cluster_id = ANY (?)");
          ps.setLong(1, archivoId);
          ps.setArray(2, conexion.createArrayOf("bigint", ids));
          return ps;
        });
  }

  /**
   * Esos clusters vuelven a la proyección en los dos balanceos: sin prioridades ni tramos de
   * grupos (el próximo balanceo los define de nuevo).
   */
  public void borrarGrupos(Long archivoId, Collection<Long> clusterIds) {
    if (clusterIds.isEmpty()) {
      return;
    }
    Long[] ids = clusterIds.toArray(Long[]::new);
    jdbc.update(
        conexion -> {
          var ps =
              conexion.prepareStatement(
                  "DELETE FROM parametros_grupos WHERE archivo_id = ? AND cluster_id = ANY (?)");
          ps.setLong(1, archivoId);
          ps.setArray(2, conexion.createArrayOf("bigint", ids));
          return ps;
        });
  }

  /** UPDATE por bloques de pares (id de ruta, valor); la columna es una constante interna. */
  private void actualizarEnLote(Map<Long, Long> valores, String columna) {
    if (valores.isEmpty()) {
      return;
    }
    String sql =
        "UPDATE rutas_proyeccion r SET " + columna + " = c.valor"
            + " FROM unnest(?::bigint[], ?::bigint[]) AS c(id, valor) WHERE r.id = c.id";
    List<Map.Entry<Long, Long>> cambios = List.copyOf(valores.entrySet());
    for (int i = 0; i < cambios.size(); i += LOTE_UPDATE) {
      List<Map.Entry<Long, Long>> bloque = cambios.subList(i, Math.min(i + LOTE_UPDATE, cambios.size()));
      Long[] ids = bloque.stream().map(Map.Entry::getKey).toArray(Long[]::new);
      Long[] destinos = bloque.stream().map(Map.Entry::getValue).toArray(Long[]::new);
      jdbc.update(
          conexion -> {
            var ps = conexion.prepareStatement(sql);
            ps.setArray(1, conexion.createArrayOf("bigint", ids));
            ps.setArray(2, conexion.createArrayOf("bigint", destinos));
            return ps;
          });
    }
  }

  /**
   * Historial del balanceo en lotes: con IDENTITY, JPA insertaría fila a fila
   * y una ejecución larga podría pasar el tiempo límite de la petición.
   */
  public void insertarHistorial(List<HistorialBalanceo> logs) {
    if (logs.isEmpty()) {
      return;
    }
    jdbc.batchUpdate(
        """
        INSERT INTO historial_balanceo
            (archivo_id, lanzamiento_id, tipo_accion, ruta_id, chute_origen_id,
             chute_destino_id, chute_modificado_id, motivo, operarios_origen,
             operarios_destino, creado_por, creado_en, balanceo)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        logs,
        LOTE,
        (ps, log) -> {
          ps.setLong(1, log.getArchivoId());
          ps.setObject(2, log.getLanzamientoId(), Types.BIGINT);
          ps.setString(3, log.getTipoAccion());
          ps.setObject(4, log.getRutaId(), Types.BIGINT);
          ps.setObject(5, log.getChuteOrigenId(), Types.BIGINT);
          ps.setObject(6, log.getChuteDestinoId(), Types.BIGINT);
          ps.setObject(7, log.getChuteModificadoId(), Types.BIGINT);
          ps.setString(8, log.getMotivo());
          ps.setObject(9, log.getOperariosOrigen(), Types.INTEGER);
          ps.setObject(10, log.getOperariosDestino(), Types.INTEGER);
          ps.setLong(11, log.getCreadoPor());
          ps.setTimestamp(12, Timestamp.from(log.getCreadoEn()));
          ps.setString(13, log.getBalanceo());
        });
  }

  public void insertarRutas(
      Long archivoId,
      List<SimulacionExcel.FilaRuta> rutas,
      Map<String, Long> clusters,
      Map<String, Long> chutes) {
    // Las rutas repiten pocos clusters/chutes: la clave (normalización Unicode) se calcula una
    // vez por nombre distinto, no dos veces por ruta.
    Map<String, Long> idCluster = new HashMap<>();
    Map<String, Long> idChute = new HashMap<>();
    jdbc.batchUpdate(
        """
        INSERT INTO rutas_proyeccion
            (archivo_id, chute_id, chute_original_id, cluster_id, codigo_ruta, tipo,
             total_bultos, total_unidades, total_lineas, conteo_cod, cluster_original_id, grupo,
             frecuencia, dias)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        rutas,
        LOTE,
        (ps, ruta) -> {
          long chuteId = idChute.computeIfAbsent(ruta.chute(), (n) -> id(chutes, "chute", n));
          ps.setLong(1, archivoId);
          ps.setLong(2, chuteId);
          // El chute original se congela al cargar: el balanceo solo cambia chute_id.
          ps.setLong(3, chuteId);
          long clusterId = idCluster.computeIfAbsent(ruta.cluster(), (n) -> id(clusters, "cluster", n));
          ps.setLong(4, clusterId);
          ps.setString(5, SimulacionExcel.nombre(ruta.codigoRuta()));
          ps.setString(6, ruta.tipo().name());
          ps.setBigDecimal(7, ruta.bultos());
          ps.setBigDecimal(8, ruta.unidades());
          ps.setBigDecimal(9, ruta.lineas());
          ps.setInt(10, ruta.conteoCod());
          // Igual que el chute: el cluster original se congela al cargar.
          ps.setLong(11, clusterId);
          ps.setString(12, SimulacionExcel.nombre(ruta.grupo()));
          ps.setString(13, ruta.frecuencia());
          ps.setString(14, ruta.dias());
        });
  }

  /** Tope de locales que devuelve el detalle de una ruta (la consulta pide uno más para saberlo). */
  public static final int MAX_LOCALES_DETALLE = 5_000;

  /** Clave única de una ruta en su registro: código canónico y tipo (como en uq_rutas_archivo_ruta_tipo). */
  public static String clave(SimulacionExcel.FilaRuta ruta) {
    return SimulacionExcel.nombre(ruta.codigoRuta()) + '\u0000' + ruta.tipo().name();
  }

  /** Totales de una ruta guardada: para reconocer la misma ruta en el Excel al completar sus locales. */
  public record TotalesRuta(long id, BigDecimal bultos, BigDecimal unidades, BigDecimal lineas) {
    public boolean coincide(SimulacionExcel.FilaRuta fila) {
      return bultos.compareTo(fila.bultos()) == 0
          && unidades.compareTo(fila.unidades()) == 0
          && lineas.compareTo(fila.lineas()) == 0;
    }
  }

  /** Un local listo para insertar: su ruta, su orden en el Excel y sus totales. */
  private record FilaLocal(long rutaId, int orden, SimulacionExcel.LocalRuta local) {}

  /**
   * Los locales de cada ruta del archivo recién insertado, en el orden del Excel. Las rutas se
   * leen de una vez por su clave única (código, tipo) y los locales van por lotes.
   */
  public void insertarLocales(Long archivoId, List<SimulacionExcel.FilaRuta> rutas) {
    Map<String, Long> ids = new HashMap<>(rutas.size() * 2);
    jdbc.query(
        "SELECT id, codigo_ruta, tipo FROM simulacion.rutas_proyeccion WHERE archivo_id = ?",
        (java.sql.ResultSet rs) -> {
          ids.put(rs.getString(2) + '\u0000' + rs.getString(3), rs.getLong(1));
        },
        archivoId);
    List<FilaLocal> filas = new ArrayList<>();
    for (SimulacionExcel.FilaRuta ruta : rutas) {
      Long rutaId = ids.get(clave(ruta));
      if (rutaId == null) {
        throw new IllegalStateException("La ruta " + ruta.codigoRuta() + " no quedó insertada.");
      }
      agregarLocales(filas, rutaId, ruta);
    }
    insertarFilas(filas);
  }

  /** Totales de cada ruta del registro, por su clave (código canónico, tipo). */
  public Map<String, TotalesRuta> totalesRutas(Long archivoId) {
    Map<String, TotalesRuta> rutas = new HashMap<>();
    // Q: idx_rutas_archivo_id (archivo_id, id).
    jdbc.query(
        """
        SELECT id, codigo_ruta, tipo, total_bultos, total_unidades, total_lineas
        FROM simulacion.rutas_proyeccion WHERE archivo_id = ?
        """,
        (java.sql.ResultSet rs) -> {
          rutas.put(
              rs.getString(2) + '\u0000' + rs.getString(3),
              new TotalesRuta(rs.getLong(1), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6)));
        },
        archivoId);
    return rutas;
  }

  /**
   * Reemplaza los locales de estas rutas (id de ruta → su ruta del Excel) y deja su conteo de
   * locales en el número de locales que trae.
   */
  public void reemplazarLocales(Map<Long, SimulacionExcel.FilaRuta> porRuta) {
    if (porRuta.isEmpty()) {
      return;
    }
    Long[] ids = porRuta.keySet().toArray(Long[]::new);
    // Q: pk_rutas_locales (ruta_id, orden).
    jdbc.update(
        conexion -> {
          var ps = conexion.prepareStatement("DELETE FROM simulacion.rutas_locales WHERE ruta_id = ANY (?)");
          ps.setArray(1, conexion.createArrayOf("bigint", ids));
          return ps;
        });
    List<FilaLocal> filas = new ArrayList<>();
    porRuta.forEach((rutaId, ruta) -> agregarLocales(filas, rutaId, ruta));
    insertarFilas(filas);
    jdbc.batchUpdate(
        "UPDATE simulacion.rutas_proyeccion SET conteo_cod = ? WHERE id = ?",
        new ArrayList<>(porRuta.entrySet()),
        LOTE,
        (ps, e) -> {
          ps.setInt(1, e.getValue().conteoCod());
          ps.setLong(2, e.getKey());
        });
  }

  /** Total de locales del registro (archivos_excel.total_cod) tras cambiar el de alguna ruta. */
  public int totalCod(Long archivoId) {
    Integer total =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(conteo_cod), 0)::int FROM simulacion.rutas_proyeccion WHERE archivo_id = ?",
            Integer.class,
            archivoId);
    return total == null ? 0 : total;
  }

  private static void agregarLocales(List<FilaLocal> filas, long rutaId, SimulacionExcel.FilaRuta ruta) {
    int orden = 0;
    for (SimulacionExcel.LocalRuta local : ruta.locales()) {
      filas.add(new FilaLocal(rutaId, ++orden, local));
    }
  }

  private void insertarFilas(List<FilaLocal> filas) {
    jdbc.batchUpdate(
        """
        INSERT INTO simulacion.rutas_locales (ruta_id, orden, cod, filas, bultos, unidades, lineas)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        filas,
        LOTE,
        (ps, f) -> {
          ps.setLong(1, f.rutaId());
          ps.setInt(2, f.orden());
          ps.setString(3, f.local().cod());
          ps.setInt(4, f.local().filas());
          ps.setBigDecimal(5, f.local().bultos());
          ps.setBigDecimal(6, f.local().unidades());
          ps.setBigDecimal(7, f.local().lineas());
        });
  }

  /**
   * Locales de una ruta del archivo, en el orden del Excel, hasta {@link #MAX_LOCALES_DETALLE} + 1
   * (el de más indica que hay más). Vacío si la ruta no es del archivo o no tiene detalle.
   */
  public List<LocalesRutaResponse.Local> localesDeRuta(Long archivoId, Long rutaId) {
    // Q: PK rutas_locales (ruta_id, orden) y PK de rutas_proyeccion (id).
    return jdbc.query(
        """
        SELECT l.cod, l.filas, l.bultos, l.unidades, l.lineas
        FROM simulacion.rutas_locales l
        JOIN simulacion.rutas_proyeccion r ON r.id = l.ruta_id
        WHERE l.ruta_id = ? AND r.archivo_id = ?
        ORDER BY l.orden
        LIMIT ?
        """,
        (rs, i) ->
            new LocalesRutaResponse.Local(
                rs.getString(1), rs.getInt(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5)),
        rutaId,
        archivoId,
        MAX_LOCALES_DETALLE + 1);
  }

  /** La ruta pertenece al archivo (para distinguir «sin detalle» de «no existe»). */
  public boolean rutaDelArchivo(Long archivoId, Long rutaId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM simulacion.rutas_proyeccion WHERE id = ? AND archivo_id = ?)",
            Boolean.class,
            rutaId,
            archivoId));
  }

  /**
   * INSERT … ON CONFLICT DO NOTHING + lectura con bloqueo compartido: si dos
   * cargas crean el mismo nombre a la vez, la segunda espera a la primera y
   * reutiliza su fila en lugar de fallar con la restricción UNIQUE. FOR SHARE
   * lee lo último confirmado (READ COMMITTED), no una instantánea vieja.
   */
  private Map<String, Long> asegurarCatalogo(String tabla, Collection<String> nombres) {
    // Se inserta el nombre canónico (espacios internos colapsados): la colación comun.ci ignora
    // mayúsculas y tildes pero NO colapsa espacios, así que "CHUTE  1" y "CHUTE 1" serían dos
    // filas con la misma clave y el id resultante dependería del orden del SELECT.
    Map<String, String> porClave = new LinkedHashMap<>();
    nombres.forEach(
        (nombre) -> porClave.putIfAbsent(SimulacionExcel.clave(nombre), SimulacionExcel.nombre(nombre)));
    List<String> unicos = new ArrayList<>(porClave.values());
    jdbc.batchUpdate(
        "INSERT INTO " + tabla + " (nombre, activo) VALUES (?, TRUE) ON CONFLICT (nombre) DO NOTHING",
        unicos,
        LOTE,
        (ps, nombre) -> ps.setString(1, nombre));

    Map<String, Long> ids = new HashMap<>();
    for (int i = 0; i < unicos.size(); i += LOTE_IN) {
      List<String> bloque = unicos.subList(i, Math.min(i + LOTE_IN, unicos.size()));
      String marcadores = String.join(",", bloque.stream().map((n) -> "?").toList());
      jdbc.query(
          "SELECT id, nombre FROM " + tabla + " WHERE nombre IN (" + marcadores + ") FOR SHARE",
          (rs) -> {
            ids.put(SimulacionExcel.clave(rs.getString("nombre")), rs.getLong("id"));
          },
          bloque.toArray());
    }
    porClave.keySet().stream()
        .filter(Predicate.not(ids::containsKey))
        .findFirst()
        .ifPresent(
            (faltante) -> {
              throw new IllegalStateException("No se pudo registrar " + tabla + " " + faltante);
            });
    return ids;
  }

  private static long id(Map<String, Long> ids, String tipo, String nombre) {
    Long id = ids.get(SimulacionExcel.clave(nombre));
    if (id == null) {
      throw new IllegalStateException("No se resolvió el id del " + tipo + " " + nombre);
    }
    return id;
  }
}
