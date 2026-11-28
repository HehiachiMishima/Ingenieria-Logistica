package oracle.simulacion.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Velocidad, chutes con buff y chutes bloqueados con que nace todo registro. Con el candado puesto,
 * ningún registro los cambia: hay que desbloquearlos (para todos) y volver a bloquearlos.
 *
 * @param chutesBuff ids del catálogo de chutes (el plano es el mismo para todos los registros)
 * @param chutesBloqueados ids del catálogo que el balanceo automático no usa (solo a mano); null =
 *     aún no se fijaron: valen los del plano ({@code simulacion.chutes.manuales})
 */
public record Predeterminados(
    BigDecimal velocidad, List<Long> chutesBuff, List<Long> chutesBloqueados, boolean bloqueado) {

  /** Lo de siempre: 100 bultos/h, sin buff, los bloqueados del plano y abierto (base sin la fila). */
  public static final Predeterminados INICIALES =
      new Predeterminados(SimulacionConfig.VELOCIDAD_INICIAL, List.of(), null, false);

  public Predeterminados {
    chutesBuff = chutesBuff == null ? List.of() : List.copyOf(chutesBuff);
    chutesBloqueados = chutesBloqueados == null ? null : List.copyOf(chutesBloqueados);
  }

  /** Sin chutes bloqueados propios: los del plano. */
  public Predeterminados(BigDecimal velocidad, List<Long> chutesBuff, boolean bloqueado) {
    this(velocidad, chutesBuff, null, bloqueado);
  }

  /** Los chutes bloqueados fijados o, si aún no hay, los del plano. */
  public List<Long> bloqueadosO(List<Long> delPlano) {
    return chutesBloqueados != null ? chutesBloqueados : List.copyOf(delPlano);
  }
}
