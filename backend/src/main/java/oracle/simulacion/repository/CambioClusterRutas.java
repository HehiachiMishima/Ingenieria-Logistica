package oracle.simulacion.repository;

import java.math.BigDecimal;

/** Rutas que pasaron de un cluster a otro (original → actual), con sus totales. */
public interface CambioClusterRutas {

  Long getOrigen();

  Long getDestino();

  Long getRutas();

  BigDecimal getBultos();

  BigDecimal getUnidades();

  BigDecimal getLineas();

  BigDecimal getCod();

  /** Frecuencia de esas rutas (DIARIA, LXV o MJS): cada balanceo suma solo las suyas. */
  String getFrecuencia();
}
