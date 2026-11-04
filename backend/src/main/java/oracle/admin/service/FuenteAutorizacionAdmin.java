package oracle.admin.service;

import java.util.Optional;
import oracle.admin.repository.ResolucionRepository;
import oracle.admin.repository.UsuarioRepository;
import oracle.common.security.FuenteAutorizacion;
import org.springframework.stereotype.Component;

/**
 * Implementa el puerto de seguridad con las tablas de admin: la cuenta en
 * usuarios y los permisos permitidos de la vista v_permisos_finales.
 */
@Component
public class FuenteAutorizacionAdmin implements FuenteAutorizacion {

    private final UsuarioRepository usuarioRepository;
    private final ResolucionRepository resolucionRepository;

    public FuenteAutorizacionAdmin(UsuarioRepository usuarioRepository, ResolucionRepository resolucionRepository) {
        this.usuarioRepository = usuarioRepository;
        this.resolucionRepository = resolucionRepository;
    }

    @Override
    public Optional<Identidad> buscar(Long usuarioId) {
        return usuarioRepository.findById(usuarioId)
                .map(usuario -> new Identidad(
                        Boolean.TRUE.equals(usuario.getActivo()),
                        usuario.getUsuario(),
                        resolucionRepository.nombresPermitidos(usuarioId),
                        usuario.getCredencialesDesde()));
    }
}
