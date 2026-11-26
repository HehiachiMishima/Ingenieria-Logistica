package oracle.simulacion.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record ConfiguracionActualizarRequest(
    @NotNull @DecimalMin("0.01") @DecimalMax(Limites.MAX_VELOCIDAD) @Digits(integer = 8, fraction = 2)
        BigDecimal velocidadBase,
    /**
     * Obsoleto: ya no hay equipo del registro (cada ola elige su cantidad en el
     * paso 2). Se acepta para no romper clientes viejos, pero se ignora.
     */
    @Min(1) @Max(Limites.MAX_OPERARIOS_TOTAL) Integer operariosMaximos,
    /** Máximo de operarios en un chute; nulo = reparto parejo del máximo global. */
    @Min(1) @Max(Limites.MAX_OPERARIOS) Integer operariosPorChute,
    @Valid @Size(max = Limites.MAX_PARAMETROS) List<ClusterLimiteRequest> clusters,
    @Valid @Size(max = Limites.MAX_PARAMETROS) List<ChuteOperariosRequest> chutes,
    /**
     * Lo que la pantalla tenía cargado cuando se empezó a editar (opcional). Con él solo se aplica
     * lo que el usuario cambió (lo demás queda como está en el servidor, aunque otra persona lo
     * haya cambiado), y si otra persona cambió eso mismo mientras tanto, se avisa en vez de pisarlo.
     * Sin él, todo lo enviado se aplica (clientes viejos).
     */
    @Valid Anterior anterior) {

  /** Sin «anterior»: todo lo enviado se aplica. */
  public static ConfiguracionActualizarRequest sinAnterior(
      BigDecimal velocidadBase,
      Integer operariosMaximos,
      Integer operariosPorChute,
      List<ClusterLimiteRequest> clusters,
      List<ChuteOperariosRequest> chutes) {
    return new ConfiguracionActualizarRequest(velocidadBase, operariosMaximos, operariosPorChute, clusters, chutes, null);
  }

  /** Velocidad, máximo por chute y límites tal como la pantalla los cargó. */
  public record Anterior(
      @DecimalMin("0.01") @DecimalMax(Limites.MAX_VELOCIDAD) @Digits(integer = 8, fraction = 2)
          BigDecimal velocidadBase,
      @Min(1) @Max(Limites.MAX_OPERARIOS) Integer operariosPorChute,
      @Valid @Size(max = Limites.MAX_PARAMETROS) List<ClusterLimiteRequest> clusters) {}
}
