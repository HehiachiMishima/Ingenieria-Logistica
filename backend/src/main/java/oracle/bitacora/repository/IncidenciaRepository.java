package oracle.bitacora.repository;

import oracle.bitacora.model.Incidencia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IncidenciaRepository
        extends JpaRepository<Incidencia, Long>, JpaSpecificationExecutor<Incidencia> {
}
