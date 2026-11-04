package oracle.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Limpieza periodica de refresh tokens vencidos de todos los usuarios. Con miles
 * de usuarios la tabla acumula una fila por login y por renovacion: sin esta
 * purga, crece sin limite con las sesiones de quienes no vuelven a entrar.
 */
@Component
public class PurgaSesiones {

    private static final Logger log = LoggerFactory.getLogger(PurgaSesiones.class);
    private static final int LOTE = 5_000;
    private static final int MAX_LOTES = 200;

    private final SesionesRefresh sesiones;

    public PurgaSesiones(SesionesRefresh sesiones) {
        this.sesiones = sesiones;
    }

    /** Cada hora (primera pasada a los 5 minutos de arrancar). */
    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    public void purgar() {
        try {
            int borradas = sesiones.purgarVencidas(LOTE, MAX_LOTES);
            if (borradas > 0) {
                log.info("Sesiones vencidas purgadas: {}", borradas);
            }
        } catch (RuntimeException ex) {
            log.warn("No se pudo purgar sesiones vencidas: {}", ex.getMessage());
        }
    }
}
