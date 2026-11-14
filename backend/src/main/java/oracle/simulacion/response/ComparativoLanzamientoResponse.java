package oracle.simulacion.response;

import java.util.List;

/**
 * Antes y después de un lanzamiento en los chutes de su cluster: bultos (fase RUTAS) u operarios
 * (fase OPERARIOS). El «después» es el estado justo tras el lanzamiento, sin los cambios
 * posteriores.
 */
public record ComparativoLanzamientoResponse(
    Long lanzamientoId,
    Long clusterId,
    String cluster,
    /** RUTAS u OPERARIOS. */
    String fase,
    int rutas,
    long bultosMovidos,
    List<ChuteComparado> chutes) {

  /** Un chute: su valor antes y después (bultos redondeados hacia arriba, u operarios). */
  public record ChuteComparado(Long chuteId, String chute, long antes, long despues) {}
}
