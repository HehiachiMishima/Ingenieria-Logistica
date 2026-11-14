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
   * Rutas de un cluster para su balanceo, en solo lectura: Hibernate no guarda
   * copias para detectar cambios; los chutes nuevos se guardan en lote. Usa
   * idx_rutas_archivo_cluster.
   */
  @QueryHints(@QueryHint(name = HibernateHints.HINT_READ_ONLY, value = "true"))
  List<RutaProyeccion> findByArchivoIdAndClusterIdOrderByIdAsc(Long archivoId, Long clusterId);

  Optional<RutaProyeccion> findByIdAndArchivoId(Long id, Long archivoId);

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
             COALESCE(SUM(r.conteoCod), 0) AS cod,
             r.frecuencia AS frecuencia
        FROM RutaProyeccion r
       WHERE r.archivoId = :archivoId
         AND r.clusterCambiado = true
       GROUP BY r.clusterOriginalId, r.clusterId, r.frecuencia
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
             COALESCE(SUM(r.conteoCod), 0) AS cod,
             r.frecuencia AS frecuencia
        FROM RutaProyeccion r
       WHERE r.archivoId = :archivoId
       GROUP BY r.clusterId, r.chuteId, r.frecuencia
      """)
  List<CargaRutas> cargaPorArchivo(@Param("archivoId") Long archivoId);

  /** La misma carga, de un solo cluster (comparativo de un lanzamiento): idx_rutas_archivo_cluster. */
  @Query("""
      SELECT r.clusterId AS clusterId,
             r.chuteId AS chuteId,
             COUNT(r) AS rutas,
             COALESCE(SUM(r.totalBultos), 0) AS bultos,
             COALESCE(SUM(r.totalUnidades), 0) AS unidades,
             COALESCE(SUM(r.totalLineas), 0) AS lineas,
             COALESCE(SUM(r.conteoCod), 0) AS cod,
             r.frecuencia AS frecuencia
        FROM RutaProyeccion r
       WHERE r.archivoId = :archivoId AND r.clusterId = :clusterId
       GROUP BY r.clusterId, r.chuteId, r.frecuencia
      """)
  List<CargaRutas> cargaPorCluster(@Param("archivoId") Long archivoId, @Param("clusterId") Long clusterId);

  /**
   * Carga por (cluster, grupo, chute): grupos de cada cluster, su carga y
   * dónde están hoy sus rutas (modal de balanceo y configuración). Usa
   * idx_rutas_archivo_cluster.
   */
  @Query("""
      SELECT r.clusterId AS clusterId,
             r.grupo AS grupo,
             r.chuteId AS chuteId,
             COUNT(r) AS rutas,
             COALESCE(SUM(r.totalBultos), 0) AS bultos,
             r.frecuencia AS frecuencia
        FROM RutaProyeccion r
       WHERE r.archivoId = :archivoId
       GROUP BY r.clusterId, r.grupo, r.chuteId, r.frecuencia
      """)
  List<CargaGrupo> cargaPorGrupo(@Param("archivoId") Long archivoId);

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
             COALESCE(SUM(r.conteoCod), 0) AS cod,
             r.frecuencia AS frecuencia
        FROM RutaProyeccion r
       WHERE r.archivoId = :archivoId
       GROUP BY r.clusterOriginalId, r.chuteOriginalId, r.frecuencia
      """)
  List<CargaRutas> cargaOriginalPorArchivo(@Param("archivoId") Long archivoId);

  /**
   * Rutas que cambiaron de chute, agrupadas por (chute original, chute actual).
   * Con clusterId, solo las de ese cluster (vista de un lanzamiento).
   */
  @Query("""
      SELECT r.chuteOriginalId AS origen,
             r.chuteId AS destino,
             COUNT(r) AS rutas,
             r.frecuencia AS frecuencia
        FROM RutaProyeccion r
       WHERE r.archivoId = :archivoId
         AND r.movida = true
         AND (:clusterId IS NULL OR r.clusterId = :clusterId)
       GROUP BY r.chuteOriginalId, r.chuteId, r.frecuencia
      """)
  List<MovimientoRutas> movimientosPorArchivo(
      @Param("archivoId") Long archivoId, @Param("clusterId") Long clusterId);

  /** Rutas movidas de cada cluster (hoy en un chute distinto al original). */
  @Query("""
      SELECT r.clusterId AS clusterId,
             COUNT(r) AS rutas,
             r.frecuencia AS frecuencia
        FROM RutaProyeccion r
       WHERE r.archivoId = :archivoId
         AND r.movida = true
       GROUP BY r.clusterId, r.frecuencia
      """)
  List<RutasMovidasCluster> rutasMovidasPorCluster(@Param("archivoId") Long archivoId);

  /**
   * Todo el registro vuelve a la proyección sin recorrer las rutas una a una: cada ruta movida o
   * cambiada de cluster vuelve a su chute y cluster originales (los tres balanceos a la vez).
   */
  @Modifying
  @Query("""
      UPDATE RutaProyeccion r
         SET r.chuteId = r.chuteOriginalId,
             r.clusterId = r.clusterOriginalId
       WHERE r.archivoId = :archivoId
         AND (r.movida = true OR r.clusterCambiado = true)
      """)
  int restaurarTodas(@Param("archivoId") Long archivoId);

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
         AND frecuencia IN (:frecuencias)
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
      @Param("soloCambios") boolean soloCambios,
      @Param("frecuencias") Collection<String> frecuencias);

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
      @Param("soloCambios") boolean soloCambios,
      @Param("frecuencias") Collection<String> frecuencias);

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
      boolean soloCambios,
      Collection<String> frecuencias) {
    return antes
        ? resumenAntes(archivoId, texto, tipo, chuteIds, clusterId, soloCambios, frecuencias)
        : resumenDespues(archivoId, texto, tipo, chuteIds, clusterId, soloCambios, frecuencias);
  }
}
