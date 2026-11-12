package oracle.simulacion.repository;

import java.util.List;
import oracle.simulacion.model.LanzamientoBalanceo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LanzamientoBalanceoRepository extends JpaRepository<LanzamientoBalanceo, Long> {

  /** Lanzamientos de un balanceo del registro, del más reciente al más antiguo. */
  List<LanzamientoBalanceo> findTop200ByArchivoIdAndBalanceoOrderByIdDesc(
      Long archivoId, String balanceo);

  /** El historial de cada lanzamiento se borra con él (ON DELETE CASCADE). */
  @Modifying
  @Query(
      "DELETE FROM LanzamientoBalanceo l"
          + " WHERE l.archivoId = :archivoId AND l.balanceo = :balanceo"
          + " AND l.clusterId = :clusterId")
  void borrarPorCluster(
      @Param("archivoId") Long archivoId,
      @Param("balanceo") String balanceo,
      @Param("clusterId") Long clusterId);

  @Modifying
  @Query("DELETE FROM LanzamientoBalanceo l WHERE l.archivoId = :archivoId AND l.balanceo = :balanceo")
  void borrarPorArchivo(@Param("archivoId") Long archivoId, @Param("balanceo") String balanceo);
}
