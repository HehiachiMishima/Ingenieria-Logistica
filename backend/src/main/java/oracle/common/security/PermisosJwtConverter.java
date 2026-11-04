package oracle.common.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Convierte el JWT en un Authentication con las autoridades del usuario.
 *
 * Las autoridades se resuelven contra la vista final (deny-overrides) pero a
 * traves de AutorizacionCache, de modo que no se ejecutan 2 consultas SQL en
 * cada peticion. Un usuario inexistente o inactivo se rechaza con 401.
 */
@Component
public class PermisosJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AutorizacionCache autorizacionCache;

    public PermisosJwtConverter(AutorizacionCache autorizacionCache) {
        this.autorizacionCache = autorizacionCache;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long usuarioId = parsearId(jwt.getSubject());
        AutorizacionCache.Autorizacion autorizacion;
        try {
            autorizacion = autorizacionCache.cargar(usuarioId);
        } catch (DataAccessException ex) {
            // Base saturada o caida: no es un token invalido (ver RestAuthenticationEntryPoint).
            throw new AuthenticationServiceException("No se pudo verificar la sesion.", ex);
        }
        if (!autorizacion.activo()) {
            throw new BadCredentialsException("La cuenta esta desactivada.");
        }
        if (emitidoAntesDe(jwt.getIssuedAt(), autorizacion.credencialesDesde())) {
            throw new BadCredentialsException("La sesion ya no es valida.");
        }
        return new JwtAuthenticationToken(
                jwt, autorizacion.autoridades(), autorizacion.usuario());
    }

    /**
     * Un token emitido antes del ultimo cambio de contrasena ya no sirve, aunque
     * no haya vencido. iat tiene precision de segundos: se compara contra el
     * segundo del cambio.
     */
    static boolean emitidoAntesDe(Instant emitido, Instant credencialesDesde) {
        if (credencialesDesde == null) {
            return false;
        }
        return emitido == null || emitido.isBefore(credencialesDesde.truncatedTo(ChronoUnit.SECONDS));
    }

    private Long parsearId(String sub) {
        try {
            return Long.valueOf(sub);
        } catch (NumberFormatException ex) {
            throw new BadCredentialsException("Token invalido.");
        }
    }
}
