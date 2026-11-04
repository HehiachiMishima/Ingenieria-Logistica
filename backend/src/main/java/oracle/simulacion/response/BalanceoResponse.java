package oracle.simulacion.response;

/**
 * Resultado de lanzar un cluster: qué paso se ejecutó, en qué estado quedó
 * el cluster, qué cambió y la configuración ya actualizada (la pantalla no
 * necesita pedirla de nuevo).
 */
public record BalanceoResponse(
    Long clusterId,
    String cluster,
    /** RUTAS u OPERARIOS. */
    String fase,
    /** Estado del cluster tras el paso (RUTAS o LISTO). */
    String estado,
    int rutasMovidas,
    /** Operarios asignados en la ola tras el paso 2. */
    int operariosAsignados,
    String mensaje,
    ConfiguracionSimulacionResponse configuracion) {}
