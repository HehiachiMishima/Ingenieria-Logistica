package oracle.simulacion.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Límite de un cluster, su avance (PENDIENTE, RUTAS, LISTO o CERRADO), los
 * clusters con los que no puede compartir chute y la diferencia de bultos
 * entre sus chutes al cargar el Excel (%; null con un solo chute).
 */
public record ClusterParametroResponse(
    Long id,
    String nombre,
    BigDecimal limiteHoras,
    List<Long> incompatibles,
    String estado,
    Integer diferenciaOriginal) {}
