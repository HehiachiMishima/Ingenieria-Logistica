package oracle.simulacion.response;

import java.util.List;

/**
 * Vista previa del paso 1 de un cluster: sus grupos en el orden de prioridad
 * con el tramo que recibiría cada uno. No cambia nada.
 *
 * @param chutesRango chutes útiles del rango (sin bloqueados); null sin rango
 * @param chutesSinUsar chutes del rango que no recibe ningún grupo (tienen menos rutas que chutes)
 * @param aviso por qué no se podría balancear así (rango inválido o muy chico); null si se puede
 * @param heredadoDe balanceo (L-X-V o M-J-S) que ya definió el rango y los tramos del cluster: aquí
 *     se reutilizan y no se eligen; null si se eligen aquí
 */
public record VistaBalanceoResponse(
    Long clusterId,
    String cluster,
    Integer chutesRango,
    int chutesSinUsar,
    String aviso,
    List<TramoGrupoResponse> grupos,
    String heredadoDe) {

  public VistaBalanceoResponse(
      Long clusterId,
      String cluster,
      Integer chutesRango,
      int chutesSinUsar,
      String aviso,
      List<TramoGrupoResponse> grupos) {
    this(clusterId, cluster, chutesRango, chutesSinUsar, aviso, grupos, null);
  }
}
