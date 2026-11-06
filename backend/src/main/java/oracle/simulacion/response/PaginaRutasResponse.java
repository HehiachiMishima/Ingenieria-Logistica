package oracle.simulacion.response;

import java.util.List;

/**
 * Página de rutas por keyset: {@code siguiente} es el cursor (id de la última ruta) para pedir la
 * próxima, o null si no quedan; {@code total} solo viene en la primera página.
 */
public record PaginaRutasResponse(List<RutaSimulacionResponse> contenido, Long total, Long siguiente) {}
