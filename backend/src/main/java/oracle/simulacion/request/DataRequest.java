package oracle.simulacion.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que recibe DATA, el bloque de Configuración con las rutas que no se balancean: crear y editar
 * rutas, asignarles tipo, frecuencia y grupo, moverlas entre DATA y los clusters, y cuántos
 * clusters hay.
 */
public final class DataRequest {

  /** Rutas por acción: una selección a mano no llega a tanto y acota la transacción. */
  public static final int MAX_SELECCION = 5_000;
  /** Códigos (locales) por ruta. */
  public static final int MAX_LOCALES = 5_000;
  /** Clusters del escenario (CL1…CLn). */
  public static final int MAX_CLUSTERS = 30;

  private DataRequest() {}

  /** Crear o editar una ruta: número y uno o varios códigos. Nace en DATA, sin tipo, frecuencia ni grupo. */
  public record Ruta(
      @NotBlank(message = "Escribe el número de ruta.") @Size(max = 50) String codigoRuta,
      @NotEmpty(message = "La ruta necesita al menos un código.")
          @Size(max = MAX_LOCALES)
          List<@NotNull @Valid Local> locales) {}

  /**
   * Un código (local) con sus bultos, unidades y líneas (dos decimales). {@code filas}: cuántas veces
   * se ingresó ya sumado (al editar se conserva; null = 1).
   */
  public record Local(
      @NotBlank(message = "Cada línea necesita su código.") @Size(max = 100) String cod,
      @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal bultos,
      @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unidades,
      @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal lineas,
      @Min(1) @Max(1_000_000) Integer filas) {}

  /** Tipo para las rutas elegidas de DATA (todas o ninguna: sin rutas repetidas). */
  public record Tipo(
      @NotEmpty @Size(max = MAX_SELECCION) List<@NotNull @Positive Long> rutaIds,
      @NotNull @Pattern(regexp = "B2C|B2B", message = "El tipo debe ser B2C o B2B.") String tipo) {}

  /** Frecuencia para las rutas elegidas de DATA. */
  public record Frecuencia(
      @NotEmpty @Size(max = MAX_SELECCION) List<@NotNull @Positive Long> rutaIds,
      @NotNull
          @Pattern(regexp = "LXV|MJS|DIARIA", message = "La frecuencia debe ser LXV, MJS o DIARIA.")
          String frecuencia) {}

  /** Grupo («1», «2», «A») para las rutas elegidas de DATA. */
  public record Grupo(
      @NotEmpty @Size(max = MAX_SELECCION) List<@NotNull @Positive Long> rutaIds,
      @NotBlank(message = "Escribe el grupo.") @Size(max = 50) String grupo) {}

  /**
   * Rutas a una celda: un cluster y un chute, o DATA ({@code clusterId} null) en ese chute. Todas
   * se mueven o ninguna.
   */
  public record Mover(
      @NotEmpty @Size(max = MoverRutasRequest.MAXIMO) List<@NotNull @Positive Long> rutaIds,
      @Positive Long clusterId,
      @NotNull @Positive Long chuteId) {}

  /** Cuántos clusters hay: se agregan o se quitan desde el último. */
  public record Clusters(@NotNull @Min(0) @Max(MAX_CLUSTERS) Integer cantidad) {}
}
