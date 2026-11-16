package oracle.bitacora.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import oracle.bitacora.model.Incidencia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidenciaRepository
        extends JpaRepository<Incidencia, Long>, JpaSpecificationExecutor<Incidencia> {

    /** SELECT ... FOR UPDATE por PK: serializa ediciones y cierres de la misma incidencia. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Incidencia i WHERE i.id = :id")
    Optional<Incidencia> bloquear(@Param("id") Long id);
}
