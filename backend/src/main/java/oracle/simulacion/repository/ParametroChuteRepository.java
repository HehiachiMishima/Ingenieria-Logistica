package oracle.simulacion.repository;

import java.util.List;
import oracle.simulacion.model.ParametroChute;
import oracle.simulacion.model.ParametroChuteId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParametroChuteRepository extends JpaRepository<ParametroChute, ParametroChuteId> {

  List<ParametroChute> findByIdArchivoId(Long archivoId);
}
