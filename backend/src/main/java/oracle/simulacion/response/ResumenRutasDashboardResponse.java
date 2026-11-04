package oracle.simulacion.response;

import java.math.BigDecimal;

/** Totales de un lado del chute (antes o después) con los filtros del modal. */
public record ResumenRutasDashboardResponse(
    long rutas,
    long rutasB2c,
    long rutasB2b,
    /** Rutas que cambiaron de chute: salieron (antes) o llegaron (después). */
    long cambios,
    BigDecimal bultos,
    BigDecimal unidades,
    BigDecimal lineas,
    long cod) {}
