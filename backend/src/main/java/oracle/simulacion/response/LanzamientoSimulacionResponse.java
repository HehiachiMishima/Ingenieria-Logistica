package oracle.simulacion.response;

import java.time.Instant;

/** Un lanzamiento de un cluster con el resultado que vio el usuario y sus totales. */
public record LanzamientoSimulacionResponse(
    Long id,
    Long clusterId,
    String cluster,
    /** RUTAS u OPERARIOS. */
    String fase,
    String mensaje,
    Instant creadoEn,
    Long creadoPor,
    int rutas,
    int separaciones,
    int chutesOperarios) {}
