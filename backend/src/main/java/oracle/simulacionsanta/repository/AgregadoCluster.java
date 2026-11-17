package oracle.simulacionsanta.repository;

import java.util.List;

/**
 * Las filas de un cluster de un registro, agregadas en SQL.
 *
 * @param bultosEnvio bultos de los envíos 1, 2 y 3
 * @param completasEnvio paletas cuyo acumulado hasta el envío 1, 2 y 3 ya es su total
 */
public record AgregadoCluster(
    int cluster, int paletas, List<Integer> bultosEnvio, List<Integer> completasEnvio) {

  /** Un cluster sin filas. */
  public static AgregadoCluster vacio(int cluster) {
    return new AgregadoCluster(cluster, 0, List.of(0, 0, 0), List.of(0, 0, 0));
  }

  public int bultos() {
    return bultosEnvio.stream().mapToInt(Integer::intValue).sum();
  }
}
