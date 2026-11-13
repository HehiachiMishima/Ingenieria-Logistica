package oracle.simulacion.model;

import java.util.List;
import java.util.Locale;
import oracle.common.exception.SolicitudInvalidaException;

/**
 * Los balanceos de un registro, en el orden en que se trabaja cada cluster:
 *
 * <ol>
 *   <li>{@link #DIA}: las rutas diarias (aparecen los seis días). Aquí se elige el rango de
 *       chutes y se reparten los tramos de los grupos; desde ahí las diarias quedan fijas para el
 *       balanceo automático de los dos días.
 *   <li>{@link #LXV} y {@link #MJS} (en cualquier orden): cada día mueve solo sus rutas, dentro del
 *       rango y los tramos de las diarias, y cuenta las diarias como carga fija.
 * </ol>
 *
 * Los operarios se asignan a los dos días con la misma cantidad y cerrar cierra los tres. A mano
 * todo se puede mover: una diaria movida cambia en los dos días.
 */
public enum Balanceo {
  /** Lunes, miércoles y viernes. */
  LXV("L-X-V"),
  /** Martes, jueves y sábado. */
  MJS("M-J-S"),
  /** Rutas diarias: se balancean primero y no tienen operarios propios (no son un día). */
  DIA("Diarias");

  /** Ruta de lunes a sábado: aparece en los dos días y la balancea {@link #DIA}. */
  public static final String DIARIA = "DIARIA";

  /** Los dos días de trabajo (los que tienen operarios, dashboard y exportación). */
  public static final List<Balanceo> DIAS = List.of(LXV, MJS);

  private final String etiqueta;

  Balanceo(String etiqueta) {
    this.etiqueta = etiqueta;
  }

  public String etiqueta() {
    return etiqueta;
  }

  /** L-X-V o M-J-S: un día de trabajo (no las diarias). */
  public boolean esDia() {
    return this != DIA;
  }

  /** El otro día de trabajo. Las diarias no tienen «otro». */
  public Balanceo otro() {
    if (this == DIA) {
      throw new IllegalStateException("Las diarias no tienen otro balanceo.");
    }
    return this == LXV ? MJS : LXV;
  }

  /** La ruta de esa frecuencia se ve (y suma carga) en este balanceo. */
  public boolean incluye(String frecuencia) {
    return this == DIA ? diaria(frecuencia) : diaria(frecuencia) || name().equals(frecuencia);
  }

  /**
   * El balanceo automático mueve las rutas de esa frecuencia: las diarias, solo {@link #DIA}; las
   * de un día, solo ese día (las diarias allí son carga fija).
   */
  public boolean mueve(String frecuencia) {
    return this == DIA ? diaria(frecuencia) : name().equals(frecuencia);
  }

  /** Balanceo dueño de una ruta: el que la mueve en automático y guarda su historial. */
  public static Balanceo deRuta(String frecuencia) {
    return diaria(frecuencia) ? DIA : valueOf(frecuencia);
  }

  /** Frecuencia diaria (o sin dato, como las rutas anteriores a las frecuencias). */
  public static boolean diaria(String frecuencia) {
    return frecuencia == null || DIARIA.equals(frecuencia);
  }

  /** LXV, MJS o DIA (también «L-X-V», «diaria»…); null o vacío = L-X-V. */
  public static Balanceo de(String valor) {
    if (valor == null || valor.isBlank()) {
      return LXV;
    }
    String limpio = valor.trim().toUpperCase(Locale.ROOT).replace("-", "");
    if (limpio.equals(DIARIA) || limpio.equals("DIARIAS")) {
      return DIA;
    }
    for (Balanceo b : values()) {
      if (b.name().equals(limpio)) {
        return b;
      }
    }
    throw new SolicitudInvalidaException(
        "Balanceo desconocido: " + valor + ". Usa LXV, MJS o DIA.");
  }

  /** Como {@link #de} pero solo un día de trabajo: dashboard, rutas del dashboard y exportación. */
  public static Balanceo dia(String valor) {
    Balanceo balanceo = de(valor);
    if (!balanceo.esDia()) {
      throw new SolicitudInvalidaException(
          "Las diarias no son un día de trabajo: elige L-X-V o M-J-S (cada uno ya incluye sus diarias).");
    }
    return balanceo;
  }
}
