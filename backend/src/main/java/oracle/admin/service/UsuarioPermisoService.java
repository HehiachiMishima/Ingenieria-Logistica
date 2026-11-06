package oracle.admin.service;

import oracle.common.support.ActorActual;
import oracle.common.support.Trazas;

import java.time.Instant;
import java.util.List;
import oracle.common.exception.OperacionNoPermitidaException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.admin.model.Permiso;
import oracle.admin.model.Usuario;
import oracle.admin.model.UsuarioPermiso;
import oracle.admin.repository.PermisoRepository;
import oracle.admin.repository.UsuarioPermisoRepository;
import oracle.admin.repository.UsuarioRepository;
import oracle.admin.request.UsuarioPermisoOverrideRequest;
import oracle.common.response.ApiResponse;
import oracle.admin.response.UsuarioPermisoResponse;
import oracle.common.security.AutorizacionCache;
import oracle.common.config.Transacciones;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/**
 * Overrides individuales (usuarios_permisos): otorgado=true es un grant y
 * false un deny. Con MIN(otorgado) en la vista, el deny siempre gana.
 */
@Slf4j
@Service
public class UsuarioPermisoService {

    private final UsuarioRepository usuarioRepository;
    private final PermisoRepository permisoRepository;
    private final UsuarioPermisoRepository usuarioPermisoRepository;
    private final AutorizacionCache autorizacionCache;
    private final Transacciones transacciones;
    private final ControlEscalamiento escalamiento;

    public UsuarioPermisoService(
            UsuarioRepository usuarioRepository,
            PermisoRepository permisoRepository,
            UsuarioPermisoRepository usuarioPermisoRepository,
            AutorizacionCache autorizacionCache,
            Transacciones transacciones,
            ControlEscalamiento escalamiento) {
        this.usuarioRepository = usuarioRepository;
        this.permisoRepository = permisoRepository;
        this.usuarioPermisoRepository = usuarioPermisoRepository;
        this.autorizacionCache = autorizacionCache;
        this.transacciones = transacciones;
        this.escalamiento = escalamiento;
    }

    /** Crear o actualizar el override del permiso para el usuario. */
    public ApiResponse<UsuarioPermisoResponse> otorgar(Long usuarioId, UsuarioPermisoOverrideRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log,
                "UsuarioPermisoService.otorgar usuarioId=" + usuarioId + " permisoId=" + peticion.permisoId(),
                () -> transacciones.ejecutar(() -> {
                    Usuario usuario = obtenerUsuario(usuarioId);
                    if (Protecciones.esUsuarioProtegido(usuario.getUsuario())) {
                        throw new OperacionNoPermitidaException(
                                "El usuario '" + usuario.getUsuario() + "' es el usuario principal protegido: no se pueden conceder ni quitar permisos individuales.");
                    }
                    Protecciones.verificarNoAutoGestion(usuarioId, actor);
                    escalamiento.verificarPuedeAdministrar(actor, usuarioId);
                    Permiso permiso = permisoRepository.findById(peticion.permisoId())
                            .orElseThrow(() -> new RecursoNoEncontradoException(
                                    "No existe el permiso con id " + peticion.permisoId() + "."));
                    boolean otorgado = peticion.otorgado();
                    if (otorgado) {
                        escalamiento.verificarPuedeOtorgar(actor, List.of(permiso.getNombre()));
                    }
                    UsuarioPermiso override = usuarioPermisoRepository
                            .findByUsuarioIdAndPermisoId(usuarioId, peticion.permisoId())
                            .orElse(null);

                    String mensaje;
                    if (override == null) {
                        override = new UsuarioPermiso();
                        override.setUsuario(usuario);
                        override.setPermiso(permiso);
                        override.setOtorgado(otorgado);
                        override.setCreadoPor(actor);
                        override.setCreadoEn(Instant.now());
                        override.setActualizadoPor(actor);
                        override.setActualizadoEn(Instant.now());
                        mensaje = otorgado ? "Permiso otorgado al usuario." : "Permiso denegado al usuario.";
                    } else {
                        override.setOtorgado(otorgado);
                        override.setActualizadoPor(actor);
                        override.setActualizadoEn(Instant.now());
                        mensaje = otorgado
                                ? "Override actualizado a grant (otorgado=TRUE)."
                                : "Override actualizado a deny (otorgado=FALSE).";
                    }
                    usuarioPermisoRepository.save(override);
                    transacciones.alConfirmar(() -> autorizacionCache.invalidar(usuarioId));

                    return ApiResponse.ok(mensaje, UsuarioPermisoResponse.of(override));
                }));
    }

    /** Eliminar el override y volver al calculo por rol. */
    public ApiResponse<Void> eliminar(Long usuarioId, Long permisoId) {
        Long actor = ActorActual.id();
        Trazas.medir(log,
                "UsuarioPermisoService.eliminar usuarioId=" + usuarioId + " permisoId=" + permisoId,
                () -> transacciones.ejecutar(() -> {
                    Usuario usuario = obtenerUsuario(usuarioId);
                    if (Protecciones.esUsuarioProtegido(usuario.getUsuario())) {
                        throw new OperacionNoPermitidaException(
                                "El usuario '" + usuario.getUsuario() + "' es el usuario principal protegido: no se pueden conceder ni quitar permisos individuales.");
                    }
                    Protecciones.verificarNoAutoGestion(usuarioId, actor);
                    escalamiento.verificarPuedeAdministrar(actor, usuarioId);
                    if (!usuarioPermisoRepository.existsByUsuarioIdAndPermisoId(usuarioId, permisoId)) {
                        throw new RecursoNoEncontradoException(
                                "El usuario no tiene un override para el permiso con id " + permisoId + ".");
                    }
                    usuarioPermisoRepository.eliminarPar(usuarioId, permisoId);
                    transacciones.alConfirmar(() -> autorizacionCache.invalidar(usuarioId));
                    return null;
                }));
        return ApiResponse.ok("Override eliminado.", null);
    }

    /** Overrides del usuario (grant y deny). */
    public ApiResponse<List<UsuarioPermisoResponse>> listarPorUsuario(Long usuarioId) {
        List<UsuarioPermisoResponse> datos = Trazas.medir(log,
                "UsuarioPermisoService.listarPorUsuario usuarioId=" + usuarioId, () -> transacciones.consultar(() -> {
                    obtenerUsuario(usuarioId);
                    return usuarioPermisoRepository.findByUsuarioId(usuarioId).stream()
                            .map(UsuarioPermisoResponse::of)
                            .toList();
                }));
        return ApiResponse.ok("Overrides del usuario.", datos);
    }

    private Usuario obtenerUsuario(Long id) {
        return usuarioRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el usuario con id " + id + "."));
    }
}
