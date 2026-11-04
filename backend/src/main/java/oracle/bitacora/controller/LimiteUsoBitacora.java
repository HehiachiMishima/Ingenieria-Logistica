package oracle.bitacora.controller;

import oracle.common.security.LimiteSolicitudes;
import oracle.common.security.LimiteUsoPanel;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Límite de uso del panel Bitácora por usuario y minuto (429 + Retry-After al
 * pasarse). La exportación recorre todo el filtro y la analítica lanza 6
 * agregaciones, por eso tienen cupos propios más bajos que los listados.
 */
@Configuration
public class LimiteUsoBitacora implements WebMvcConfigurer {

    static final LimiteUsoPanel.Cupos CUPOS = new LimiteUsoPanel.Cupos(
            300, // lecturas: listados paginados, búsqueda y catálogos
            60, // escrituras: altas, ediciones y bajas
            60, // analítica del dashboard (cacheada por filtro)
            10); // exportaciones completas

    private final LimiteSolicitudes limite;

    public LimiteUsoBitacora(LimiteSolicitudes limite) {
        this.limite = limite;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LimiteUsoPanel(limite, "bitacora", CUPOS))
                .addPathPatterns("/api/incidencias/**", "/api/centros/**", "/api/sistemas/**", "/api/areas/**");
    }
}
