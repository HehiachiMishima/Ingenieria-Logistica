package oracle.simulacion.repository;

import jakarta.persistence.QueryHint;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import oracle.simulacion.model.RutaProyeccion;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface RutaProyeccionRepository
    extends JpaRepository<RutaProyeccion, Long>, JpaSpecificationExecutor<RutaProyeccion> {

  /**
   * Todas las rutas del archivo para el balanceo, en solo lectura: Hibernate no
   * guarda copias para detectar cambios; los chutes nuevos se guardan en lote.
   */
  @QueryHints(@QueryHint(name = HibernateHints.HINT_READ_ONLY, value = "true"))
  List<RutaProyeccion> findByArchivoIdOrderByIdAsc(Long archivoId);

  Optional<RutaProyeccion> findByIdAndArchivoId(Long id, Long archivoId);

  /** Clusters con rutas en un chute: valida que un movimiento no mezcle CL1 y CL2. */
  @Query("""
      SELECT DISTINCT r.clusterId
        FROM RutaProyeccion r
       WHERE r.archivoId = :archivoId
         AND r.chuteId = :chuteId
      """)
  List<Long> clustersEnChute(
      @Param("archivoId") Long archivoId, @Param("chuteId") Long chuteId);

  /**
   * Algún cluster de la lista con rutas en el chute, sin contar una ruta (la que
   * cambia de cluster). Se detiene en la primera fila: usa
   * idx_rutas_archivo_chute_cluster sin recorrer todas las rutas del chute.
   */
  @Query(
      value =
          """
          SELECT cluster_id
            FROM rutas_proyeccion
           WHERE archivo_id = :archivoId
             AND chute_id = :chuteId
             AND cluster_id IN (:clusterIds)
             AND id <> :rutaId
           LIMIT 1
          """,
      nativeQuery = true)
  Optional<Long> clusterEnChute(
      @Param("archivoId") Long archivoId,
      @Param("chuteId") Long chuteId,
      @Param("clusterIds") Collection<Long> clusterIds,
      @Param("rutaId") Long rutaId);

  /**
   * El cluster tiene otra ruta además de la indicada. EXISTS con LIMIT 1 sobre
   * idx_rutas_archivo_cluster: no cuenta todas las rutas del cluster.
   */
  boolean existsByArchivoIdAndClusterIdAndIdNot(Long archivoId, Long clusterId, Long id);

  /** Rutas que hoy están en otro cluster, agrupadas por (cluster original, cluster actual). */
  @Query("""
      SELECT r.clusterOriginalId AS origen,
             r.clusterId AS destino,
             COUNT(r) AS rutas,
             COALESCE(SUM(r.totalBultos), 0) AS bultos,
             COALESCE(SUM(r.totalUnidades), 0) AS unidades,
             COALESCE(SUM(r.totalLineas), 0) AS lineas,
             COALESCE(SUM(r.conteoCod), 0) AS cod
        FROM RutaProyeccion r
       WHERE r.archivoId = :archivoId
         AND r.clusterCambiado = true
       GROUP BY r.clusterOriginalId, r.clusterId
      """)
  List<CambioClusterRutas> cambiosClusterPorArchivo(@Param("archivoId") Long archivoId);

  /**
   * Carga por (cluster, chute): la configuración y el dashboard trabajan con
   * estos agregados (decenas de filas) en lugar de traer todas las rutas.
   * Usa idx_rutas_archivo_cluster.
   */
  @Query("""
      SELECT r.clusterId AS clusterId,
             r.chuteId AS chuteId,
             COUNT(r) AS rutas,
             COALESCE(SUM(r.totalBultos), 0) AS bultos,
             COALESCE(SUM(r.totalUnidades), 0) AS unidades,
             COALESCE(SUM(r.totalLineas), 0) AS lineas,
             COALESCE(SUM(r.conteoCod), 0) AS cod
        FROM RutaProyeccion r
       WHERE r.archivoId = :archivoId
       GROUP BY r.clusterId, r.chuteId
      """)
  List<CargaRutas> cargaPorArchivo(@Param("archivoId") Long archivoId);

  /**
   * Carga original por (cluster original, chute original). Ambos se congelan al cargar el Excel,
   * así que el comparativo "antes" no depende de que el historial siga existiendo ni de los
   * cambios de cluster.
   */
  @Query("""
      SELECT r.clusterOriginalId AS clusterId,
             r.chuteOriginalId AS chuteId,
             COUNT(r) AS rutas,
             COALESCE(SUM(r.totalBultos), 0) AS bultos,
             COALESCE(SUM(r.totalUnidades), 0) AS unidades,
             COALESCE(SUM(r.totalLineas), 0) AS lineas,
             COALESCE(SUM(r.conteoCod), 0) AS cod
        FROM RutaProyeccion r
       WHERE r.archivoId = :archivoId
       GROUP BY r.clusterOriginalId, r.chuteOriginalId
      """)
  List<CargaRutas> cargaOriginalPorArchivo(@Param("archivoId") Long archivoId);

  /**
   * Rutas que cambiaron de chute, agrupadas por (chute original, chute actual).
   * Con clusterId, solo las de ese cluster (vista de un lanzamiento).
   */
  @Query("""
      SELECT r.chuteOriginalId AS origen,
             r.chuteId AS destino,
             COUNT(r) AS rutas
        FROM RutaProyeccion r
       WHERE r.archivoId = :archivoId
         AND r.movida = true
         AND (:clusterId IS NULL OR r.clusterId = :clusterId)
       GROUP BY r.chuteOriginalId, r.chuteId
      """)
  List<MovimientoRutas> movimientosPorArchivo(
      @Param("archivoId") Long archivoId, @Param("clusterId") Long clusterId);

  /** Rutas movidas de cada cluster (hoy en un chute distinto al original). */
  @Query("""
      SELECT r.clusterId AS clusterId,
             COUNT(r) AS rutas
        FROM RutaProyeccion r
       WHERE r.archivoId = :archivoId
         AND r.movida = true
       GROUP BY r.clusterId
      """)
  List<RutasMovidasCluster> rutasMovidasPorCluster(@Param("archivoId") Long archivoId);

  /**
   * Devuelve todas las rutas del archivo a su chute y cluster originales (el punto de inicio) sin
   * recorrerlas una a una.
   */
  @Modifying
  @Query("""
      UPDATE RutaProyeccion r
         SET r.chuteId = r.chuteOriginalId,
             r.clusterId = r.clusterOriginalId
       WHERE r.archivoId = :archivoId
         AND (r.movida = true OR r.clusterCambiado = true)
      """)
  int restaurarRutas(@Param("archivoId") Long archivoId);

  /**
   * Totales del detalle de rutas; cada lado añade su filtro de chute (ver abajo).
   * Los filtros opcionales llevan CAST: PostgreSQL necesita el tipo de un
   * parámetro que llega nulo.
   */
  String RESUMEN =
      """
      SELECT COUNT(*) AS rutas,
             COUNT(*) FILTER (WHERE tipo = 'B2C') AS rutasB2c,
             COUNT(*) FILTER (WHERE tipo = 'B2B') AS rutasB2b,
             COUNT(*) FILTER (WHERE movida OR cluster_cambiado) AS cambios,
             COALESCE(SUM(total_bultos), 0) AS bultos,
             COALESCE(SUM(total_unidades), 0) AS unidades,
             COALESCE(SUM(total_lineas), 0) AS lineas,
             COALESCE(SUM(conteo_cod), 0)::numeric AS cod
        FROM rutas_proyeccion
       WHERE archivo_id = :archivoId
         AND (CAST(:texto AS text) IS NULL OR codigo_ruta LIKE CAST(:texto AS text))
         AND (CAST(:tipo AS text) IS NULL OR tipo = CAST(:tipo AS text))
         AND (:soloCambios = FALSE OR movida OR cluster_cambiado)
      """;

  /**
   * Rutas que los chutes tenían al cargar el Excel, con su cluster de entonces (usa
   * idx_rutas_archivo_chute_original).
   */
  @Query(
      value =
          RESUMEN
              + " AND (CAST(:clusterId AS bigint) IS NULL"
              + " OR cluster_original_id = CAST(:clusterId AS bigint))"
              + " AND chute_original_id IN (:chuteIds)",
      nativeQuery = true)
  ResumenRutasFiltro resumenAntes(
      @Param("archivoId") Long archivoId,
      @Param("texto") String texto,
      @Param("tipo") String tipo,
      @Param("chuteIds") List<Long> chuteIds,
      @Param("clusterId") Long clusterId,
      @Param("soloCambios") boolean soloCambios);

  /** Rutas que los chutes tienen hoy, con su cluster actual (usa idx_rutas_archivo_chute_cluster). */
  @Query(
      value =
          RESUMEN
              + " AND (CAST(:clusterId AS bigint) IS NULL OR cluster_id = CAST(:clusterId AS bigint))"
              + " AND chute_id IN (:chuteIds)",
      nativeQuery = true)
  ResumenRutasFiltro resumenDespues(
      @Param("archivoId") Long archivoId,
      @Param("texto") String texto,
      @Param("tipo") String tipo,
      @Param("chuteIds") List<Long> chuteIds,
      @Param("clusterId") Long clusterId,
      @Param("soloCambios") boolean soloCambios);

  /**
   * Totales de un lado de los chutes elegidos: el detalle muestra los del
   * conjunto filtrado, no solo de la página.
   */
  default ResumenRutasFiltro resumenFiltrado(
      Long archivoId,
      String texto,
      String tipo,
      List<Long> chuteIds,
      boolean antes,
      Long clusterId,
      boolean soloCambios) {
    return antes
        ? resumenAntes(archivoId, texto, tipo, chuteIds, clusterId, soloCambios)
        : resumenDespues(archivoId, texto, tipo, chuteIds, clusterId, soloCambios);
  }
}
