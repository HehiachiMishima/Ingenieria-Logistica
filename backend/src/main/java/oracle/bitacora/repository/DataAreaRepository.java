package oracle.bitacora.repository;

import oracle.bitacora.model.DataArea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataAreaRepository extends JpaRepository<DataArea, Long> {

    boolean existsByArea(String area);

    boolean existsByAreaAndIdNot(String area, Long id);

    /** Registros en la tabla de bitácora que referencian esta área (FK RESTRICT). */
    // Con tope (ConteoEnUso.TOPE): basta saber si esta en uso, no se cuentan millones de filas.
    @Query(value = "SELECT COUNT(*) FROM (SELECT 1 FROM incidencias WHERE id_data_area = :id LIMIT "
            + ConteoEnUso.TOPE + ") x", nativeQuery = true)
    long contarIncidencias(@Param("id") Long id);
}
