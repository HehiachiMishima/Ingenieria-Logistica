package oracle.admin.service;

import oracle.common.support.ActorActual;
import oracle.common.support.Trazas;

import java.time.Instant;
import java.util.List;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.OperacionNoPermitidaException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.admin.model.Permiso;
import oracle.admin.model.Rol;
import oracle.admin.model.Usuario;
import oracle.admin.model.UsuarioRol;
import oracle.admin.repository.RolPermisoRepository;
import oracle.admin.repository.RolRepository;
import oracle.admin.repository.UsuarioRepository;
import oracle.admin.repository.UsuarioRolRepository;
import oracle.admin.request.UsuarioRolAsignarRequest;
import oracle.common.response.ApiResponse;
import oracle.admin.response.RolResponse;
import oracle.admin.response.UsuarioRolResponse;
import oracle.common.security.AutorizacionCache;
import oracle.common.config.Transacciones;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/** Asignacion usuario-rol (usuarios_roles): dar y quitar roles. */
@Slf4j
@Service
public class UsuarioRolService {

    private final UsuarioRepository usuarioRepository;
    private final RolRepository rolRepository;
    private final UsuarioRolRepository usuarioRolRepository;
    private final AutorizacionCache autorizacionCache;
    private final Transacciones transacciones;
    private final RolPermisoRepository rolPermisoRepository;
    private final ControlEscalamiento escalamiento;

    public UsuarioRolService(
            UsuarioRepository usuarioRepository,
            RolRepository rolRepository,
            UsuarioRolRepository usuarioRolRepository,
            AutorizacionCache autorizacionCache,
            Transacciones transacciones,
            ControlEscalamiento escalamiento,
            RolPermisoRepository rolPermisoRepository) {
        this.usuarioRepository = usuarioRepository;
        this.rolRepository = rolRepository;
        this.usuarioRolRepository = usuarioRolRepository;
        this.autorizacionCache = autorizacionCache;
        this.transacciones = transacciones;
        this.escalamiento = escalamiento;
        this.rolPermisoRepository = rolPermisoRepository;
    }

    /** Asignar un rol al usuario (unico por par). */
    public ApiResponse<UsuarioRolResponse> asignar(Long usuarioId, UsuarioRolAsignarRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log,
                "UsuarioRolService.asignar usuarioId=" + usuarioId + " rolId=" + peticion.rolId(),
                () -> transacciones.ejecutar(() -> {
                    Usuario usuario = obtenerUsuario(usuarioId);
                    verificarNoProtegido(usuario);
                    Protecciones.verificarNoAutoGestion(usuarioId, actor);
                    escalamiento.verificarPuedeAdministrar(actor, usuarioId);
                    Rol rol = rolRepository.findById(peticion.rolId())
                            .orElseThrow(() -> new RecursoNoEncontradoException(
                                    "No existe el rol con id " + peticion.rolId() + "."));
                    escalamiento.verificarPuedeOtorgar(actor, rolPermisoRepository.permisosDeRol(rol.getId())
                            .stream().map(Permiso::getNombre).toList());

                    if (usuarioRolRepository.existsByUsuarioIdAndRolId(usuarioId, peticion.rolId())) {
                        throw new ConflictoException("El usuario '" + usuario.getUsuario()
                                + "' ya tiene el rol '" + rol.getNombre() + "'.");
                    }

                    UsuarioRol asignacion = new UsuarioRol();
                    asignacion.setUsuario(usuario);
                    asignacion.setRol(rol);
                    asignacion.setCreadoPor(actor);
                    asignacion.setCreadoEn(Instant.now());
                    usuarioRolRepository.save(asignacion);
                    transacciones.alConfirmar(() -> autorizacionCache.invalidar(usuarioId));

                    return ApiResponse.ok("Rol asignado.", UsuarioRolResponse.of(asignacion));
                }));
    }

    /** Quitar un rol al usuario. */
    public ApiResponse<Void> quitar(Long usuarioId, Long rolId) {
        Long actor = ActorActual.id();
        Trazas.medir(log,
                "UsuarioRolService.quitar usuarioId=" + usuarioId + " rolId=" + rolId,
                () -> transacciones.ejecutar(() -> {
                    Usuario usuario = obtenerUsuario(usuarioId);
                    Protecciones.verificarNoAutoGestion(usuarioId, actor);
                    verificarNoProtegido(usuario);
                    escalamiento.verificarPuedeAdministrar(actor, usuarioId);
                    if (!usuarioRolRepository.existsByUsuarioIdAndRolId(usuarioId, rolId)) {
                        throw new RecursoNoEncontradoException("El usuario '" + usuario.getUsuario()
                                + "' no tiene el rol con id " + rolId + ".");
                    }
                    usuarioRolRepository.eliminarPar(usuarioId, rolId);
                    transacciones.alConfirmar(() -> autorizacionCache.invalidar(usuarioId));
                    return null;
                }));
        return ApiResponse.ok("Rol quitado.", null);
    }

    /** Roles del usuario. */
    public ApiResponse<List<RolResponse>> listarPorUsuario(Long usuarioId) {
        List<RolResponse> datos = Trazas.medir(log,
                "UsuarioRolService.listarPorUsuario usuarioId=" + usuarioId, () -> transacciones.consultar(() -> {
                    validarUsuario(usuarioId);
                    return usuarioRolRepository.rolesDeUsuario(usuarioId).stream()
                            .map(RolResponse::of)
                            .toList();
                }));
        return ApiResponse.ok("Roles del usuario.", datos);
    }

    private void validarUsuario(Long id) {
        if (!usuarioRepository.existsById(id)) {
            throw new RecursoNoEncontradoException("No existe el usuario con id " + id + ".");
        }
    }

    private Usuario obtenerUsuario(Long id) {
        return usuarioRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el usuario con id " + id + "."));
    }

    private void verificarNoProtegido(Usuario usuario) {
        if (Protecciones.esUsuarioProtegido(usuario.getUsuario())) {
            throw new OperacionNoPermitidaException(
                    "El usuario '" + usuario.getUsuario() + "' esta protegido: no se puede modificar su rol.");
        }
    }
}
