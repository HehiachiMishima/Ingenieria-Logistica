package oracle.simulacion.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Parámetros de un paso de balanceo. Todo es opcional: el cliente manda
 * {@code {}} cuando no hay cuerpo, y cada paso usa lo último guardado del
 * cluster.
 *
 * <ul>
 *   <li>Paso 1 (RUTAS): chutes entre los que se reparten sus rutas, de «desde» a
 *       «hasta», ambos incluidos, en el orden natural de los chutes (CHUTE 2
 *       antes que CHUTE 10); invertidos se toman al revés. Los dos vacíos = sin
 *       rango; solo uno es un error que valida el servicio.
 *   <li>Con varios grupos, cada uno recibe un tramo de chutes seguidos del rango en orden natural
 *       (1, 2, 10…): ya no hay prioridad elegida.
 *   <li>Paso 2 (OPERARIOS): cantidad de operarios con la que se calcula la ola.
 * </ul>
 *
 * Cada paso ignora lo que no es suyo.
 */
public record BalanceoRequest(
    @Positive Long chuteDesdeId,
    @Positive Long chuteHastaId,
    @Positive @Max(Limites.MAX_OPERARIOS_TOTAL) Integer operarios) {

  /** Solo el rango del paso 1. */
  public BalanceoRequest(Long chuteDesdeId, Long chuteHastaId) {
    this(chuteDesdeId, chuteHastaId, null);
  }

  /** Solo la cantidad del paso 2. */
  public static BalanceoRequest conOperarios(Integer operarios) {
    return new BalanceoRequest(null, null, operarios);
  }

  /** Ninguno de los dos chutes: equivale a no mandar rango. */
  public boolean sinRango() {
    return chuteDesdeId == null && chuteHastaId == null;
  }
}
