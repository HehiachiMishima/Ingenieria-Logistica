package oracle.simulacion.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Velocidad y chutes con buff con que nace todo registro. Bloqueados, ningún registro los cambia:
 * hay que desbloquearlos (para todos) y volver a bloquearlos.
 *
 * @param chutesBuff ids del catálogo de chutes (el plano es el mismo para todos los registros)
 */
public record Predeterminados(BigDecimal velocidad, List<Long> chutesBuff, boolean bloqueado) {

  /** Lo de siempre: 100 bultos/h, sin buff y abierto (base sin la fila). */
  public static final Predeterminados INICIALES =
      new Predeterminados(SimulacionConfig.VELOCIDAD_INICIAL, List.of(), false);

  public Predeterminados {
    chutesBuff = chutesBuff == null ? List.of() : List.copyOf(chutesBuff);
  }
}
