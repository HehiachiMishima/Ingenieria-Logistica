package oracle.simulacionsanta.repository;

import java.time.Instant;

/**
 * Candado de los predeterminados: si la configuración está bloqueada para todos los registros y,
 * en ese caso, quién la bloqueó (nombre visible: nombre y apellido; null si el usuario no se
 * encuentra) y cuándo. Abierto, ambos son null.
 */
public record Candado(boolean bloqueado, String bloqueadoPor, Instant bloqueadoEn) {

  public static final Candado ABIERTO = new Candado(false, null, null);
}
