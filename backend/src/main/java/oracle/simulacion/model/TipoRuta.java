package oracle.simulacion.model;

import java.util.Locale;

/**
 * Tipo de operación de una ruta: B2C (consumidor final) o B2B (empresa).
 *
 * <p>Una misma ruta puede existir una vez por cada tipo dentro de un archivo; lo que no puede
 * repetirse es el par (ruta, tipo).
 */
public enum TipoRuta {
  B2C,
  B2B;

  /** Tipo a partir del texto del Excel; {@code null} si no es B2C ni B2B. */
  public static TipoRuta desdeTexto(String valor) {
    if (valor == null) {
      return null;
    }
    // strip() también quita el espacio no separable que dejan algunas hojas de cálculo.
    return switch (valor.replace('\u00A0', ' ').strip().toUpperCase(Locale.ROOT)) {
      case "B2C" -> B2C;
      case "B2B" -> B2B;
      default -> null;
    };
  }
}
