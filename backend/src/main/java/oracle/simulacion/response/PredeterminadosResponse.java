package oracle.simulacion.response;

import java.math.BigDecimal;
import java.util.List;
import oracle.simulacion.model.Predeterminados;

/** Velocidad y chutes con buff de todos los registros, y si están bloqueados. */
public record PredeterminadosResponse(BigDecimal velocidad, List<Long> chutesBuff, boolean bloqueado) {

  public static PredeterminadosResponse de(Predeterminados p) {
    return new PredeterminadosResponse(p.velocidad(), p.chutesBuff(), p.bloqueado());
  }
}
