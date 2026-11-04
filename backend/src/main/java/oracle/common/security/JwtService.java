package oracle.common.security;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Emite los JWT (HS256). El secreto vive en .env (JWT_SECRET) y se valida
 * su longitud al construir los beans en SecurityConfig. El token solo lleva
 * la identidad; los permisos se resuelven por peticion contra la vista final
 * para que un deny o una desactivacion tengan efecto inmediato.
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final String emisor;
    private final long ttlMs;

    public JwtService(
            JwtEncoder encoder,
            @Value("${app.jwt.issuer}") String emisor,
            @Value("${app.jwt.ttl-ms}") long ttlMs) {
        this.encoder = encoder;
        this.emisor = emisor;
        this.ttlMs = ttlMs;
    }

    /** Genera el token para el usuario (id y nombre de usuario) y devuelve tambien su expiracion. */
    public TokenGenerado generar(Long usuarioId, String usuario) {
        Instant ahora = Instant.now();
        Instant expira = ahora.plusMillis(ttlMs);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(emisor)
                .subject(String.valueOf(usuarioId))
                .claim("usuario", usuario)
                .issuedAt(ahora)
                .expiresAt(expira)
                .build();

        String token = encoder.encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new TokenGenerado(token, expira);
    }

    /** Token emitido y su instante de expiracion. */
    public record TokenGenerado(String valor, Instant expiraEn) {
    }
}
