package oracle.admin.repository;

import java.util.Optional;
import oracle.admin.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    /** Login: busca por nombre de usuario o por email. */
    Optional<Usuario> findByUsuarioOrEmail(String usuario, String email);

    boolean existsByUsuario(String usuario);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, Long id);

    boolean existsByUsuarioAndIdNot(String usuario, Long id);
}
