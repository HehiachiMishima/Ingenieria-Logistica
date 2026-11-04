package oracle.simulacion.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ClusterLimiteRequest(
    @NotNull Long clusterId,
    @NotNull @DecimalMin("0.00") @DecimalMax(Limites.MAX_HORAS) @Digits(integer = 8, fraction = 2)
        BigDecimal limiteHoras) {}
