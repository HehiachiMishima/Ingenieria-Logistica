package oracle.common.security;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import oracle.common.exception.ApiError;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Devuelve 401 en JSON (en lugar del redirect a login por defecto). Si no se
 * pudo comprobar el token porque la base no respondio, es 503 con Retry-After:
 * un 401 haria que el navegador cerrara una sesion que sigue siendo valida.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError error;
        if (authException instanceof AuthenticationServiceException) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "2");
            error = ApiError.de(503, "Service Unavailable",
                    "El servicio esta ocupado. Intente de nuevo en unos segundos.", request.getRequestURI());
        } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            error = ApiError.de(401, "No autorizado", "Autenticacion requerida o token invalido.", request.getRequestURI());
        }
        objectMapper.writeValue(response.getOutputStream(), error);
    }
}
