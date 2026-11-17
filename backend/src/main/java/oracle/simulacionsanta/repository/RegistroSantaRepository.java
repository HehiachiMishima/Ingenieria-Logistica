package oracle.simulacionsanta.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import oracle.simulacionsanta.model.RegistroSanta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegistroSantaRepository
    extends JpaRepository<RegistroSanta, Long>, JpaSpecificationExecutor<RegistroSanta> {

  /** Los 200 más recientes para los selectores (índice idx_registros_recientes). */
  List<RegistroSanta> findTop200ByOrderByCreadoEnDescIdDesc();

  /**
   * El registro con SELECT … FOR UPDATE: toda escritura sobre un registro (renombrar, configurar,
   * cambiar el envío, eliminar) empieza aquí, así dos usuarios sobre el mismo registro se
   * serializan en lugar de pisarse.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT r FROM RegistroSanta r WHERE r.id = :id")
  Optional<RegistroSanta> bloquear(@Param("id") Long id);
}
