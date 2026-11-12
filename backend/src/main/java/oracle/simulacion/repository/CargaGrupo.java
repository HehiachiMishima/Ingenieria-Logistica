package oracle.simulacion.repository;

import java.math.BigDecimal;

/** Rutas y bultos de un grupo de un cluster en un chute. */
public interface CargaGrupo {

  Long getClusterId();

  String getGrupo();

  Long getChuteId();

  Long getRutas();

  BigDecimal getBultos();

  /** Frecuencia de esas rutas (DIARIA, LXV o MJS): cada balanceo suma solo las suyas. */
  String getFrecuencia();
}
