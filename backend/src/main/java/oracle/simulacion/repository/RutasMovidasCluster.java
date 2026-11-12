package oracle.simulacion.repository;

/** Rutas de un cluster que hoy están en un chute distinto al original. */
public interface RutasMovidasCluster {

  Long getClusterId();

  Long getRutas();

  /** Frecuencia de esas rutas (DIARIA, LXV o MJS): cada balanceo suma solo las suyas. */
  String getFrecuencia();
}
