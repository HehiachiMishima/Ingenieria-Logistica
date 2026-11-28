package oracle.simulacion.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Velocidad, chutes con buff y chutes bloqueados que se fijan para todos los registros.
 * {@code chutesBloqueados} nulo = los que ya tenían los predeterminados.
 */
public record PredeterminadosRequest(
    @NotNull @DecimalMin("0.01") @DecimalMax(Limites.MAX_VELOCIDAD) @Digits(integer = 8, fraction = 2)
        BigDecimal velocidad,
    @NotNull @Size(max = Limites.MAX_PARAMETROS) List<@NotNull @Positive Long> chutesBuff,
    @Size(max = Limites.MAX_PARAMETROS) List<@NotNull @Positive Long> chutesBloqueados) {

  public PredeterminadosRequest(BigDecimal velocidad, List<Long> chutesBuff) {
    this(velocidad, chutesBuff, null);
  }
}
