package oracle.simulacion.response;

import java.util.List;

/**
 * Vista previa del paso 1 de un cluster: sus grupos en el orden de prioridad
 * con el tramo que recibiría cada uno. No cambia nada.
 *
 * @param chutesRango chutes útiles del rango (sin bloqueados); null sin rango
 * @param chutesSinUsar chutes del rango que no recibe ningún grupo (tienen menos rutas que chutes)
 * @param aviso por qué no se podría balancear así (rango inválido o muy chico); null si se puede
 */
public record VistaBalanceoResponse(
    Long clusterId,
    String cluster,
    Integer chutesRango,
    int chutesSinUsar,
    String aviso,
    List<TramoGrupoResponse> grupos) {}
