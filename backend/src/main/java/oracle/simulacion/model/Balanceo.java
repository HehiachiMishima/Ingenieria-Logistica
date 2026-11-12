package oracle.simulacion.model;

import java.util.Locale;
import oracle.common.exception.SolicitudInvalidaException;

/**
 * Los dos balanceos de un registro: cada uno trabaja con las rutas de sus días
 * y las diarias. Son independientes (su propio avance, rango y operarios por
 * cluster); solo comparten las rutas diarias, que el primer balanceo
 * automático acomoda y desde ahí ningún automático vuelve a mover.
 */
public enum Balanceo {
  /** Lunes, miércoles y viernes. */
  LXV("L-X-V"),
  /** Martes, jueves y sábado. */
  MJS("M-J-S");

  /** Ruta de lunes a sábado: aparece en los dos balanceos. */
  public static final String DIARIA = "DIARIA";

  private final String etiqueta;

  Balanceo(String etiqueta) {
    this.etiqueta = etiqueta;
  }

  public String etiqueta() {
    return etiqueta;
  }

  public Balanceo otro() {
    return this == LXV ? MJS : LXV;
  }

  /** La ruta de esa frecuencia aparece en este balanceo (las diarias, en los dos). */
  public boolean incluye(String frecuencia) {
    return frecuencia == null || DIARIA.equals(frecuencia) || name().equals(frecuencia);
  }

  /** Frecuencia diaria (o sin dato, como las rutas anteriores a las frecuencias). */
  public static boolean diaria(String frecuencia) {
    return frecuencia == null || DIARIA.equals(frecuencia);
  }

  /** LXV o MJS (sin distinguir mayúsculas); null o vacío = L-X-V. */
  public static Balanceo de(String valor) {
    if (valor == null || valor.isBlank()) {
      return LXV;
    }
    String limpio = valor.trim().toUpperCase(Locale.ROOT).replace("-", "");
    for (Balanceo b : values()) {
      if (b.name().equals(limpio)) {
        return b;
      }
    }
    throw new SolicitudInvalidaException("Balanceo desconocido: " + valor + ". Usa LXV o MJS.");
  }
}
