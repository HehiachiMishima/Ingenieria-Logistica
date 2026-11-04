package oracle.admin.repository;

import java.util.List;
import java.util.Optional;
import oracle.admin.model.UsuarioPermiso;
import oracle.admin.model.UsuarioPermiso.UsuarioPermisoId;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UsuarioPermisoRepository extends JpaRepository<UsuarioPermiso, UsuarioPermisoId> {

    Optional<UsuarioPermiso> findByUsuarioIdAndPermisoId(Long usuarioId, Long permisoId);

    boolean existsByUsuarioIdAndPermisoId(Long usuarioId, Long permisoId);

    /** Overrides del usuario con usuario y permiso en el mismo SELECT (sin una consulta por fila). */
    @EntityGraph(attributePaths = {"usuario", "permiso"})
    List<UsuarioPermiso> findByUsuarioId(Long usuarioId);

    @Transactional
    @Modifying
    @Query("DELETE FROM UsuarioPermiso up WHERE up.usuario.id = :usuarioId AND up.permiso.id = :permisoId")
    void eliminarPar(@Param("usuarioId") Long usuarioId, @Param("permisoId") Long permisoId);
}
