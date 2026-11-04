package oracle.simulacion.repository;

import java.math.BigDecimal;

/** Totales de las rutas de un archivo que cumplen los filtros del dashboard. */
public interface ResumenRutasFiltro {

  Long getRutas();

  Long getRutasB2c();

  Long getRutasB2b();

  /** Rutas que cambiaron de chute o de cluster (salieron en ANTES, llegaron en DESPUES). */
  Long getCambios();

  BigDecimal getBultos();

  BigDecimal getUnidades();

  BigDecimal getLineas();

  BigDecimal getCod();
}
