package oracle.auth;

import oracle.common.security.AutorizacionCache;
import oracle.common.support.ActorActual;
import oracle.common.support.Auditoria;
import oracle.common.support.Trazas;
import oracle.common.config.Transacciones;

import java.util.List;
import java.util.Optional;
import oracle.common.exception.NoAutorizadoException;
import oracle.common.exception.OperacionNoPermitidaException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.security.JwtService;
import oracle.admin.model.Usuario;
import oracle.admin.repository.ResolucionRepository;
import oracle.admin.repository.UsuarioRepository;
import oracle.common.response.ApiResponse;
import oracle.admin.response.UsuarioResponse;
import oracle.common.security.LoginRateLimiter;
import oracle.common.support.PoliticaContrasena;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/**
 * Autenticacion: valida credenciales, respeta el kill switch y emite el JWT
 * (corto) junto con el refresh token que lo renueva sin pedir la contrasena.
 */
@Slf4j
@Service
public class AuthService {

    /** Respuesta para el cuerpo y refresh token para la cookie (nunca va en el cuerpo). */
    public record Acceso(AuthResponse respuesta, SesionRefreshService.Emitido refresh) {
    }

    private final UsuarioRepository usuarioRepository;
    private final ResolucionRepository resolucionRepository;
    private final JwtService jwtService;
    private final LoginRateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final AutorizacionCache autorizacionCache;
    private final Transacciones transacciones;
    private final SesionRefreshService refresh;

    /**
     * Hash descartable contra el que se compara cuando el usuario no existe,
     * para que el coste de BCrypt no delate la existencia de la cuenta.
     */
    private final String hashFicticio;

    public AuthService(
            UsuarioRepository usuarioRepository,
            ResolucionRepository resolucionRepository,
            JwtService jwtService,
            LoginRateLimiter rateLimiter,
            PasswordEncoder passwordEncoder,
            AutorizacionCache autorizacionCache,
            Transacciones transacciones,
            SesionRefreshService refresh) {
        this.autorizacionCache = autorizacionCache;
        this.usuarioRepository = usuarioRepository;
        this.resolucionRepository = resolucionRepository;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.passwordEncoder = passwordEncoder;
        this.transacciones = transacciones;
        this.refresh = refresh;
        this.hashFicticio = passwordEncoder.encode("oracle-hash-ficticio");
    }

    /**
     * Login (tambien el desbloqueo de la pantalla de sesion vencida). Abre una
     * familia de refresh tokens nueva y cierra la que traia el navegador.
     */
    public Acceso login(
            LoginRequest peticion, LoginRateLimiter.Reserva reserva, String refreshAnterior, String ip) {
        // Sin el usuario en la traza: un usuario mal tipeado puede ser una contrasena.
        Acceso acceso = Trazas.medir(log, "AuthService.login", () -> {
            Usuario usuario = autenticar(peticion);
            return acceso(usuario, refresh.abrir(usuario.getId(), refreshAnterior, ip));
        });
        rateLimiter.loginCorrecto(reserva);
        return acceso;
    }

    /** Renueva el access token con el refresh token de la cookie (que tambien se cambia). */
    public Acceso renovar(String token, String ip) {
        return Trazas.medir(log, "AuthService.renovar", () -> {
            SesionRefreshService.Renovado renovado = refresh.renovar(token, ip);
            Usuario usuario = usuarioRepository.findById(renovado.usuarioId())
                    .orElseThrow(() -> new NoAutorizadoException(SesionRefreshService.SESION_VENCIDA));
            return acceso(usuario, renovado.refresh());
        });
    }

    /** Cierre de sesion: el refresh token de este navegador deja de servir. */
    public void cerrar(String token) {
        refresh.cerrar(token);
    }

    /**
     * Sesion vigente del usuario autenticado. Usa la misma cache de autoridades
     * que el filtro JWT, asi que no agrega consultas en el caso comun y refleja
     * al instante los cambios de rol/permiso (que invalidan esa cache).
     */
    public ApiResponse<SesionResponse> sesionActual(Long usuarioId) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new NoAutorizadoException("La sesion ya no es valida."));
        if (!Boolean.TRUE.equals(usuario.getActivo())) {
            throw new NoAutorizadoException("La cuenta esta desactivada.");
        }
        List<String> permisos = autorizacionCache.cargar(usuarioId).autoridades().stream()
                .map(a -> a.getAuthority())
                .toList();
        return ApiResponse.ok("Sesion vigente.", new SesionResponse(UsuarioResponse.of(usuario), permisos));
    }

    /**
     * Cambio de la propia contrasena. Exige la contrasena actual como
     * confirmacion de identidad, valida que la nueva sea distinta y guarda el
     * nuevo hash. Cierra las sesiones de los demas navegadores (sus refresh
     * tokens) y conserva la de este. Una contrasena actual erronea es un 400,
     * no un 401: el 401 significa «sesion vencida» para el navegador.
     */
    public ApiResponse<Void> cambiarContrasena(
            Long usuarioId, CambiarContrasenaRequest peticion, String refreshToken) {
        Long actor = ActorActual.id();
        Trazas.medir(log,
                "AuthService.cambiarContrasena usuarioId=" + usuarioId,
                () -> transacciones.ejecutar(() -> {
                    Usuario usuario = usuarioRepository.findById(usuarioId)
                            .orElseThrow(() -> new NoAutorizadoException("La sesion ya no es valida."));
                    if (!Boolean.TRUE.equals(usuario.getActivo())) {
                        throw new NoAutorizadoException("La cuenta esta desactivada.");
                    }
                    // Cada intento cuenta: frena adivinar la clave con un token robado.
                    String intentos = rateLimiter.reservarCambioContrasena(usuarioId);
                    if (!passwordEncoder.matches(peticion.contrasenaActual(), usuario.getContrasena())) {
                        throw new SolicitudInvalidaException("La contrasena actual no es correcta.");
                    }
                    rateLimiter.limpiar(intentos);
                    PoliticaContrasena.validar(peticion.contrasenaNueva());
                    if (passwordEncoder.matches(peticion.contrasenaNueva(), usuario.getContrasena())) {
                        throw new OperacionNoPermitidaException(
                                "La contrasena nueva debe ser distinta a la actual.");
                    }
                    usuario.setContrasena(passwordEncoder.encode(peticion.contrasenaNueva()));
                    Auditoria.alActualizar(usuario, actor);
                    usuarioRepository.save(usuario);
                    refresh.cerrarOtras(usuarioId, refreshToken);
                    transacciones.alConfirmar(() -> autorizacionCache.invalidar(usuarioId));
                    return null;
                }));
        return ApiResponse.ok("Contrasena actualizada.", null);
    }

    private Usuario autenticar(LoginRequest peticion) {
        String credencial = peticion.usuario().trim();
        Optional<Usuario> encontrado = usuarioRepository.findByUsuarioOrEmail(credencial, credencial);

        // Se ejecuta BCrypt SIEMPRE (contra un hash ficticio si el usuario no
        // existe) para no filtrar por tiempo la existencia de una cuenta.
        String hash = encontrado.map(Usuario::getContrasena).orElse(hashFicticio);
        boolean coincide = passwordEncoder.matches(peticion.contrasena(), hash);
        if (encontrado.isEmpty() || !coincide) {
            throw new NoAutorizadoException("Usuario o contrasena incorrectos.");
        }

        Usuario usuario = encontrado.get();
        if (!Boolean.TRUE.equals(usuario.getActivo())) {
            throw new NoAutorizadoException("La cuenta esta desactivada.");
        }
        return usuario;
    }

    private Acceso acceso(Usuario usuario, SesionRefreshService.Emitido emitido) {
        List<String> permisos = resolucionRepository.nombresPermitidos(usuario.getId());
        JwtService.TokenGenerado token = jwtService.generar(usuario.getId(), usuario.getUsuario());
        AuthResponse respuesta = new AuthResponse(token.valor(), "Bearer", token.expiraEn(),
                emitido.expiraEn(), UsuarioResponse.of(usuario), permisos);
        return new Acceso(respuesta, emitido);
    }
}
