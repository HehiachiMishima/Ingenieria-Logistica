package oracle.simulacion.response;

import java.math.BigDecimal;

/**
 * Un cluster en el orden de las olas, su avance (PENDIENTE, RUTAS, LISTO o
 * CERRADO) y su cuadro: rutas movidas, límite de horas, cierre conseguido con
 * los operarios de su ola (null si le falta gente) y su carga total.
 */
public record OlaSimulacionResponse(
    Long clusterId,
    String cluster,
    String estado,
    int rutasMovidas,
    BigDecimal limiteHoras,
    BigDecimal tiempoCierre,
    BigDecimal bultos,
    BigDecimal unidades,
    BigDecimal lineas,
    int cod) {}
