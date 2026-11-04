package oracle.malla.controller;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import oracle.common.exception.LimiteExcedidoException;
import oracle.common.security.LimiteSolicitudes;
import oracle.common.support.ActorActual;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Límite de uso del panel por usuario: guardar, importar o exportar la malla
 * (escrituras y Excel) se limitan más que las lecturas. Al pasarse responde
 * 429 con Retry-After.
 */
@Configuration
public class LimiteUsoMalla implements WebMvcConfigurer, HandlerInterceptor {

    private static final long VENTANA_MS = 60_000L;
    /** Escrituras y archivos Excel por usuario y minuto. */
    static final int MAX_ESCRITURAS = 30;
    /** Lecturas por usuario y minuto. */
    static final int MAX_LECTURAS = 240;

    private final LimiteSolicitudes limite;

    public LimiteUsoMalla(LimiteSolicitudes limite) {
        this.limite = limite;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/malla/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Las respuestas Mono vuelven a pasar por aquí al reanudarse: se cuentan una vez.
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        String ruta = request.getRequestURI();
        boolean pesada = !HttpMethod.GET.matches(request.getMethod())
                || ruta.endsWith("/excel") || ruta.endsWith("/plantilla");
        long espera = limite.consumir("malla:" + (pesada ? "escritura:" : "lectura:") + ActorActual.id(),
                pesada ? MAX_ESCRITURAS : MAX_LECTURAS, VENTANA_MS);
        if (espera == 0) {
            return true;
        }
        long segundos = Math.max(1, (espera + 999) / 1000);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(segundos));
        throw new LimiteExcedidoException("Demasiadas solicitudes seguidas. Intenta de nuevo en " + segundos + " s.");
    }
}
