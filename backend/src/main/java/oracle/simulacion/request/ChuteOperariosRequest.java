package oracle.simulacion.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Base de operarios de un chute, si tiene buff (más capacidad) y si está bloqueado (el balanceo
 * automático no lo usa; recibe rutas solo a mano). Nulo = no cambia. El buff solo se cambia antes
 * de balancear el primer cluster; el bloqueo, en cualquier momento.
 */
public record ChuteOperariosRequest(
    @NotNull Long chuteId,
    @NotNull @Min(0) @Max(Limites.MAX_OPERARIOS) Integer operarios,
    Boolean buff,
    Boolean manual) {

  public ChuteOperariosRequest(Long chuteId, Integer operarios) {
    this(chuteId, operarios, null, null);
  }

  public ChuteOperariosRequest(Long chuteId, Integer operarios, Boolean buff) {
    this(chuteId, operarios, buff, null);
  }
}
