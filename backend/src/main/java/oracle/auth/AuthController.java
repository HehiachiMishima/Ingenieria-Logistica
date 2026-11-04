package oracle.auth;

import oracle.common.support.ActorActual;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Arrays;
import oracle.common.exception.NoAutorizadoException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import oracle.common.security.LoginRateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Acceso: login, renovacion y cierre (publicos: el access token puede estar
 * vencido) y consulta de la sesion vigente. El refresh token viaja solo en
 * una cookie HttpOnly; renovar y cerrar exigen ademas la cabecera
 * X-Requested-With, que otro sitio no puede enviar sin pasar por CORS.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final String CABECERA_AJAX = "X-Requested-With";

    private final AuthService authService;
    private final LoginRateLimiter rateLimiter;
    private final CookieRefresh cookie;

    public AuthController(AuthService authService, LoginRateLimiter rateLimiter, CookieRefresh cookie) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.cookie = cookie;
    }

    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    public Mono<ResponseEntity<ApiResponse<AuthResponse>>> login(
            @Valid @RequestBody LoginRequest peticion,
            HttpServletRequest http) {
        // Cubos por IP+usuario, por IP y por cuenta; el intento se cuenta ANTES
        // de verificar la contrasena (ver LoginRateLimiter).
        String ip = http.getRemoteAddr();
        LoginRateLimiter.Reserva reserva = rateLimiter.reservarLogin(ip, peticion.usuario());
        return authService.login(peticion, reserva, refreshDe(http), ip)
                .map(acceso -> conCookie(acceso, "Autenticacion exitosa."));
    }

    /**
     * Nuevo access token a cambio del refresh token de la cookie, que se
     * reemplaza (rotacion). 401 si la sesion vencio o se cerro: el navegador
     * pide entonces solo la contrasena.
     */
    @PostMapping("/refresh")
    @PreAuthorize("permitAll()")
    public Mono<ResponseEntity<ApiResponse<AuthResponse>>> renovar(HttpServletRequest http) {
        exigirAjax(http);
        String ip = http.getRemoteAddr();
        String claveIp = LoginRateLimiter.claveIp(ip);
        rateLimiter.verificarPorIp(ip);
        return authService.renovar(refreshDe(http), ip)
                .map(acceso -> conCookie(acceso, "Sesion renovada."))
                .doOnError(NoAutorizadoException.class, ex -> rateLimiter.registrarFallo(claveIp));
    }

    /** Cierra la sesion de este navegador: revoca su refresh token y borra la cookie. */
    @PostMapping("/logout")
    @PreAuthorize("permitAll()")
    public Mono<ResponseEntity<ApiResponse<Void>>> cerrar(HttpServletRequest http) {
        exigirAjax(http);
        return authService.cerrar(refreshDe(http))
                .then(Mono.fromSupplier(() -> ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, cookie.borrar())
                        .body(ApiResponse.<Void>ok("Sesion cerrada.", null))));
    }

    /** Sesion vigente (usuario + permisos actuales) del portador del token. */
    @GetMapping("/sesion")
    @PreAuthorize("isAuthenticated()")
    public Mono<ApiResponse<SesionResponse>> sesion() {
        return authService.sesionActual(ActorActual.id());
    }

    /** Cambio de la propia contrasena: exige la actual como confirmacion. */
    @PutMapping("/contrasena")
    @PreAuthorize("isAuthenticated()")
    public Mono<ApiResponse<Void>> cambiarContrasena(
            @Valid @RequestBody CambiarContrasenaRequest peticion, HttpServletRequest http) {
        return authService.cambiarContrasena(ActorActual.id(), peticion, refreshDe(http));
    }

    private ResponseEntity<ApiResponse<AuthResponse>> conCookie(AuthService.Acceso acceso, String mensaje) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.crear(acceso.refresh()))
                .body(ApiResponse.ok(mensaje, acceso.respuesta()));
    }

    private String refreshDe(HttpServletRequest http) {
        Cookie[] cookies = http.getCookies();
        return cookies == null ? null : Arrays.stream(cookies)
                .filter(c -> cookie.nombre().equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private static void exigirAjax(HttpServletRequest http) {
        if (http.getHeader(CABECERA_AJAX) == null) {
            throw new SolicitudInvalidaException("Falta la cabecera " + CABECERA_AJAX + ".");
        }
    }
}
