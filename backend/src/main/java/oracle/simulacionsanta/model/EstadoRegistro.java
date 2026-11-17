package oracle.simulacionsanta.model;

import java.util.Locale;

/** Estado de un registro según el envío en curso: 0 → PENDIENTE, 1 o 2 → EN_CURSO, 3 → COMPLETO. */
public enum EstadoRegistro {
  PENDIENTE,
  EN_CURSO,
  COMPLETO;

  public static EstadoRegistro de(int envioActual) {
    if (envioActual <= 0) {
      return PENDIENTE;
    }
    return envioActual >= 3 ? COMPLETO : EN_CURSO;
  }

  /** El estado escrito como texto (sin distinguir mayúsculas); null si no es uno de los tres. */
  public static EstadoRegistro desdeTexto(String texto) {
    if (texto == null) {
      return null;
    }
    String limpio = texto.trim().toUpperCase(Locale.ROOT);
    for (EstadoRegistro estado : values()) {
      if (estado.name().equals(limpio)) {
        return estado;
      }
    }
    return null;
  }
}
