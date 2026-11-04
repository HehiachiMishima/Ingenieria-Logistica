package oracle.admin.repository;

import java.util.List;
import oracle.admin.model.Permiso;
import oracle.admin.model.Rol;
import oracle.admin.model.RolPermiso;
import oracle.admin.model.RolPermiso.RolPermisoId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RolPermisoRepository extends JpaRepository<RolPermiso, RolPermisoId> {

    boolean existsByRolIdAndPermisoId(Long rolId, Long permisoId);

    @Transactional
    @Modifying
    @Query("DELETE FROM RolPermiso rp WHERE rp.rol.id = :rolId AND rp.permiso.id = :permisoId")
    void eliminarPar(@Param("rolId") Long rolId, @Param("permisoId") Long permisoId);

    @Query("SELECT rp.permiso FROM RolPermiso rp WHERE rp.rol.id = :rolId")
    List<Permiso> permisosDeRol(@Param("rolId") Long rolId);

    @Query("SELECT rp.rol FROM RolPermiso rp WHERE rp.permiso.id = :permisoId")
    List<Rol> rolesConPermiso(@Param("permisoId") Long permisoId);
}
