package oracle.simulacion.repository;

import jakarta.persistence.QueryHint;
import java.util.List;
import oracle.simulacion.model.HistorialBalanceo;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface HistorialBalanceoRepository extends JpaRepository<HistorialBalanceo, Long> {

  /** Cambios de un lanzamiento, paginados (índice de la FK lanzamiento_id). */
  Page<HistorialBalanceo> findByArchivoIdAndLanzamientoId(
      Long archivoId, Long lanzamientoId, Pageable pageable);

  /** Cambios sin lanzamiento (a mano o de versiones anteriores), paginados. */
  Page<HistorialBalanceo> findByArchivoIdAndLanzamientoIdIsNull(Long archivoId, Pageable pageable);

  long countByArchivoIdAndLanzamientoIdIsNull(Long archivoId);

  /** Borrado en una sola sentencia (el derivado deleteBy… carga y borra fila a fila). */
  @Modifying
  @Query("DELETE FROM HistorialBalanceo h WHERE h.archivoId = :archivoId")
  void borrarPorArchivo(@Param("archivoId") Long archivoId);

  /**
   * Rutas movidas al trabajar un cluster, de la última a la primera: las de
   * sus lanzamientos (incluidas rutas de otro cluster que movió al separarse)
   * y las de sus rutas sin lanzamiento (a mano o de versiones anteriores).
   * Deshacerlas en este orden devuelve cada ruta a donde estaba antes. Solo
   * lectura: se leen origen y ruta, y el historial se borra después en bloque.
   */
  @QueryHints(@QueryHint(name = HibernateHints.HINT_READ_ONLY, value = "true"))
  @Query("""
      SELECT h
        FROM HistorialBalanceo h
       WHERE h.archivoId = :archivoId
         AND h.tipoAccion = 'MOVER_RUTA'
         AND (h.lanzamientoId IN (SELECT l.id
                                    FROM LanzamientoBalanceo l
                                   WHERE l.archivoId = :archivoId
                                     AND l.clusterId = :clusterId)
              OR (h.lanzamientoId IS NULL
                  AND h.rutaId IN (SELECT r.id
                                     FROM RutaProyeccion r
                                    WHERE r.archivoId = :archivoId
                                      AND r.clusterId = :clusterId)))
       ORDER BY h.id DESC
      """)
  List<HistorialBalanceo> movimientosParaDeshacer(
      @Param("archivoId") Long archivoId, @Param("clusterId") Long clusterId);

  /**
   * Movimientos sin lanzamiento (a mano o de versiones anteriores) de las rutas de un cluster. Los
   * cambios de cluster se conservan: solo los deshace restablecer ese cluster (ver abajo).
   */
  @Modifying
  @Query("""
      DELETE FROM HistorialBalanceo h
       WHERE h.archivoId = :archivoId
         AND h.lanzamientoId IS NULL
         AND h.tipoAccion = 'MOVER_RUTA'
         AND h.rutaId IN (SELECT r.id
                            FROM RutaProyeccion r
                           WHERE r.archivoId = :archivoId
                             AND r.clusterId = :clusterId)
      """)
  void borrarSinLanzamientoDeCluster(
      @Param("archivoId") Long archivoId, @Param("clusterId") Long clusterId);

  /**
   * Primer cambio de cluster de cada ruta que hoy está fuera de su cluster original y en la que
   * participa el cluster indicado (como original o actual). Parte de las rutas cambiadas (índice
   * parcial idx_rutas_cluster_cambiado, pocas filas) y entra al historial por
   * idx_historial_cambio_cluster: no recorre el historial del registro, que tras varios balanceos
   * tiene miles de movimientos.
   */
  String PRIMEROS_CAMBIOS =
      """
      SELECT r.id AS rutaId,
             p.chute_origen_id AS chuteId,
             p.id AS historialId
        FROM rutas_proyeccion r
        CROSS JOIN LATERAL (
              SELECT h.id, h.chute_origen_id
                FROM historial_balanceo h
               WHERE h.ruta_id = r.id
                 AND h.tipo_accion = 'CAMBIAR_CLUSTER'
               ORDER BY h.id
               LIMIT 1) p
       WHERE r.archivo_id = :archivoId
         AND r.cluster_cambiado
         AND (r.cluster_id = :clusterId OR r.cluster_original_id = :clusterId)
      """;

  @Query(value = PRIMEROS_CAMBIOS, nativeQuery = true)
  List<PrimerCambioCluster> primerosCambiosDeCluster(
      @Param("archivoId") Long archivoId, @Param("clusterId") Long clusterId);

  /**
   * Todo lo manual de esas rutas desde su primer cambio de cluster (el cambio y los movimientos
   * a mano posteriores): al restablecer, la ruta vuelve a como estaba antes del cambio. Va antes
   * de devolver el cluster, porque la consulta depende de que la ruta siga cambiada.
   */
  @Modifying
  @Query(
      value =
          "DELETE FROM historial_balanceo h USING ("
              + PRIMEROS_CAMBIOS
              + ") p WHERE h.archivo_id = :archivoId AND h.ruta_id = p.rutaId"
              + " AND h.lanzamiento_id IS NULL AND h.id >= p.historialId",
      nativeQuery = true)
  int borrarDesdeCambioDeCluster(
      @Param("archivoId") Long archivoId, @Param("clusterId") Long clusterId);
}
