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
import java.util.function.Predicate;
import oracle.simulacion.model.HistorialBalanceo;
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

  public void insertarParametrosCluster(Long archivoId, Collection<Long> clusterIds) {
    jdbc.batchUpdate(
        "INSERT INTO parametros_clusters (archivo_id, cluster_id, limite_horas) VALUES (?, ?, 0)",
        new ArrayList<>(clusterIds),
        LOTE,
        (ps, clusterId) -> {
          ps.setLong(1, archivoId);
          ps.setLong(2, clusterId);
        });
  }

  /** Los operarios iniciales son la base de cada chute hasta que el usuario la cambie. */
  public void insertarParametrosChute(Long archivoId, Collection<Long> chuteIds, int operarios) {
    jdbc.batchUpdate(
        "INSERT INTO parametros_chutes (archivo_id, chute_id, operarios) VALUES (?, ?, ?)",
        new ArrayList<>(chuteIds),
        LOTE,
        (ps, chuteId) -> {
          ps.setLong(1, archivoId);
          ps.setLong(2, chuteId);
          ps.setInt(3, operarios);
        });
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
             operarios_destino, creado_por, creado_en)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
             total_bultos, total_unidades, total_lineas, conteo_cod, cluster_original_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        });
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
