package oracle.simulacion.repository;

import java.util.List;
import oracle.simulacion.model.ParametroCluster;
import oracle.simulacion.model.ParametroClusterId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParametroClusterRepository
    extends JpaRepository<ParametroCluster, ParametroClusterId> {

  List<ParametroCluster> findByIdArchivoId(Long archivoId);
}
