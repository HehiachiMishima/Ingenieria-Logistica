package oracle.simulacion.response;

import oracle.common.response.PaginaResponse;

/**
 * Detalle ejecutivo de rutas para el dashboard: totales de todo el conjunto
 * filtrado (no solo de la página visible) y la página solicitada.
 */
public record RutasDashboardResponse(
    ResumenRutasDashboardResponse resumen, PaginaResponse<RutaSimulacionResponse> rutas) {}
