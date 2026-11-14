package oracle.simulacion.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** Velocidad y chutes con buff que se bloquean para todos los registros. */
public record PredeterminadosRequest(
    @NotNull @DecimalMin("0.01") @DecimalMax(Limites.MAX_VELOCIDAD) @Digits(integer = 8, fraction = 2)
        BigDecimal velocidad,
    @NotNull @Size(max = Limites.MAX_PARAMETROS) List<@NotNull @Positive Long> chutesBuff) {}
