package oracle.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import oracle.admin.model.Usuario;
import oracle.admin.repository.UsuarioRepository;
import oracle.common.config.Transacciones;
import oracle.common.exception.NoAutorizadoException;
import oracle.common.security.SesionesRefresh;
import oracle.common.security.SesionesRefresh.Fila;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Refresh tokens: el navegador renueva su access token sin pedir la
 * contraseña mientras la sesión esté activa. Cada token sirve una sola vez
 * (rotación); si llega uno ya usado, alguien lo copió y se cierra toda su
 * familia. La sesión vence por inactividad (cada renovación la extiende) y,
 * aunque haya actividad, al cumplir el máximo de la familia.
 */
@Slf4j
@Service
public class SesionRefreshService {

    static final String SESION_VENCIDA = "La sesion vencio. Ingresa tu contrasena para continuar.";

    private static final SecureRandom AZAR = new SecureRandom();

    /** Token entregado al navegador (va en la cookie) y hasta cuándo sirve. */
    public record Emitido(String token, Instant expiraEn) {
    }

    /** Renovación aceptada: de quién es la sesión y el token que reemplaza al usado. */
    public record Renovado(Long usuarioId, Emitido refresh) {
    }

    private enum Estado { VIGENTE, INVALIDO, REUTILIZADO }

    private record Resultado(Estado estado, Long usuarioId, long restanteS) {
    }

    private final SesionesRefresh sesiones;
    private final UsuarioRepository usuarios;
    private final Transacciones transacciones;
    private final long inactividadS;
    private final long maximoS;

    public SesionRefreshService(
            SesionesRefresh sesiones,
            UsuarioRepository usuarios,
            Transacciones transacciones,
            @Value("${app.auth.refresh.inactividad-s}") long inactividadS,
            @Value("${app.auth.refresh.maximo-s}") long maximoS) {
        this.sesiones = sesiones;
        this.usuarios = usuarios;
        this.transacciones = transacciones;
        this.inactividadS = inactividadS;
        this.maximoS = maximoS;
    }

    /** Abre una familia al iniciar sesión; la que traía el navegador (si traía) se cierra. */
    public Emitido abrir(Long usuarioId, String anterior, String ip) {
        String token = nuevoToken();
        transacciones.ejecutar(() -> {
            if (anterior != null && !anterior.isBlank()) {
                sesiones.familiaDe(hash(anterior)).ifPresent(sesiones::revocarFamilia);
            }
            sesiones.purgar(usuarioId);
            sesiones.abrir(usuarioId, UUID.randomUUID().toString(), hash(token), inactividadS, maximoS, ip);
            return null;
        });
        return new Emitido(token, Instant.now().plusSeconds(Math.min(inactividadS, maximoS)));
    }

    /**
     * Cambia el token por uno nuevo. La revocación por reutilización se
     * confirma antes de responder 401: por eso el resultado sale de la
     * transacción y la excepción se lanza fuera.
     */
    public Renovado renovar(String token, String ip) {
        if (token == null || token.isBlank()) {
            throw new NoAutorizadoException(SESION_VENCIDA);
        }
        String nuevo = nuevoToken();
        String hashNuevo = hash(nuevo);
        Resultado resultado = transacciones.ejecutar(() -> {
            Optional<Fila> encontrada = sesiones.bloquear(hash(token));
            if (encontrada.isEmpty()) {
                return new Resultado(Estado.INVALIDO, null, 0);
            }
            Fila fila = encontrada.get();
            if (fila.revocado()) {
                sesiones.revocarFamilia(fila.familia());
                return new Resultado(Estado.REUTILIZADO, fila.usuarioId(), 0);
            }
            if (fila.vencido()) {
                return new Resultado(Estado.INVALIDO, fila.usuarioId(), 0);
            }
            boolean activo = usuarios.findById(fila.usuarioId())
                    .map(Usuario::getActivo)
                    .map(Boolean.TRUE::equals)
                    .orElse(false);
            if (!activo) {
                sesiones.revocarFamilia(fila.familia());
                return new Resultado(Estado.INVALIDO, fila.usuarioId(), 0);
            }
            sesiones.rotar(fila, hashNuevo, inactividadS, ip);
            return new Resultado(Estado.VIGENTE, fila.usuarioId(), sesiones.segundosRestantes(hashNuevo));
        });
        if (resultado.estado() == Estado.REUTILIZADO) {
            log.warn("Refresh token reutilizado (usuarioId={}, ip={}): se cerraron las sesiones de esa familia.",
                    resultado.usuarioId(), ip);
        }
        if (resultado.estado() != Estado.VIGENTE) {
            throw new NoAutorizadoException(SESION_VENCIDA);
        }
        return new Renovado(resultado.usuarioId(), new Emitido(nuevo, Instant.now().plusSeconds(resultado.restanteS())));
    }

    /** Cierre de sesión: la familia del token deja de servir. */
    public void cerrar(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        transacciones.ejecutar(() -> {
            sesiones.familiaDe(hash(token)).ifPresent(sesiones::revocarFamilia);
            return null;
        });
    }

    /**
     * Tras cambiar la propia contraseña: se cierran las demás sesiones y se
     * conserva la de este navegador. Debe llamarse dentro de la transacción
     * del cambio.
     */
    public void cerrarOtras(Long usuarioId, String token) {
        Optional<String> propia = token == null || token.isBlank()
                ? Optional.empty()
                : sesiones.familiaDe(hash(token));
        propia.ifPresentOrElse(
                familia -> sesiones.revocarUsuarioExcepto(usuarioId, familia),
                () -> sesiones.revocarUsuario(usuarioId));
    }

    /** SHA-256 en hexadecimal: en la base nunca está el token. */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible.", ex);
        }
    }

    /** 256 bits aleatorios en base64url: imposible de adivinar. */
    private static String nuevoToken() {
        byte[] bytes = new byte[32];
        AZAR.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
