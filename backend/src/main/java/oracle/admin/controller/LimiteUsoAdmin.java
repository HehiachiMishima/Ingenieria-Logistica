package oracle.admin.controller;

import oracle.common.security.LimiteSolicitudes;
import oracle.common.security.LimiteUsoPanel;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Límite de uso del panel Administración por usuario y minuto (429 +
 * Retry-After al pasarse). Cubre usuarios, roles, permisos, resolución y el
 * dashboard; /api/auth/** queda fuera (lo protege LoginRateLimiter).
 * /usuarios/resumen y /admin/analitica recorren las vistas de resolución de
 * todos los usuarios, por eso cuentan como analítica.
 */
@Configuration
public class LimiteUsoAdmin implements WebMvcConfigurer {

    static final LimiteUsoPanel.Cupos CUPOS = new LimiteUsoPanel.Cupos(
            300, // lecturas: listados y detalle de roles/permisos de un usuario
            60, // escrituras: CRUD y asignaciones
            30, // resumen de usuarios y dashboard
            10); // exportaciones (sin uso hoy)

    private final LimiteSolicitudes limite;

    public LimiteUsoAdmin(LimiteSolicitudes limite) {
        this.limite = limite;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LimiteUsoPanel(limite, "admin", CUPOS))
                .addPathPatterns("/api/admin/**", "/api/usuarios/**", "/api/roles/**", "/api/permisos/**",
                        "/api/resolucion/**");
    }
}
