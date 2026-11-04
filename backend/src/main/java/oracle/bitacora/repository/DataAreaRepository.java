package oracle.bitacora.repository;

import oracle.bitacora.model.DataArea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataAreaRepository extends JpaRepository<DataArea, Long> {

    boolean existsByArea(String area);

    boolean existsByAreaAndIdNot(String area, Long id);

    /** Registros en la tabla de bitácora que referencian esta área (FK RESTRICT). */
    @Query(value = "SELECT COUNT(*) FROM incidencias WHERE id_data_area = :id", nativeQuery = true)
    long contarIncidencias(@Param("id") Long id);
}
