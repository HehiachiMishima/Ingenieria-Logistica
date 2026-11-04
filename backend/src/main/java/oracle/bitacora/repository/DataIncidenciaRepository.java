package oracle.bitacora.repository;

import java.util.List;
import oracle.bitacora.model.DataIncidencia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataIncidenciaRepository extends JpaRepository<DataIncidencia, Long> {

    boolean existsByIdDataSistemaAndIncidencia(Long idDataSistema, String incidencia);

    boolean existsByIdDataSistemaAndIncidenciaAndIdNot(Long idDataSistema, String incidencia, Long id);

    List<DataIncidencia> findByIdDataSistemaOrderById(Long idDataSistema);

    long countByIdDataSistema(Long idDataSistema);

    /** Registros de bitácora que referencian este tipo de incidencia (FK RESTRICT). */
    @Query(value = "SELECT COUNT(*) FROM incidencias WHERE id_data_incidencia = :id", nativeQuery = true)
    long contarIncidencias(@Param("id") Long id);
}
