package oracle.simulacion.repository;

/**
 * Un cambio a deshacer al restablecer o reabrir: MOVER_RUTA devuelve la ruta a chuteOrigenId;
 * CAMBIAR_CLUSTER, a clusterOrigenId.
 */
public interface CambioParaDeshacer {
  Long getRutaId();

  String getTipoAccion();

  Long getChuteOrigenId();

  Long getClusterOrigenId();

  /** Frecuencia de la ruta: una diaria no se deshace si el otro balanceo ya cuenta con ella. */
  String getFrecuencia();

  /** Cluster actual de la ruta. */
  Long getClusterId();
}
