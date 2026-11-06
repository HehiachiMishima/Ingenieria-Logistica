package oracle.admin.service;

import oracle.common.support.ActorActual;
import oracle.common.support.Auditoria;
import oracle.common.support.Listas;
import oracle.common.support.PoliticaContrasena;
import oracle.common.support.SnapshotCache;
import oracle.common.support.Trazas;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import oracle.admin.repository.ResolucionRepository;
import oracle.admin.response.ActorResponse;
import oracle.admin.response.UsuarioResumenResponse;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.OperacionNoPermitidaException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.admin.model.Usuario;
import oracle.admin.repository.UsuarioRepository;
import oracle.admin.request.CambiarEstadoRequest;
import oracle.admin.request.UsuarioActualizarRequest;
import oracle.admin.request.UsuarioCrearRequest;
import oracle.common.response.ApiResponse;
import oracle.admin.response.UsuarioResponse;
import oracle.common.security.AutorizacionCache;
import oracle.common.security.SesionesRefresh;
import oracle.common.config.Transacciones;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/** CRUD de usuarios con filtros LIKE. */
@Slf4j
@Service
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final AutorizacionCache autorizacionCache;
    private final PasswordEncoder passwordEncoder;
    private final Transacciones transacciones;
    private final ResolucionRepository resolucionRepository;
    private final SesionesRefresh sesiones;
    private final ControlEscalamiento escalamiento;
    /**
     * Roles y permisos de todos los usuarios: su costo crece con usuarios x
     * permisos, asi que se arma una sola vez y se descarta con cada cambio
     * de RBAC (las mismas invalidaciones de AutorizacionCache).
     */
    private final SnapshotCache<List<UsuarioResumenResponse>> resumenes;

    public UsuarioService(
            UsuarioRepository usuarioRepository,
            AutorizacionCache autorizacionCache,
            PasswordEncoder passwordEncoder,
            Transacciones transacciones,
            ResolucionRepository resolucionRepository,
            SesionesRefresh sesiones,
            ControlEscalamiento escalamiento) {
        this.resolucionRepository = resolucionRepository;
        this.sesiones = sesiones;
        this.escalamiento = escalamiento;
        this.usuarioRepository = usuarioRepository;
        this.autorizacionCache = autorizacionCache;
        this.passwordEncoder = passwordEncoder;
        this.transacciones = transacciones;
        this.resumenes = new SnapshotCache<>("resumen-usuarios",
                () -> transacciones.consultar(this::armarResumen));
        autorizacionCache.alInvalidar(resumenes::invalidar);
    }

    /** Tope de ids por petición: una página de auditoría trae pocos actores distintos. */
    private static final int MAX_ACTORES = 200;

    /**
     * Login de los usuarios indicados, para mostrar quién creó o modificó un
     * registro. Solo id y nombre de usuario (nada sensible), así lo puede pedir
     * cualquier sesión aunque no tenga VER_USUARIOS. Los ids que no existen
     * (usuarios eliminados) simplemente no vuelven.
     */
    public ApiResponse<List<ActorResponse>> actores(List<Long> ids) {
        List<Long> unicos = ids == null ? List.of()
                : ids.stream().filter((id) -> id != null && id > 0).distinct().toList();
        if (unicos.size() > MAX_ACTORES) {
            throw new SolicitudInvalidaException(
                    "Se pueden consultar como máximo " + MAX_ACTORES + " usuarios por petición.");
        }
        if (unicos.isEmpty()) {
            return ApiResponse.ok("Actores resueltos.", List.of());
        }
        List<ActorResponse> lista = transacciones.consultar(() -> usuarioRepository.findByIdIn(unicos).stream()
                .map((a) -> new ActorResponse(a.getId(), a.getUsuario()))
                .toList());
        return ApiResponse.ok("Actores resueltos.", lista);
    }

    public ApiResponse<List<UsuarioResponse>> listar() {
        List<UsuarioResponse> datos = Trazas.medir(log, "UsuarioService.listar", () -> transacciones.consultar(() -> {
            List<UsuarioResponse> lista = usuarioRepository.findAll(Listas.limitado()).stream()
                    .map(UsuarioResponse::of).toList();
            Listas.avisarSiCorto(log, lista.size(), "UsuarioService.listar");
            return lista;
        }));
        return ApiResponse.ok("Usuarios listados.", datos);
    }

    /**
     * Roles y permisos efectivos de todos los usuarios (2 consultas en total,
     * en la misma transaccion de solo lectura para que ambas vean el mismo estado).
     */
    public ApiResponse<List<UsuarioResumenResponse>> resumen() {
        List<UsuarioResumenResponse> datos = Trazas.medir(log, "UsuarioService.resumen", resumenes::obtener);
        return ApiResponse.ok("Resumen de usuarios.", datos);
    }

    private List<UsuarioResumenResponse> armarResumen() {
        Map<Long, List<String>> roles = resolucionRepository.rolesPorUsuario();
        Map<Long, List<String>> permisos = resolucionRepository.permitidosPorUsuario();
        Set<Long> ids = new LinkedHashSet<>(roles.keySet());
        ids.addAll(permisos.keySet());
        return ids.stream()
                .map(id -> new UsuarioResumenResponse(id,
                        roles.getOrDefault(id, List.of()),
                        permisos.getOrDefault(id, List.of())))
                .toList();
    }

    public ApiResponse<UsuarioResponse> crear(UsuarioCrearRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "UsuarioService.crear usuario=" + peticion.usuario(), () -> transacciones.ejecutar(() -> {
            if (usuarioRepository.existsByUsuario(peticion.usuario())) {
                throw new ConflictoException("Ya existe un usuario con el nombre '" + peticion.usuario() + "'.");
            }
            if (usuarioRepository.existsByEmail(peticion.email())) {
                throw new ConflictoException("Ya existe un usuario con el email '" + peticion.email() + "'.");
            }

            Usuario usuario = new Usuario();
            usuario.setUsuario(peticion.usuario().trim());
            PoliticaContrasena.validar(peticion.contrasena());
            usuario.setContrasena(passwordEncoder.encode(peticion.contrasena()));
            usuario.setNombre(peticion.nombre().trim());
            usuario.setApellido(peticion.apellido().trim());
            usuario.setEmail(peticion.email().trim());
            usuario.setTelefono(peticion.telefono());
            usuario.setActivo(peticion.activo() == null ? Boolean.TRUE : peticion.activo());
            Auditoria.alCrear(usuario, actor);
            usuarioRepository.save(usuario);

            return ApiResponse.ok("Usuario creado.", UsuarioResponse.of(usuario));
        }));
    }

    public ApiResponse<UsuarioResponse> actualizar(Long id, UsuarioActualizarRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "UsuarioService.actualizar id=" + id, () -> transacciones.ejecutar(() -> {
            Usuario usuario = obtenerUsuario(id);
            verificarNoProtegido(usuario);
            escalamiento.verificarPuedeAdministrar(actor, id);

            if (peticion.usuario() != null && !peticion.usuario().trim().equals(usuario.getUsuario())) {
                if (usuarioRepository.existsByUsuarioAndIdNot(peticion.usuario().trim(), id)) {
                    throw new ConflictoException("Ya existe otro usuario con el nombre '" + peticion.usuario() + "'.");
                }
                usuario.setUsuario(peticion.usuario().trim());
            }
            if (peticion.email() != null && !peticion.email().trim().equals(usuario.getEmail())) {
                if (usuarioRepository.existsByEmailAndIdNot(peticion.email().trim(), id)) {
                    throw new ConflictoException("Ya existe otro usuario con el email '" + peticion.email() + "'.");
                }
                usuario.setEmail(peticion.email().trim());
            }
            boolean cerrarSesiones = false;
            if (peticion.contrasena() != null) {
                PoliticaContrasena.validar(peticion.contrasena());
                usuario.setContrasena(passwordEncoder.encode(peticion.contrasena()));
                cerrarSesiones = true;
            }
            if (peticion.nombre() != null) {
                usuario.setNombre(peticion.nombre().trim());
            }
            if (peticion.apellido() != null) {
                usuario.setApellido(peticion.apellido().trim());
            }
            if (peticion.telefono() != null) {
                usuario.setTelefono(peticion.telefono());
            }
            if (peticion.activo() != null) {
                usuario.setActivo(peticion.activo());
                cerrarSesiones |= !peticion.activo();
            }

            Auditoria.alActualizar(usuario, actor);
            usuarioRepository.save(usuario);
            // Con otra contrasena o desactivado, sus navegadores ya no renuevan la sesion.
            if (cerrarSesiones) {
                sesiones.revocarUsuario(id);
            }
            transacciones.alConfirmar(() -> autorizacionCache.invalidar(id));
            return ApiResponse.ok("Usuario actualizado.", UsuarioResponse.of(usuario));
        }));
    }

    public ApiResponse<Void> eliminar(Long id) {
        Long actor = ActorActual.id();
        Trazas.medir(log, "UsuarioService.eliminar id=" + id, () -> transacciones.ejecutar(() -> {
            Usuario usuario = obtenerUsuario(id);
            verificarNoProtegido(usuario);
            escalamiento.verificarPuedeAdministrar(actor, id);
            usuarioRepository.delete(usuario);
            transacciones.alConfirmar(() -> autorizacionCache.invalidar(id));
            return null;
        }));
        return ApiResponse.ok("Usuario eliminado.", null);
    }

    public ApiResponse<UsuarioResponse> cambiarActivo(Long id, CambiarEstadoRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "UsuarioService.cambiarActivo id=" + id, () -> transacciones.ejecutar(() -> {
            Usuario usuario = obtenerUsuario(id);
            verificarNoProtegido(usuario);
            escalamiento.verificarPuedeAdministrar(actor, id);
            usuario.setActivo(peticion.activo());
            Auditoria.alActualizar(usuario, actor);
            usuarioRepository.save(usuario);
            if (!peticion.activo()) {
                sesiones.revocarUsuario(id);
            }
            transacciones.alConfirmar(() -> autorizacionCache.invalidar(id));

            String mensaje = peticion.activo() ? "Usuario activado." : "Usuario desactivado.";
            return ApiResponse.ok(mensaje, UsuarioResponse.of(usuario));
        }));
    }

    private Usuario obtenerUsuario(Long id) {
        return usuarioRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el usuario con id " + id + "."));
    }

    private void verificarNoProtegido(Usuario usuario) {
        if (Protecciones.esUsuarioProtegido(usuario.getUsuario())) {
            throw new OperacionNoPermitidaException(
                    "El usuario '" + usuario.getUsuario() + "' esta protegido: no se puede modificar ni eliminar.");
        }
    }
}
