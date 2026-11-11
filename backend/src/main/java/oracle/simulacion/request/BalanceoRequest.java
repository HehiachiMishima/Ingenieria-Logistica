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
 *   <li>Paso 1, con varios grupos: su prioridad (nombres de los grupos, del primero al
 *       último). Cada grupo recibe un tramo de chutes seguidos del rango en ese orden.
 *   <li>Paso 2 (OPERARIOS): cantidad de operarios con la que se calcula la ola.
 * </ul>
 *
 * Cada paso ignora lo que no es suyo.
 */
public record BalanceoRequest(
    @Positive Long chuteDesdeId,
    @Positive Long chuteHastaId,
    @Positive @Max(Limites.MAX_OPERARIOS_TOTAL) Integer operarios,
    @Size(max = MAX_GRUPOS) List<@NotBlank @Size(max = 50) String> prioridad) {

  /** Tope de grupos por cluster: nunca más que los chutes del plano. */
  public static final int MAX_GRUPOS = 100;

  /** Rango y cantidad, sin prioridad de grupos. */
  public BalanceoRequest(Long chuteDesdeId, Long chuteHastaId, Integer operarios) {
    this(chuteDesdeId, chuteHastaId, operarios, null);
  }

  /** Solo el rango del paso 1. */
  public BalanceoRequest(Long chuteDesdeId, Long chuteHastaId) {
    this(chuteDesdeId, chuteHastaId, null, null);
  }

  /** Solo la cantidad del paso 2. */
  public static BalanceoRequest conOperarios(Integer operarios) {
    return new BalanceoRequest(null, null, operarios, null);
  }

  /** Ninguno de los dos chutes: equivale a no mandar rango. */
  public boolean sinRango() {
    return chuteDesdeId == null && chuteHastaId == null;
  }
}
