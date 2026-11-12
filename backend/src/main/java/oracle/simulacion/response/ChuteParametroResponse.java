package oracle.simulacion.response;

import java.util.List;

/**
 * Operarios base de un chute (los que fija el usuario) y los clusters con rutas
 * en él. {@code bloqueado}: chute del plano que no recibe rutas (19, 20, 21);
 * solo se envía si un registro anterior al bloqueo aún tiene rutas en él.
 */
public record ChuteParametroResponse(
    Long id,
    String nombre,
    Integer operarios,
    List<Long> clusterIds,
    boolean bloqueado,
    /** Chute con más capacidad: recibe primero las rutas más pesadas al balancear. */
    boolean buff) {

  public ChuteParametroResponse(
      Long id, String nombre, Integer operarios, List<Long> clusterIds, boolean bloqueado) {
    this(id, nombre, operarios, clusterIds, bloqueado, false);
  }


  public ChuteParametroResponse(Long id, String nombre, Integer operarios, List<Long> clusterIds) {
    this(id, nombre, operarios, clusterIds, false);
  }
}
