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
  /** Número más alto de un cluster (CL999). */
  public static final int MAX_NUMERO_CLUSTER = 999;

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
   * Rutas a una celda: de un cluster (cluster y chute) o de DATA ({@code clusterId} null y el chute
   * del bloque). Todas se mueven o ninguna.
   */
  public record Mover(
      @NotEmpty @Size(max = MoverRutasRequest.MAXIMO) List<@NotNull @Positive Long> rutaIds,
      @Positive Long clusterId,
      @NotNull(message = "Elige la columna donde van las rutas.") @Positive Long chuteId) {}

  /** Varias rutas de DATA a la vez (todas o ninguna): las elegidas en la barra de selección. */
  public record Eliminar(
      @NotEmpty(message = "Elige al menos una ruta.") @Size(max = MAX_SELECCION) List<@NotNull @Positive Long> rutaIds) {}

  /**
   * Locales de una ruta a otra (por su orden dentro de la ruta origen). Todos o ninguno; el código
   * que ya está en la ruta destino se suma ahí.
   */
  public record MoverLocales(
      @NotNull(message = "Falta la ruta de donde salen los locales.") @Positive Long origenId,
      @NotNull(message = "Suelta los locales sobre otra ruta.") @Positive Long destinoId,
      @NotEmpty(message = "Elige al menos un local.") @Size(max = MAX_LOCALES) List<@NotNull @Positive Integer> ordenes,
      /**
       * Código de cada local, en el mismo orden que {@code ordenes} (opcional): lo que el usuario vio
       * en la ficha. Si otra persona cambió los locales de la ruta, el número ya puede ser de otro
       * local; con los códigos el servidor lo nota y no mueve nada.
       */
      @Size(max = MAX_LOCALES) List<@Size(max = 100) String> cods) {

    /** Sin códigos: solo se comprueba que cada número exista (clientes viejos). */
    public static MoverLocales sinCodigos(Long origenId, Long destinoId, List<Integer> ordenes) {
      return new MoverLocales(origenId, destinoId, ordenes, null);
    }
  }

  /** Cuántos clusters hay: se agregan o se quitan desde el último. */
  public record Clusters(@NotNull @Min(0) @Max(MAX_CLUSTERS) Integer cantidad) {}

  /** Un cluster nuevo, «CL{numero}»: el número lo elige el usuario (puede llenar un hueco). */
  public record NuevoCluster(
      @NotNull(message = "Elige el número del cluster.")
          @Min(value = 1, message = "El número del cluster va de 1 a " + MAX_NUMERO_CLUSTER + ".")
          @Max(value = MAX_NUMERO_CLUSTER, message = "El número del cluster va de 1 a " + MAX_NUMERO_CLUSTER + ".")
          Integer numero) {}
}
