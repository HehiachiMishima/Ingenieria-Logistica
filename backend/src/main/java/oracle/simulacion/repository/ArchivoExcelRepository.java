package oracle.simulacion.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import oracle.simulacion.model.ArchivoExcel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArchivoExcelRepository
    extends JpaRepository<ArchivoExcel, Long>, JpaSpecificationExecutor<ArchivoExcel> {

  /** Registros más recientes para los selectores (acotado, no carga la tabla entera). */
  List<ArchivoExcel> findTop200ByOrderByCreadoEnDescIdDesc();

  /**
   * Lee el registro con SELECT … FOR UPDATE. Toda escritura sobre un registro
   * (parámetros, mover, balancear, restablecer, eliminar) empieza aquí, de
   * modo que dos usuarios sobre el mismo registro se serializan en lugar de
   * pisarse las rutas y el historial.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM ArchivoExcel a WHERE a.id = :id")
  Optional<ArchivoExcel> bloquear(@Param("id") Long id);
}
