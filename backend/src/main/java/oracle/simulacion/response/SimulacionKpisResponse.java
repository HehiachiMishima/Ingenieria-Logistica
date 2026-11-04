package oracle.simulacion.response;

import java.math.BigDecimal;

/**
 * Indicadores del comparativo: cierre (horas, suma de las olas) antes y
 * después de los ajustes (null = sin operarios) y rutas que hoy están en un
 * chute distinto al original.
 */
public record SimulacionKpisResponse(
    BigDecimal tiempoOriginal, BigDecimal tiempoBalanceado, int rutasMovidas) {}
