package oracle.simulacion.repository;

import java.math.BigDecimal;

/**
 * Bultos que un lanzamiento cambió en un chute ({@code delta}: entraron menos salieron) y los que
 * cambiaron después, hasta hoy ({@code deltaPosterior}).
 */
public interface DeltaChute {

  Long getChuteId();

  BigDecimal getDelta();

  BigDecimal getDeltaPosterior();
}
