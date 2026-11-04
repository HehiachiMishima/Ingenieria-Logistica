package oracle.simulacion.response;

import java.util.List;

/**
 * Chute que mezcla clusters que no pueden compartir espacio (hoy: CL1 con
 * CL2). Se informa como advertencia; la carga no se rechaza.
 */
public record ChuteMezcladoResponse(Long id, String chute, List<String> clusters) {}
