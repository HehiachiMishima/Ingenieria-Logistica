package oracle.simulacionsanta.response;

import java.util.List;

/**
 * Dashboard de un registro: totales por envío y, por cluster, su capacidad con la configuración
 * guardada, sus paletas, las que quedan sin ubicar y el avance por envío.
 */
public record DashboardSanta(
    Long registroId,
    String nombre,
    /** Envíos ya recibidos (0 a 3). */
    int envioActual,
    /** Configuración guardada del registro. */
    int operarios,
    int bultosHora,
    /** Candado global de la configuración. */
    boolean bloqueado,
    int bultosTotal,
    int paletasTotal,
    /** Envíos 1 a 3, en orden. */
    List<Envio> envios,
    /** Siempre los 3 clusters, en orden. */
    List<Cluster> clusters) {

  /** Bultos de un envío (1 a 3). */
  public record Envio(int envio, int bultos) {}

  /**
   * Un cluster.
   *
   * @param rampas rampas asignadas al cluster en la configuración guardada
   * @param posiciones huecos habilitados (posiciones de sus rampas + 24 por pasillo)
   * @param paletas filas del Excel del cluster
   * @param sinUbicar paletas sin hueco con la configuración guardada (la misma simulación)
   */
  public record Cluster(
      int cluster,
      int rampas,
      int pasillos,
      int posiciones,
      int paletas,
      int sinUbicar,
      int bultos,
      /** Envíos 1 a 3, en orden. */
      List<EnvioCluster> porEnvio) {}

  /**
   * Avance de un cluster en un envío.
   *
   * @param paletasCompletas paletas cuyo acumulado hasta este envío ya es su total
   */
  public record EnvioCluster(int envio, int bultos, int paletasCompletas) {}
}
