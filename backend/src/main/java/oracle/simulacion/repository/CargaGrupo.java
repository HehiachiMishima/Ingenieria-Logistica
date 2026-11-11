package oracle.simulacion.repository;

import java.math.BigDecimal;

/** Rutas y bultos de un grupo de un cluster en un chute. */
public interface CargaGrupo {

  Long getClusterId();

  String getGrupo();

  Long getChuteId();

  Long getRutas();

  BigDecimal getBultos();
}
