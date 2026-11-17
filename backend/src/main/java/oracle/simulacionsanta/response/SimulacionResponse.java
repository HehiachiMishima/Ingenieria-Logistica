package oracle.simulacionsanta.response;

import java.util.List;

/** Ubicación y avance de las paletas de un registro en un envío. */
public record SimulacionResponse(
    Long registroId,
    /** Envío que se muestra (0 a 3). */
    int envio,
    /** El guardado en el registro. */
    int envioActual,
    KpisSimulacion kpis,
    /** Siempre los 3. */
    List<ClusterSimulacion> clusters,
    /** En el orden del Excel. */
    List<PaletaSimulacion> paletas) {}
