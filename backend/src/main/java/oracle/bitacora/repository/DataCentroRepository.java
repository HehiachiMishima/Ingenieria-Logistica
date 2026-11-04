package oracle.bitacora.repository;

import oracle.bitacora.model.DataCentro;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataCentroRepository extends JpaRepository<DataCentro, Long> {

    boolean existsByCentro(String centro);

    boolean existsByCentroAndIdNot(String centro, Long id);

    /** Registros en la tabla de bitácora que referencian este centro (FK RESTRICT). */
    @Query(value = "SELECT COUNT(*) FROM incidencias WHERE id_data_centro = :id", nativeQuery = true)
    long contarIncidencias(@Param("id") Long id);
}
