package oracle.common.support;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Resuelve el id del usuario autenticado para auditoria
 * (creado_por / actualizado_por). Debe invocarse en el hilo de la peticion
 * (antes del subscribeOn), porque el SecurityContext es thread-local.
 * Si no hay autenticacion devuelve 0 (sistema/seeding), igual que el esquema.
 */
public final class ActorActual {

    public static final Long SISTEMA = 0L;

    public static Long id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            try {
                return Long.valueOf(jwt.getSubject());
            } catch (NumberFormatException ignored) {
                return SISTEMA;
            }
        }
        return SISTEMA;
    }

    private ActorActual() {
    }
}
