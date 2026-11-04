package oracle.bitacora.repository;

import java.util.List;
import oracle.bitacora.model.DataResponsable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataResponsableRepository extends JpaRepository<DataResponsable, Long> {

    boolean existsByResponsable(String responsable);

    boolean existsByResponsableAndIdNot(String responsable, Long id);

    List<DataResponsable> findByIdDataAreaOrderById(Long idDataArea);

    long countByIdDataArea(Long idDataArea);

    /** Registros de bitácora que referencian este responsable (FK RESTRICT). */
    @Query(value = "SELECT COUNT(*) FROM incidencias WHERE id_data_responsable = :id", nativeQuery = true)
    long contarIncidencias(@Param("id") Long id);
}
