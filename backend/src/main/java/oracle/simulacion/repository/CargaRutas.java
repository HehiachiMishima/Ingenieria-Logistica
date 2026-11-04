package oracle.simulacion.repository;

import java.math.BigDecimal;

/** Totales de las rutas de un archivo agrupados por (cluster, chute). */
public interface CargaRutas {

  Long getClusterId();

  Long getChuteId();

  Long getRutas();

  BigDecimal getBultos();

  BigDecimal getUnidades();

  BigDecimal getLineas();

  BigDecimal getCod();
}
