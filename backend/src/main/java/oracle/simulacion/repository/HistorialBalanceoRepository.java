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

  /**
   * Cambios sin lanzamiento (a mano) de esos balanceos, paginados. Un día ve también los de
   * Diarias: una diaria movida a mano cambia en los dos días.
   */
  Page<HistorialBalanceo> findByArchivoIdAndBalanceoInAndLanzamientoIdIsNull(
      Long archivoId, Collection<String> balanceos, Pageable pageable);

  long countByArchivoIdAndBalanceoInAndLanzamientoIdIsNull(
      Long archivoId, Collection<String> balanceos);

  /** Todo el historial del registro, en una sola sentencia (restablecer todo). */
  @Modifying
  @Query("DELETE FROM HistorialBalanceo h WHERE h.archivoId = :archivoId")
  void borrarTodoPorArchivo(@Param("archivoId") Long archivoId);

  /**
   * Trabajo hecho mientras alguno de esos clusters estaba en curso, en los tres balanceos y del
   * último cambio al primero (los ids del historial son una sola secuencia, así que el orden es el
   * real): los movimientos de sus lanzamientos (incluidas rutas de otros clusters que movió al
   * separarse) y los cambios a mano (mover una ruta o pasarla de cluster) con ese cluster de
   * trabajo. Deshacerlos en este orden devuelve cada ruta al chute y cluster que tenía antes de
   * ese trabajo. Solo lectura: el historial se borra después en bloque.
   */
  @Query(
      value =
          """
          SELECT h.ruta_id AS rutaId,
                 h.tipo_accion AS tipoAccion,
                 h.chute_origen_id AS chuteOrigenId,
                 h.cluster_origen_id AS clusterOrigenId,
                 r.frecuencia AS frecuencia,
                 r.cluster_id AS clusterId
            FROM historial_balanceo h
            LEFT JOIN lanzamientos_balanceo l ON l.id = h.lanzamiento_id
            JOIN rutas_proyeccion r ON r.id = h.ruta_id
           WHERE h.archivo_id = :archivoId
             AND h.tipo_accion IN ('MOVER_RUTA', 'CAMBIAR_CLUSTER')
             AND COALESCE(l.cluster_id, h.cluster_trabajo_id) IN (:clusterIds)
           ORDER BY h.id DESC
          """,
      nativeQuery = true)
  List<CambioParaDeshacer> trabajoParaDeshacerAmbos(
      @Param("archivoId") Long archivoId, @Param("clusterIds") Collection<Long> clusterIds);

  /**
   * Cambios a mano con esos clusters en curso, en los tres balanceos (los de los lanzamientos se
   * borran con su lanzamiento, en cascada).
   */
  @Modifying
  @Query("""
      DELETE FROM HistorialBalanceo h
       WHERE h.archivoId = :archivoId
         AND h.lanzamientoId IS NULL
         AND h.clusterTrabajoId IN :clusterIds
      """)
  int borrarManualesDeTrabajoAmbos(
      @Param("archivoId") Long archivoId, @Param("clusterIds") Collection<Long> clusterIds);
}
