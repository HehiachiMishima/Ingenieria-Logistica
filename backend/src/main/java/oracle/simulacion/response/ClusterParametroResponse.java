package oracle.simulacion.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Límite de un cluster, su avance en el balanceo pedido (PENDIENTE, RUTAS, LISTO o CERRADO), la
 * diferencia de bultos entre sus chutes al cargar el Excel (%; null con un solo chute), el último
 * rango de chutes con que se balancearon sus rutas (null si nunca se eligió) y los operarios de su
 * última ola.
 */
public record ClusterParametroResponse(
    Long id,
    String nombre,
    BigDecimal limiteHoras,
    String estado,
    Integer diferenciaOriginal,
    Long chuteDesdeId,
    Long chuteHastaId,
    /** Operarios con que se calculó su ola la última vez; null si nunca se eligió. */
    Integer operariosOla,
    /** Grupos de rutas del cluster en orden natural, con su carga en este balanceo. */
    List<GrupoClusterResponse> grupos,
    /** Balanceo de esta fila: LXV, MJS o DIA. */
    String balanceo,
    /** Avance del cluster en cada balanceo (LXV, MJS y DIA), para el menú de pasos. */
    AvanceClusterResponse avance,
    /** Sus rutas diarias ya están balanceadas: el automático de los días no las mueve (a mano sí). */
    boolean diariasFijas) {

  /**
   * Avance del mismo cluster en los tres balanceos y cuántas rutas propias tiene en cada
   * frecuencia: un día sin rutas propias no se balancea (y no frena los operarios ni el cierre);
   * uno sin rutas propias ni diarias no participa.
   */
  public record AvanceClusterResponse(
      String diarias, String lxv, String mjs, long rutasDiarias, long rutasLxv, long rutasMjs) {}
}
