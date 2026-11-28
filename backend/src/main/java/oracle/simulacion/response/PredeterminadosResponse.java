package oracle.simulacion.response;

import java.math.BigDecimal;
import java.util.List;
import oracle.simulacion.model.Predeterminados;

/**
 * Velocidad, chutes con buff y chutes bloqueados (solo a mano) de todos los registros, y si el
 * candado está puesto.
 */
public record PredeterminadosResponse(
    BigDecimal velocidad, List<Long> chutesBuff, List<Long> chutesBloqueados, boolean bloqueado) {

  /** {@code bloqueadosDelPlano}: los ids que valen si los predeterminados aún no fijaron los suyos. */
  public static PredeterminadosResponse de(Predeterminados p, List<Long> bloqueadosDelPlano) {
    return new PredeterminadosResponse(
        p.velocidad(), p.chutesBuff(), p.bloqueadosO(bloqueadosDelPlano), p.bloqueado());
  }
}
