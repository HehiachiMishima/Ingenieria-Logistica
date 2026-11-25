package oracle.simulacion.diccionario;

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

/** Lo que recibe el Registro v2 (diccionario de rutas de Simulación Titan). */
public final class DiccionarioPeticiones {

  /** Rutas por acción: una selección a mano no llega a tanto y acota la transacción. */
  public static final int MAX_SELECCION = 5_000;
  /** Códigos (locales) por ruta. */
  public static final int MAX_LOCALES = 5_000;
  /** Bloques de cluster (CL1…CLn). */
  public static final int MAX_CLUSTERS = 30;

  private DiccionarioPeticiones() {}

  /** Crear o editar una ruta: número, tipo (opcional: null = sin tipo) y uno o varios códigos. */
  public record Ruta(
      @NotBlank(message = "Escribe el número de ruta.") @Size(max = 50) String codigoRuta,
      @Pattern(regexp = "B2C|B2B", message = "El tipo debe ser B2C o B2B.") String tipo,
      @NotEmpty(message = "La ruta necesita al menos un código.")
          @Size(max = MAX_LOCALES)
          List<@NotNull @Valid Local> locales) {}

  /** Un código (local) con sus bultos, unidades y líneas (dos decimales). */
  public record Local(
      @NotBlank(message = "Cada línea necesita su código.") @Size(max = 100) String cod,
      @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal bultos,
      @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unidades,
      @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal lineas) {}

  /** Rutas a un bloque: un cluster, o Datos con {@code clusterId} null (pierden cluster y frecuencia). */
  public record Mover(
      @NotEmpty @Size(max = MAX_SELECCION) List<@NotNull @Positive Long> rutaIds,
      @Positive Long clusterId) {}

  /** Frecuencia para las rutas elegidas que están en un cluster. */
  public record Frecuencia(
      @NotEmpty @Size(max = MAX_SELECCION) List<@NotNull @Positive Long> rutaIds,
      @NotNull
          @Pattern(regexp = "LXV|MJS|DIARIA", message = "La frecuencia debe ser LXV, MJS o DIARIA.")
          String frecuencia) {}

  /** Grupo («1», «2», «A») para las rutas elegidas que están en un cluster. */
  public record Grupo(
      @NotEmpty @Size(max = MAX_SELECCION) List<@NotNull @Positive Long> rutaIds,
      @NotBlank(message = "Escribe el grupo.") @Size(max = 50) String grupo) {}

  /** Tipo para las rutas elegidas (todas o ninguna: sin rutas repetidas). */
  public record Tipo(
      @NotEmpty @Size(max = MAX_SELECCION) List<@NotNull @Positive Long> rutaIds,
      @NotNull @Pattern(regexp = "B2C|B2B", message = "El tipo debe ser B2C o B2B.") String tipo) {}

  /** Deshacer el último movimiento: cada ruta vuelve a su bloque y frecuencia de antes. */
  public record Restaurar(@NotEmpty @Size(max = MAX_SELECCION) List<@NotNull @Valid Ubicacion> rutas) {}

  /** Dónde estaba una ruta: cluster (null = Datos), frecuencia y grupo (null = sin asignar). */
  public record Ubicacion(
      @NotNull @Positive Long id,
      @Positive Long clusterId,
      @Pattern(regexp = "LXV|MJS|DIARIA", message = "La frecuencia debe ser LXV, MJS o DIARIA.")
          String frecuencia,
      @Size(max = 50) String grupo) {}

  /** Cuántos bloques de cluster hay: se agregan o se quitan desde el último. */
  public record Clusters(@NotNull @Min(0) @Max(MAX_CLUSTERS) Integer cantidad) {}
}
