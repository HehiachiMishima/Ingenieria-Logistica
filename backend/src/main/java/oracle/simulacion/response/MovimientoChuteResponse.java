package oracle.simulacion.response;

/** Rutas que el balanceo (o un movimiento manual) pasó de un chute a otro. */
public record MovimientoChuteResponse(Long origenId, Long destinoId, int rutas) {}
