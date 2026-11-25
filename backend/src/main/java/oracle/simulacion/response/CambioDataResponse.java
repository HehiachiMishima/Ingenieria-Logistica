package oracle.simulacion.response;

/** Lo que se hizo en DATA o en los clusters, para avisarlo tal cual (p. ej. «2 rutas → CL1 · CHUTE 4.»). */
public record CambioDataResponse(String mensaje) {}
