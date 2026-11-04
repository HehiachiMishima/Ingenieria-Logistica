package oracle.admin.repository;

import java.util.List;
import oracle.admin.model.Rol;
import oracle.admin.model.UsuarioRol;
import oracle.admin.model.UsuarioRol.UsuarioRolId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UsuarioRolRepository extends JpaRepository<UsuarioRol, UsuarioRolId> {

    boolean existsByUsuarioIdAndRolId(Long usuarioId, Long rolId);

    @Transactional
    @Modifying
    @Query("DELETE FROM UsuarioRol ur WHERE ur.usuario.id = :usuarioId AND ur.rol.id = :rolId")
    void eliminarPar(@Param("usuarioId") Long usuarioId, @Param("rolId") Long rolId);

    @Query("SELECT ur.rol FROM UsuarioRol ur WHERE ur.usuario.id = :usuarioId")
    List<Rol> rolesDeUsuario(@Param("usuarioId") Long usuarioId);
}
