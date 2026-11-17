package oracle.simulacionsanta.response;

import oracle.simulacionsanta.model.Lado;
import oracle.simulacionsanta.model.TipoUbicacion;

/**
 * Hueco de una paleta. En una rampa, fila 1 es la más cercana a los pasillos y crece hacia la
 * puerta; en un pasillo, fila 1 es la más cercana al andén y 12 la más lejana.
 */
public record Ubicacion(TipoUbicacion tipo, int numero, Lado lado, int fila) {}
