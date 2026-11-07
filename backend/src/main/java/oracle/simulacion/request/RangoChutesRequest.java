package oracle.simulacion.request;

import jakarta.validation.constraints.Positive;

/**
 * Chutes entre los que se reparte el paso 1 de un cluster: de «desde» a
 * «hasta», ambos incluidos, en el orden natural de los chutes (CHUTE 2 antes
 * que CHUTE 10). Si llegan invertidos se toman al revés. Los dos vacíos (el
 * cliente manda {@code {}} cuando no hay cuerpo) = sin rango; solo uno es un
 * error que valida el servicio. El paso 2 lo ignora.
 */
public record RangoChutesRequest(@Positive Long chuteDesdeId, @Positive Long chuteHastaId) {

  /** Ninguno de los dos chutes: equivale a no mandar rango. */
  public boolean vacio() {
    return chuteDesdeId == null && chuteHastaId == null;
  }
}
