package oracle.simulacion.response;

import java.util.List;

/** Operarios base de un chute (los que fija el usuario) y los clusters con rutas en él. */
public record ChuteParametroResponse(
    Long id, String nombre, Integer operarios, List<Long> clusterIds) {}
