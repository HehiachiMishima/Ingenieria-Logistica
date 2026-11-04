package oracle.simulacion.response;

import java.math.BigDecimal;

/** Rutas que pasaron de un cluster (original) a otro (actual), con lo que se llevaron. */
public record CambioClusterResponse(
    Long origenId,
    String origen,
    Long destinoId,
    String destino,
    int rutas,
    BigDecimal bultos,
    BigDecimal unidades,
    BigDecimal lineas,
    int cod) {}
