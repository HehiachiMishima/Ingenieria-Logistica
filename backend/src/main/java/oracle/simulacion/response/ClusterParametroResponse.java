package oracle.simulacion.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Límite de un cluster, su avance (PENDIENTE, RUTAS, LISTO o CERRADO), la
 * diferencia de bultos entre sus chutes al cargar el Excel (%; null con un
 * solo chute) y el último rango de chutes con que se balancearon sus rutas
 * (null si nunca se eligió) y los operarios de su última ola.
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
    /** Grupos de rutas del cluster en orden de prioridad (la guardada y luego por nombre). */
    List<GrupoClusterResponse> grupos,
    /** Balanceo de esta fila: LXV o MJS. */
    String balanceo,
    /** Avance del mismo cluster en el otro balanceo. */
    String estadoOtro,
    /** Sus rutas diarias ya están fijas: el automático no las mueve (a mano sí). */
    boolean diariasFijas,
    /** El rango y los tramos de grupo los definió el otro balanceo: aquí se reutilizan. */
    boolean rangoDelOtro) {

  public ClusterParametroResponse(
      Long id,
      String nombre,
      BigDecimal limiteHoras,
      String estado,
      Integer diferenciaOriginal,
      Long chuteDesdeId,
      Long chuteHastaId,
      Integer operariosOla,
      List<GrupoClusterResponse> grupos) {
    this(
        id, nombre, limiteHoras, estado, diferenciaOriginal, chuteDesdeId, chuteHastaId,
        operariosOla, grupos, "LXV", "PENDIENTE", false, false);
  }


  public ClusterParametroResponse(
      Long id,
      String nombre,
      BigDecimal limiteHoras,
      String estado,
      Integer diferenciaOriginal,
      Long chuteDesdeId,
      Long chuteHastaId,
      Integer operariosOla) {
    this(
        id,
        nombre,
        limiteHoras,
        estado,
        diferenciaOriginal,
        chuteDesdeId,
        chuteHastaId,
        operariosOla,
        List.of());
  }
}
