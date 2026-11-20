package oracle.admin.service;

import oracle.common.support.ActorActual;
import oracle.common.support.Trazas;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import oracle.common.exception.OperacionNoPermitidaException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.admin.model.Permiso;
import oracle.admin.model.Usuario;
import oracle.admin.model.UsuarioPermiso;
import oracle.admin.repository.PermisoRepository;
import oracle.admin.repository.UsuarioPermisoLoteRepository;
import oracle.admin.repository.UsuarioPermisoRepository;
import oracle.admin.repository.UsuarioRepository;
import oracle.admin.request.UsuarioPermisoOverrideRequest;
import oracle.admin.request.UsuarioPermisosLoteRequest;
import oracle.common.response.ApiResponse;
import oracle.admin.response.PermisosUsuarioTableroResponse;
import oracle.admin.response.UsuarioPermisoResponse;
import oracle.admin.response.UsuarioPermisosLoteResponse;
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
    private final UsuarioPermisoLoteRepository lote;

    public UsuarioPermisoService(
            UsuarioRepository usuarioRepository,
            PermisoRepository permisoRepository,
            UsuarioPermisoRepository usuarioPermisoRepository,
            AutorizacionCache autorizacionCache,
            Transacciones transacciones,
            ControlEscalamiento escalamiento,
            UsuarioPermisoLoteRepository lote) {
        this.usuarioRepository = usuarioRepository;
        this.permisoRepository = permisoRepository;
        this.usuarioPermisoRepository = usuarioPermisoRepository;
        this.autorizacionCache = autorizacionCache;
        this.transacciones = transacciones;
        this.escalamiento = escalamiento;
        this.lote = lote;
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

    /**
     * Catálogo visto desde el usuario: lo que dan sus roles, sus excepciones y si quien edita
     * puede conceder cada permiso. {@code bloqueo} adelanta por qué no podría cambiarlas (las
     * mismas reglas que al guardar), para mostrarlo antes de intentarlo.
     */
    public ApiResponse<PermisosUsuarioTableroResponse> tablero(Long usuarioId) {
        Long actor = ActorActual.id();
        PermisosUsuarioTableroResponse datos = Trazas.medir(log,
                "UsuarioPermisoService.tablero usuarioId=" + usuarioId, () -> transacciones.consultar(() -> {
                    Usuario usuario = obtenerUsuario(usuarioId);
                    return new PermisosUsuarioTableroResponse(usuarioId, motivoBloqueo(usuario, actor),
                            lote.tablero(usuarioId, actor));
                }));
        return ApiResponse.ok("Tablero de permisos individuales.", datos);
    }

    /**
     * Concede, deniega o devuelve a la herencia del rol varios permisos del usuario en una sola
     * transacción: o se aplican todos o ninguno. Lo que ya estaba así se ignora, así un reintento
     * no falla. Mismas reglas que uno a uno: usuario no protegido, nadie se cambia a sí mismo, no
     * se administra a quien tiene más permisos y solo se concede lo que el actor tiene.
     */
    public ApiResponse<UsuarioPermisosLoteResponse> actualizarLote(Long usuarioId, UsuarioPermisosLoteRequest peticion) {
        Long actor = ActorActual.id();
        Set<Long> conceder = new LinkedHashSet<>(peticion.conceder());
        Set<Long> denegar = new LinkedHashSet<>(peticion.denegar());
        Set<Long> heredar = new LinkedHashSet<>(peticion.heredar());
        if (conceder.isEmpty() && denegar.isEmpty() && heredar.isEmpty()) {
            throw new SolicitudInvalidaException("Indique al menos un permiso para conceder, denegar o heredar.");
        }
        if (conceder.stream().anyMatch(id -> denegar.contains(id) || heredar.contains(id))
                || denegar.stream().anyMatch(heredar::contains)) {
            throw new SolicitudInvalidaException("Un mismo permiso no puede ir en dos listas a la vez.");
        }
        return Trazas.medir(log,
                "UsuarioPermisoService.actualizarLote usuarioId=" + usuarioId + " conceder=" + conceder.size()
                        + " denegar=" + denegar.size() + " heredar=" + heredar.size(),
                () -> transacciones.ejecutar(() -> {
                    Usuario usuario = obtenerUsuario(usuarioId);
                    verificarNoProtegido(usuario);
                    Protecciones.verificarNoAutoGestion(usuarioId, actor);
                    escalamiento.verificarPuedeAdministrar(actor, usuarioId);

                    Set<Long> pedidos = new LinkedHashSet<>(conceder);
                    pedidos.addAll(denegar);
                    pedidos.addAll(heredar);
                    Map<Long, String> nombres = lote.nombres(pedidos);
                    List<Long> inexistentes = pedidos.stream().filter(id -> !nombres.containsKey(id)).toList();
                    if (!inexistentes.isEmpty()) {
                        throw new RecursoNoEncontradoException("No existen los permisos con id "
                                + inexistentes.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ".");
                    }

                    Map<Long, Boolean> actuales = lote.excepciones(usuarioId);
                    List<Long> nuevosConcedidos = conceder.stream()
                            .filter(id -> !Boolean.TRUE.equals(actuales.get(id))).toList();
                    escalamiento.verificarPuedeOtorgar(actor, nuevosConcedidos.stream().map(nombres::get).toList());

                    int concedidos = lote.fijar(usuarioId, nuevosConcedidos, true, actor);
                    int denegados = lote.fijar(usuarioId, denegar, false, actor);
                    int heredados = lote.quitar(usuarioId, heredar);
                    if (concedidos + denegados + heredados > 0) {
                        transacciones.alConfirmar(() -> autorizacionCache.invalidar(usuarioId));
                    }
                    return ApiResponse.ok("Permisos individuales actualizados.", new UsuarioPermisosLoteResponse(
                            usuarioId, concedidos, denegados, heredados, lote.listaExcepciones(usuarioId)));
                }));
    }

    /** Por qué el actor no puede cambiar las excepciones de este usuario; null si puede. */
    private String motivoBloqueo(Usuario usuario, Long actor) {
        try {
            verificarNoProtegido(usuario);
            Protecciones.verificarNoAutoGestion(usuario.getId(), actor);
            escalamiento.verificarPuedeAdministrar(actor, usuario.getId());
            return null;
        } catch (OperacionNoPermitidaException e) {
            return e.getMessage();
        }
    }

    private void verificarNoProtegido(Usuario usuario) {
        if (Protecciones.esUsuarioProtegido(usuario.getUsuario())) {
            throw new OperacionNoPermitidaException("El usuario '" + usuario.getUsuario()
                    + "' es el usuario principal protegido: no se pueden conceder ni quitar permisos individuales.");
        }
    }

    private Usuario obtenerUsuario(Long id) {
        return usuarioRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el usuario con id " + id + "."));
    }
}
