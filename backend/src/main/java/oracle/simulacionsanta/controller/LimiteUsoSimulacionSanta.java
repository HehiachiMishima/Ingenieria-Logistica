package oracle.simulacionsanta.controller;

import oracle.common.security.LimiteSolicitudes;
import oracle.common.security.LimiteUsoPanel;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Límite de uso del panel Simulación Santa Anita por usuario y minuto (429 + Retry-After al
 * pasarse). Toda petición que no es GET cuenta como escritura: subir o validar un Excel,
 * renombrar, eliminar, configurar, cambiar el envío y la vista previa de la simulación (que el
 * frontend pide al editar la configuración; por eso el cupo es mayor que el de Simulación). La
 * lectura de los archivos ya tiene su propio tope de cargas simultáneas.
 */
@Configuration
public class LimiteUsoSimulacionSanta implements WebMvcConfigurer {

  static final LimiteUsoPanel.Cupos CUPOS =
      new LimiteUsoPanel.Cupos(
          240, // lecturas: listado, opciones, configuración y simulación
          60, // escrituras, Excel y vistas previas
          30, // analítica (sin uso hoy)
          10); // exportaciones (sin uso hoy)

  private final LimiteSolicitudes limite;

  public LimiteUsoSimulacionSanta(LimiteSolicitudes limite) {
    this.limite = limite;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new LimiteUsoPanel(limite, "simulacion-santa", CUPOS))
        .addPathPatterns("/api/simulacion-santa/**");
  }
}
