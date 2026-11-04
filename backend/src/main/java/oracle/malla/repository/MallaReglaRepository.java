package oracle.malla.repository;

import java.util.List;
import oracle.malla.model.MallaRegla;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MallaReglaRepository extends JpaRepository<MallaRegla, Long> {

    List<MallaRegla> findAllByOrderByOrdenAscIdAsc();
}
