package oracle.simulacion.response;

import java.util.List;

/**
 * Lanzamientos del registro, del más reciente al más antiguo, y cuántos
 * cambios no tienen lanzamiento (a mano o de versiones anteriores). Los
 * cambios de cada uno se piden paginados.
 */
public record HistorialRegistroResponse(
    List<LanzamientoSimulacionResponse> lanzamientos, long sinLanzamiento) {}
