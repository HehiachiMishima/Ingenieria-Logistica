package oracle.simulacion.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Parámetros del registro y su carga agregada por (cluster, chute). Las rutas
 * se consultan aparte y paginadas (GET …/rutas): un registro puede tener
 * decenas de miles.
 */
public record ConfiguracionSimulacionResponse(
    ArchivoSimulacionResponse archivo,
    BigDecimal velocidadBase,
    Integer operariosMaximos,
    /** Máximo por chute definido por el usuario; nulo = reparto parejo. */
    Integer operariosPorChute,
    /** Tope por chute que aplica el balanceo: el definido o, si no hay, el equipo completo. */
    int topePorChute,
    List<ClusterParametroResponse> clusters,
    List<ChuteParametroResponse> chutes,
    List<CargaSimulacionResponse> carga,
    /** Chutes que hoy mezclan CL1 y CL2: el balanceo los separa. */
    List<ChuteMezcladoResponse> mezclas) {}
