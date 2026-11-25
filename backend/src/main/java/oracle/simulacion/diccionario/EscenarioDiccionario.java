package oracle.simulacion.diccionario;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import oracle.common.config.CacheRespuestas;
import oracle.common.config.Transacciones;
import oracle.simulacion.model.ParametroChute;
import oracle.simulacion.model.Predeterminados;
import oracle.simulacion.repository.SimulacionLotesRepository;
import oracle.simulacion.service.NombresSimulacion;
import oracle.simulacion.service.PlanoChutes;
import oracle.simulacion.service.SimulacionPredeterminadosService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * El escenario único con el que trabajan Configuración y el Dashboard: una fila de
 * {@code archivos_excel} marcada {@code es_diccionario}, con las rutas COMPLETAS del Registro (en
 * un cluster, con tipo, frecuencia y grupo). Las de Datos o incompletas no entran.
 *
 * <p>Cada ruta entra con un chute al azar del plano (sin los bloqueados) que se fija la primera
 * vez: es su chute «original» (el «antes» del Dashboard) y de ahí parte el balanceo, que sigue
 * igual que siempre. Lo que se hace en Configuración (mover de chute o de cluster, balancear,
 * operarios, cerrar) no cambia el Registro. Los clusters cerrados en Configuración no se tocan.
 */
@Component
public class EscenarioDiccionario {

  /** Una ruta del Registro está completa: así entra al escenario. */
  private static final String COMPLETA =
      "r.cluster_id IS NOT NULL AND r.tipo IS NOT NULL AND r.frecuencia IS NOT NULL AND r.grupo IS NOT NULL";

  /** La fila del escenario está en un cluster cerrado en Configuración: no se toca. */
  private static final String CERRADO =
      """
      EXISTS (SELECT 1 FROM simulacion.parametros_clusters p
              WHERE p.archivo_id = rp.archivo_id AND p.cluster_id = rp.cluster_id AND p.estado = 'CERRADO')
      """;

  /** Totales de una ruta del Registro: la suma de sus locales. */
  private static final String TOTALES =
      """
      LEFT JOIN LATERAL (
          SELECT COALESCE(sum(l.bultos), 0) AS bultos, COALESCE(sum(l.unidades), 0) AS unidades,
                 COALESCE(sum(l.lineas), 0) AS lineas, count(l.*)::int AS locales
          FROM simulacion.diccionario_locales l WHERE l.ruta_id = r.id) t ON TRUE
      """;

  /** Días de cada frecuencia (los mismos que guardaba el Excel). */
  private static final String DIAS =
      "CASE r.frecuencia WHEN 'LXV' THEN 'L-X-V' WHEN 'MJS' THEN 'M-J-S' ELSE 'L-M-X-J-V-S' END";

  private final JdbcTemplate jdbc;
  private final SimulacionLotesRepository lotes;
  private final PlanoChutes plano;
  private final Transacciones transacciones;
  private final CacheRespuestas cache;

  public EscenarioDiccionario(
      JdbcTemplate jdbc,
      SimulacionLotesRepository lotes,
      PlanoChutes plano,
      Transacciones transacciones,
      CacheRespuestas cache) {
    this.jdbc = jdbc;
    this.lotes = lotes;
    this.plano = plano;
    this.transacciones = transacciones;
    this.cache = cache;
  }

  /** Id del escenario, o null si aún no se creó. */
  public Long buscar() {
    // Q: uq_archivos_es_diccionario.
    List<Long> ids =
        jdbc.queryForList("SELECT id FROM simulacion.archivos_excel WHERE es_diccionario", Long.class);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  /**
   * Id del escenario; si no existe, lo crea con la configuración y los chutes de siempre (velocidad
   * y buff predeterminados, todos los chutes visibles del plano) y le trae las rutas completas que
   * ya tenga el Registro. Debe llamarse dentro de una transacción de escritura.
   */
  public long asegurar(Long actor) {
    Long id = buscar();
    if (id != null) {
      return id;
    }
    // Dos primeras peticiones a la vez no crean dos escenarios (el índice único también lo impide).
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtext('simulacion.escenario_diccionario'))::text", String.class);
    id = buscar();
    if (id != null) {
      return id;
    }
    Long nuevo =
        jdbc.queryForObject(
            """
            INSERT INTO simulacion.archivos_excel
                (nombre_registro, nombre_archivo, estado, es_diccionario, creado_por, actualizado_por)
            VALUES ('Registro', 'Registro de rutas', 'PROYECCION', TRUE, ?, ?)
            RETURNING id
            """,
            Long.class,
            actor,
            actor);
    long archivoId = nuevo == null ? 0 : nuevo;
    Predeterminados predeterminados = SimulacionPredeterminadosService.actuales(lotes);
    List<Long> chutes = plano.idsVisibles();
    lotes.insertarConfig(archivoId, predeterminados.velocidad(), chutes.size(), actor);
    lotes.insertarParametrosChute(
        archivoId, chutes, ParametroChute.OPERARIOS_INICIALES, predeterminados.chutesBuff());
    sincronizar(archivoId, null, actor);
    return archivoId;
  }

  /**
   * Clusters del Registro que están cerrados en Configuración (por su nombre en el catálogo): el
   * Registro no los muestra y no deja cambiar sus rutas hasta que se reabran.
   */
  public Set<Long> clustersCerrados(Long archivoId) {
    if (archivoId == null) {
      return Set.of();
    }
    return new HashSet<>(
        jdbc.queryForList(
            """
            SELECT dc.id FROM simulacion.diccionario_clusters dc
            JOIN simulacion.clusters c ON c.nombre = dc.nombre
            WHERE EXISTS (SELECT 1 FROM simulacion.parametros_clusters p
                          WHERE p.archivo_id = ? AND p.cluster_id = c.id AND p.estado = 'CERRADO')
            """,
            Long.class,
            archivoId));
  }

  /**
   * Pone al día el escenario con esas rutas del Registro ({@code ids} null = todas): entran las que
   * quedaron completas (con su chute al azar), se actualizan las que cambiaron y salen las que ya
   * no están completas. Los clusters cerrados en Configuración no se tocan.
   */
  public void sincronizar(long archivoId, Collection<Long> ids, Long actor) {
    String enRegistro = ids == null ? "" : " AND r.id = ANY (?::bigint[])";
    String enEscenario = ids == null ? "" : " AND rp.diccionario_ruta_id = ANY (?::bigint[])";
    Object[] ambito = ids == null ? new Object[0] : new Object[] {DiccionarioRutasRepository.arreglo(ids)};

    // Los clusters del Registro con rutas completas existen en el catálogo y tienen sus parámetros.
    List<String> nombres =
        jdbc.queryForList(
            "SELECT DISTINCT dc.nombre FROM simulacion.diccionario_rutas r"
                + " JOIN simulacion.diccionario_clusters dc ON dc.id = r.cluster_id WHERE "
                + COMPLETA
                + enRegistro,
            String.class,
            ambito);
    if (!nombres.isEmpty()) {
      Map<String, Long> clusters = lotes.asegurarClusters(nombres);
      lotes.asegurarParametrosCluster(archivoId, clusters.values());
    }

    // Salen las que ya no están completas (o volvieron a Datos).
    jdbc.update(
        "DELETE FROM simulacion.rutas_proyeccion rp WHERE rp.archivo_id = ? AND rp.diccionario_ruta_id IS NOT NULL"
            + enEscenario
            + " AND NOT EXISTS (SELECT 1 FROM simulacion.diccionario_rutas r WHERE r.id = rp.diccionario_ruta_id AND "
            + COMPLETA
            + ") AND NOT "
            + CERRADO,
        conArchivo(archivoId, ambito));

    // Se actualizan las que siguen: si en el Registro cambió de cluster, pasa a ese cluster y
    // conserva su chute; si no, queda donde la dejó Configuración.
    jdbc.update(
        "UPDATE simulacion.rutas_proyeccion rp SET codigo_ruta = r.codigo_ruta, tipo = r.tipo,"
            + " frecuencia = r.frecuencia, dias = "
            + DIAS
            + ", grupo = r.grupo,"
            + " cluster_id = CASE WHEN rp.cluster_original_id = c.id THEN rp.cluster_id ELSE c.id END,"
            + " cluster_original_id = c.id, total_bultos = t.bultos, total_unidades = t.unidades,"
            + " total_lineas = t.lineas, conteo_cod = t.locales"
            + " FROM simulacion.diccionario_rutas r"
            + " JOIN simulacion.diccionario_clusters dc ON dc.id = r.cluster_id"
            + " JOIN simulacion.clusters c ON c.nombre = dc.nombre "
            + TOTALES
            + " WHERE rp.archivo_id = ? AND rp.diccionario_ruta_id = r.id AND "
            + COMPLETA
            + enRegistro
            + " AND NOT "
            + CERRADO,
        conArchivo(archivoId, ambito));

    // Entran las nuevas completas, cada una en un chute al azar del plano (sin los bloqueados).
    List<Nueva> nuevas =
        jdbc.query(
            "SELECT r.id, r.codigo_ruta, r.tipo, r.frecuencia, r.grupo, c.id,"
                + " t.bultos, t.unidades, t.lineas, t.locales, "
                + DIAS
                + " FROM simulacion.diccionario_rutas r"
                + " JOIN simulacion.diccionario_clusters dc ON dc.id = r.cluster_id"
                + " JOIN simulacion.clusters c ON c.nombre = dc.nombre "
                + TOTALES
                + " WHERE "
                + COMPLETA
                + enRegistro
                + " AND NOT EXISTS (SELECT 1 FROM simulacion.rutas_proyeccion rp"
                + "   WHERE rp.archivo_id = ? AND rp.diccionario_ruta_id = r.id)"
                + " AND NOT EXISTS (SELECT 1 FROM simulacion.parametros_clusters p"
                + "   WHERE p.archivo_id = ? AND p.cluster_id = c.id AND p.estado = 'CERRADO')",
            (rs, i) ->
                new Nueva(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getLong(6),
                    rs.getBigDecimal(7),
                    rs.getBigDecimal(8),
                    rs.getBigDecimal(9),
                    rs.getInt(10),
                    rs.getString(11)),
            conDosArchivos(ambito, archivoId));
    if (!nuevas.isEmpty()) {
      insertar(archivoId, nuevas);
    }

    // Los locales de las que entraron o cambiaron salen del Registro.
    jdbc.update(
        "DELETE FROM simulacion.rutas_locales l USING simulacion.rutas_proyeccion rp"
            + " WHERE l.ruta_id = rp.id AND rp.archivo_id = ? AND rp.diccionario_ruta_id IS NOT NULL"
            + enEscenario
            + " AND NOT "
            + CERRADO,
        conArchivo(archivoId, ambito));
    jdbc.update(
        "INSERT INTO simulacion.rutas_locales (ruta_id, orden, cod, filas, bultos, unidades, lineas)"
            + " SELECT rp.id, l.orden, l.cod, l.filas, l.bultos, l.unidades, l.lineas"
            + " FROM simulacion.rutas_proyeccion rp"
            + " JOIN simulacion.diccionario_locales l ON l.ruta_id = rp.diccionario_ruta_id"
            + " WHERE rp.archivo_id = ?"
            + enEscenario
            + " AND NOT "
            + CERRADO,
        conArchivo(archivoId, ambito));

    actualizarTotales(archivoId, actor);
    // Configuración y el Dashboard guardan respuestas en caché: ya no valen.
    transacciones.alConfirmar(() -> cache.invalidarEspacio("simulacion"));
  }

  /** Una ruta del Registro que entra al escenario. */
  private record Nueva(
      long rutaId,
      String codigo,
      String tipo,
      String frecuencia,
      String grupo,
      long clusterId,
      BigDecimal bultos,
      BigDecimal unidades,
      BigDecimal lineas,
      int locales,
      String dias) {}

  private void insertar(long archivoId, List<Nueva> nuevas) {
    List<Long> chutes = plano.idsVisibles();
    if (chutes.isEmpty()) {
      throw new IllegalStateException("El plano de chutes no tiene chutes visibles.");
    }
    List<Object[]> filas = new ArrayList<>(nuevas.size());
    for (Nueva n : nuevas) {
      // Al azar y una sola vez: es su chute original (el «antes») y de ahí parte el balanceo.
      long chute = chutes.get(ThreadLocalRandom.current().nextInt(chutes.size()));
      filas.add(
          new Object[] {
            archivoId, chute, chute, n.clusterId(), NombresSimulacion.nombre(n.codigo()), n.tipo(),
            n.bultos(), n.unidades(), n.lineas(), n.locales(), n.clusterId(), NombresSimulacion.nombre(n.grupo()),
            n.frecuencia(), n.dias(), n.rutaId()
          });
    }
    jdbc.batchUpdate(
        """
        INSERT INTO simulacion.rutas_proyeccion
            (archivo_id, chute_id, chute_original_id, cluster_id, codigo_ruta, tipo,
             total_bultos, total_unidades, total_lineas, conteo_cod, cluster_original_id, grupo,
             frecuencia, dias, diccionario_ruta_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        filas);
  }

  /** Totales del escenario (los que muestran Configuración y el Dashboard). */
  private void actualizarTotales(long archivoId, Long actor) {
    // Q: idx_rutas_archivo_id (archivo_id).
    jdbc.update(
        """
        UPDATE simulacion.archivos_excel a
        SET total_rutas = s.rutas, total_chutes = s.chutes, total_bultos = s.bultos,
            total_unidades = s.unidades, total_lineas = s.lineas, total_cod = s.cod,
            actualizado_por = ?, actualizado_en = now()
        FROM (SELECT count(*)::int AS rutas, count(DISTINCT chute_id)::int AS chutes,
                     COALESCE(sum(total_bultos), 0) AS bultos, COALESCE(sum(total_unidades), 0) AS unidades,
                     COALESCE(sum(total_lineas), 0) AS lineas, COALESCE(sum(conteo_cod), 0)::int AS cod
              FROM simulacion.rutas_proyeccion WHERE archivo_id = ?) s
        WHERE a.id = ?
        """,
        actor,
        archivoId,
        archivoId);
  }

  private static Object[] conArchivo(long archivoId, Object[] ambito) {
    Object[] args = new Object[ambito.length + 1];
    args[0] = archivoId;
    System.arraycopy(ambito, 0, args, 1, ambito.length);
    return args;
  }

  /** Parámetros de la consulta de nuevas: el ámbito (si hay) y luego el archivo dos veces. */
  private static Object[] conDosArchivos(Object[] ambito, long archivoId) {
    Object[] args = new Object[ambito.length + 2];
    System.arraycopy(ambito, 0, args, 0, ambito.length);
    args[ambito.length] = archivoId;
    args[ambito.length + 1] = archivoId;
    return args;
  }
}
