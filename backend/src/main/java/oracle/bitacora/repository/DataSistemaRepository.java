package oracle.bitacora.repository;

import oracle.bitacora.model.DataSistema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataSistemaRepository extends JpaRepository<DataSistema, Long> {

    boolean existsBySistema(String sistema);

    boolean existsBySistemaAndIdNot(String sistema, Long id);

    /** Registros en la tabla de bitácora que referencian este sistema (FK RESTRICT). */
    // Con tope (ConteoEnUso.TOPE): basta saber si esta en uso, no se cuentan millones de filas.
    @Query(value = "SELECT COUNT(*) FROM (SELECT 1 FROM incidencias WHERE id_data_sistema = :id LIMIT "
            + ConteoEnUso.TOPE + ") x", nativeQuery = true)
    long contarIncidencias(@Param("id") Long id);
}
