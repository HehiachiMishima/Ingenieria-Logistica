package oracle.simulacionsanta.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Configuración de un registro o de los predeterminados: operarios, bultos por hora, orden de
 * llenado de cada cluster y el cluster (y las posiciones, en rampas) de las 19 rampas y los 24
 * pasillos. Las anotaciones dan el 400 por campo; ConfiguracionSanta.desde completa las reglas
 * que cruzan campos (números del plano, repetidos, posiciones según la capacidad de cada rampa).
 * Se ignoran campos de más (p. ej. la capacidad que trae RampaConfig).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfiguracionRequest(
    @NotNull(message = "Indica los operarios.")
        @Min(value = 1, message = "Los operarios deben estar entre 1 y 99.")
        @Max(value = 99, message = "Los operarios deben estar entre 1 y 99.")
        Integer operarios,
    @NotNull(message = "Indica los bultos por hora.")
        @Min(value = 1, message = "Los bultos por hora deben estar entre 1 y 9999.")
        @Max(value = 9999, message = "Los bultos por hora deben estar entre 1 y 9999.")
        Integer bultosHora,
    @NotNull(message = "Indica el orden de llenado de los 3 clusters.")
        @Size(min = 3, max = 3, message = "El orden de llenado lleva un valor por cluster (3).")
        List<
                @NotNull(message = "Indica el orden de llenado del cluster.")
                @Pattern(
                    regexp = "RAMPAS_PRIMERO|PASILLOS_PRIMERO",
                    message = "El orden de llenado es RAMPAS_PRIMERO o PASILLOS_PRIMERO.")
                String>
            ordenClusters,
    @NotNull(message = "Indica las 19 rampas (15 a 33).")
        @Size(min = 19, max = 19, message = "Deben venir las 19 rampas (15 a 33).")
        List<@NotNull(message = "Hay una rampa vacía en la lista.") @Valid RampaRequest> rampas,
    @NotNull(message = "Indica los 24 pasillos (1 a 24).")
        @Size(min = 24, max = 24, message = "Deben venir los 24 pasillos (1 a 24).")
        List<@NotNull(message = "Hay un pasillo vacío en la lista.") @Valid PasilloRequest>
            pasillos) {

  /** Una rampa: su cluster (null = sin cluster) y cuántas posiciones de su rack se habilitan. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RampaRequest(
      @NotNull(message = "Cada rampa lleva su número.") Integer numero,
      @Min(value = 1, message = "El cluster es 1, 2, 3 o vacío.")
          @Max(value = 3, message = "El cluster es 1, 2, 3 o vacío.")
          Integer cluster,
      @NotNull(message = "Cada rampa lleva sus posiciones.")
          @Min(value = 0, message = "Las posiciones no pueden ser negativas.")
          Integer posiciones) {}

  /** Un pasillo: su cluster (null = sin cluster). Siempre tiene sus 24 posiciones. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PasilloRequest(
      @NotNull(message = "Cada pasillo lleva su número.") Integer numero,
      @Min(value = 1, message = "El cluster es 1, 2, 3 o vacío.")
          @Max(value = 3, message = "El cluster es 1, 2, 3 o vacío.")
          Integer cluster) {}
}
