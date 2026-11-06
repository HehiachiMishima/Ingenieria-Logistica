package oracle.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Traza cada peticion HTTP (metodo, ruta, estado y duracion en ms) e inyecta el
 * correlation-id en el MDC (y en la cabecera X-Request-Id) para poder cruzar
 * todas las lineas de log de una misma peticion.
 *
 * Todas las respuestas son sincronas (Spring MVC, un hilo virtual por peticion):
 * el registro se hace al terminar la cadena. No registra la query string para
 * no volcar PII.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RegistroPeticiones extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RegistroPeticiones.class);

    public static final String CABECERA_CORRELACION = "X-Request-Id";
    private static final java.util.regex.Pattern CORRELACION_VALIDA =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,64}");
    public static final String MDC_CORRELACION = "correlationId";
    private static final int MAX_LARGO_CORRELACION = 128;

    @Override
    protected void doFilterInternal(
            HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        long inicio = System.nanoTime();
        String correlacion = resolverCorrelacion(peticion);
        MDC.put(MDC_CORRELACION, correlacion);
        respuesta.setHeader(CABECERA_CORRELACION, correlacion);
        try {
            cadena.doFilter(peticion, respuesta);
        } finally {
            registrar(peticion, respuesta, inicio);
            MDC.remove(MDC_CORRELACION);
        }
    }

    private void registrar(HttpServletRequest peticion, HttpServletResponse respuesta, long inicio) {
        long ms = (System.nanoTime() - inicio) / 1_000_000L;
        log.info("{} {} -> {} ({} ms)", peticion.getMethod(), peticion.getRequestURI(), respuesta.getStatus(), ms);
    }

    private String resolverCorrelacion(HttpServletRequest peticion) {
        String entrante = peticion.getHeader(CABECERA_CORRELACION);
        // Solo un id con forma segura: el valor llega al MDC, a los logs y a la respuesta.
        if (entrante != null && entrante.length() <= MAX_LARGO_CORRELACION
                && CORRELACION_VALIDA.matcher(entrante).matches()) {
            return entrante;
        }
        return UUID.randomUUID().toString();
    }
}
