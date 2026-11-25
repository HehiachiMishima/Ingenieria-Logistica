package oracle.simulacion.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import oracle.simulacion.model.Balanceo;

/**
 * Nombres de los catálogos de Simulación (cluster, chute, ruta): cómo se guardan y cómo se
 * comparan. Antes vivían en el lector del Excel; los usa el catálogo y el Registro.
 */
public final class NombresSimulacion {

  private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
  private static final Pattern ESPACIOS = Pattern.compile("[\\s\\u00A0\\u2007\\u202F\\uFEFF]+");

  private NombresSimulacion() {}

  /**
   * Nombre canónico para guardar (cluster, chute, ruta): sin espacios al borde y con los espacios
   * internos (incluidos los no separables) reducidos a uno. Conserva mayúsculas y tildes; de eso
   * se encarga la colación comun.ci de PostgreSQL. La colación NO colapsa espacios internos, por eso
   * se normalizan antes de insertar.
   */
  public static String nombre(String texto) {
    if (texto == null) {
      return "";
    }
    if (esSimple(texto)) {
      return texto;
    }
    return ESPACIOS.matcher(texto).replaceAll(" ").trim();
  }

  /**
   * Clave de comparación de nombres (cluster, chute, ruta): {@link #nombre(String)} en mayúsculas y
   * sin tildes. Equivale a la colación comun.ci sobre el nombre canónico, así que dos nombres con la
   * misma clave son el mismo registro.
   */
  public static String clave(String texto) {
    if (texto == null) {
      return "";
    }
    if (esSimple(texto)) {
      // Camino rápido (ASCII sin espacios raros): toUpperCase devuelve la misma instancia si ya
      // está en mayúsculas, así que no asigna nada en el caso típico ("R3030", "CHUTE 1").
      return texto.toUpperCase(Locale.ROOT);
    }
    String limpio = nombre(texto).toUpperCase(Locale.ROOT);
    return MARCAS.matcher(Normalizer.normalize(limpio, Normalizer.Form.NFD)).replaceAll("");
  }

  /** «L-M-X-J-V-S», «L-X-V» o «M-J-S» para los mensajes. */
  public static String textoFrecuencia(String frecuencia) {
    return Balanceo.diaria(frecuencia) ? "L-M-X-J-V-S" : Balanceo.de(frecuencia).etiqueta();
  }

  /** ASCII imprimible, sin espacios al borde ni dobles: no requiere normalización. */
  private static boolean esSimple(String texto) {
    int n = texto.length();
    if (n == 0) {
      return true;
    }
    if (texto.charAt(0) == ' ' || texto.charAt(n - 1) == ' ') {
      return false;
    }
    char anterior = 'x';
    for (int i = 0; i < n; i++) {
      char c = texto.charAt(i);
      if (c < 0x20 || c > 0x7E || (c == ' ' && anterior == ' ')) {
        return false;
      }
      anterior = c;
    }
    return true;
  }
}
