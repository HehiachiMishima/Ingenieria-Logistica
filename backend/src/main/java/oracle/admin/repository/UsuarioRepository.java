package oracle.admin.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import oracle.admin.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    /** Solo id y login: resuelve los actores de la auditoría sin cargar la entidad. */
    interface Actor {
        Long getId();

        String getUsuario();
    }

    List<Actor> findByIdIn(Collection<Long> ids);

    /** Login: busca por nombre de usuario o por email. */
    Optional<Usuario> findByUsuarioOrEmail(String usuario, String email);

    boolean existsByUsuario(String usuario);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, Long id);

    boolean existsByUsuarioAndIdNot(String usuario, Long id);
}
