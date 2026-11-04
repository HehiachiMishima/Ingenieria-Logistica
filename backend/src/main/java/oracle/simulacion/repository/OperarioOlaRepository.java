package oracle.simulacion.repository;

import java.util.List;
import oracle.simulacion.model.OperarioOla;
import oracle.simulacion.model.OperarioOlaId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperarioOlaRepository extends JpaRepository<OperarioOla, OperarioOlaId> {

  List<OperarioOla> findByIdArchivoId(Long archivoId);
}
