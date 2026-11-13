package oracle.simulacion.repository;

import java.util.Collection;
import java.util.List;
import oracle.simulacion.model.LanzamientoBalanceo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LanzamientoBalanceoRepository extends JpaRepository<LanzamientoBalanceo, Long> {

  /**
   * Lanzamientos de esos balanceos del registro (un día ve también los de Diarias), del más
   * reciente al más antiguo.
   */
  List<LanzamientoBalanceo> findTop200ByArchivoIdAndBalanceoInOrderByIdDesc(
      Long archivoId, Collection<String> balanceos);

  /** Lanzamientos del cluster en los tres balanceos (restablecer o reabrir el cluster completo). */
  @Modifying
  @Query(
      "DELETE FROM LanzamientoBalanceo l"
          + " WHERE l.archivoId = :archivoId AND l.clusterId IN :clusterIds")
  void borrarPorClusters(
      @Param("archivoId") Long archivoId, @Param("clusterIds") Collection<Long> clusterIds);

  /** Todos los lanzamientos del registro (su historial se borra con ellos, ON DELETE CASCADE). */
  @Modifying
  @Query("DELETE FROM LanzamientoBalanceo l WHERE l.archivoId = :archivoId")
  void borrarTodoPorArchivo(@Param("archivoId") Long archivoId);
}
