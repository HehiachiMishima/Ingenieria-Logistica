package oracle.simulacion.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ChuteOperariosRequest(
    @NotNull Long chuteId, @NotNull @Min(0) @Max(Limites.MAX_OPERARIOS) Integer operarios) {}
