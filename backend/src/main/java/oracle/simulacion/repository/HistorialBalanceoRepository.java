package oracle.simulacion.repository;

import java.util.Collection;
import java.util.List;
import oracle.simulacion.model.HistorialBalanceo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
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
   * Trabajo hecho mientras alguno de esos clusters estaba en curso, del último cambio al primero:
   * los movimientos de sus lanzamientos (incluidas rutas de otros clusters que movió al separarse)
   * y los cambios a mano (mover una ruta o pasarla de cluster) con ese cluster de trabajo.
   * Deshacerlos en este orden devuelve cada ruta al chute y cluster que tenía antes de ese
   * trabajo. Solo lectura: el historial se borra después en bloque.
   */
  @Query(
      value =
          """
          SELECT h.ruta_id AS rutaId,
                 h.tipo_accion AS tipoAccion,
                 h.chute_origen_id AS chuteOrigenId,
                 h.cluster_origen_id AS clusterOrigenId
            FROM historial_balanceo h
            LEFT JOIN lanzamientos_balanceo l ON l.id = h.lanzamiento_id
           WHERE h.archivo_id = :archivoId
             AND h.ruta_id IS NOT NULL
             AND h.tipo_accion IN ('MOVER_RUTA', 'CAMBIAR_CLUSTER')
             AND COALESCE(l.cluster_id, h.cluster_trabajo_id) IN (:clusterIds)
           ORDER BY h.id DESC
          """,
      nativeQuery = true)
  List<CambioParaDeshacer> trabajoParaDeshacer(
      @Param("archivoId") Long archivoId, @Param("clusterIds") Collection<Long> clusterIds);

  /**
   * Cambios a mano hechos con alguno de esos clusters en curso (los del balanceo se borran con su
   * lanzamiento, en cascada).
   */
  @Modifying
  @Query("""
      DELETE FROM HistorialBalanceo h
       WHERE h.archivoId = :archivoId
         AND h.lanzamientoId IS NULL
         AND h.clusterTrabajoId IN :clusterIds
      """)
  int borrarManualesDeTrabajo(
      @Param("archivoId") Long archivoId, @Param("clusterIds") Collection<Long> clusterIds);
}
