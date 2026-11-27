package oracle.simulacion.response;

import java.util.List;

/**
 * Operarios base de un chute (los que fija el usuario) y los clusters con rutas
 * en él. {@code bloqueado}: chute del plano que no recibe rutas; solo se envía si
 * un registro anterior al bloqueo aún tiene rutas en él. {@code manual}: chute de
 * uso manual (19, 20, 21): se ve con su franja de peligro y recibe rutas solo a
 * mano (el balanceo automático no lo usa ni mueve lo que hay en él).
 */
public record ChuteParametroResponse(
    Long id,
    String nombre,
    Integer operarios,
    List<Long> clusterIds,
    boolean bloqueado,
    /** Chute con más capacidad: recibe primero las rutas más pesadas al balancear. */
    boolean buff,
    boolean manual) {

  public ChuteParametroResponse(
      Long id, String nombre, Integer operarios, List<Long> clusterIds, boolean bloqueado, boolean buff) {
    this(id, nombre, operarios, clusterIds, bloqueado, buff, false);
  }

  public ChuteParametroResponse(
      Long id, String nombre, Integer operarios, List<Long> clusterIds, boolean bloqueado) {
    this(id, nombre, operarios, clusterIds, bloqueado, false);
  }


  public ChuteParametroResponse(Long id, String nombre, Integer operarios, List<Long> clusterIds) {
    this(id, nombre, operarios, clusterIds, false);
  }
}
