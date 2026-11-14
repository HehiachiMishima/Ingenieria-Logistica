package oracle.simulacion.response;

import java.util.List;

/**
 * Vista previa del paso 1 de un cluster en una frecuencia: el tramo que recibiría cada grupo con
 * sus rutas de esa frecuencia. No cambia nada.
 *
 * @param chutesRango chutes útiles del rango (sin bloqueados); null sin rango
 * @param chutesSinUsar en Diarias, chutes del rango que ninguna frecuencia llena (el rango se
 *     recorta); en un día, los que ese día deja fuera
 * @param aviso por qué no se podría balancear así (rango inválido o muy chico); null si se puede
 * @param heredadoDe «DIA» cuando el rango lo definieron las diarias (vista de un día): aquí se
 *     reutiliza y no se elige; null si se elige aquí
 * @param usadoDesde primer chute que se usará (null sin tramos)
 * @param usadoHasta último chute que se usará (null sin tramos)
 */
public record VistaBalanceoResponse(
    Long clusterId,
    String cluster,
    Integer chutesRango,
    int chutesSinUsar,
    String aviso,
    List<TramoGrupoResponse> grupos,
    String heredadoDe,
    String usadoDesde,
    String usadoHasta) {
  public VistaBalanceoResponse(
      Long clusterId,
      String cluster,
      Integer chutesRango,
      int chutesSinUsar,
      String aviso,
      List<TramoGrupoResponse> grupos) {
    this(clusterId, cluster, chutesRango, chutesSinUsar, aviso, grupos, null, null, null);
  }
}
