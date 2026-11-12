package oracle.simulacion.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Base de operarios de un chute y si tiene buff (más capacidad). {@code buff} nulo = no cambia;
 * solo se puede cambiar antes de balancear el primer cluster.
 */
public record ChuteOperariosRequest(
    @NotNull Long chuteId,
    @NotNull @Min(0) @Max(Limites.MAX_OPERARIOS) Integer operarios,
    Boolean buff) {

  public ChuteOperariosRequest(Long chuteId, Integer operarios) {
    this(chuteId, operarios, null);
  }
}
