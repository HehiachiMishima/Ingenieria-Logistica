package oracle.simulacion.response;

import java.math.BigDecimal;
/**
 * Límite de un cluster, su avance (PENDIENTE, RUTAS, LISTO o CERRADO) y la
 * diferencia de bultos entre sus chutes al cargar el Excel (%; null con un
 * solo chute).
 */
public record ClusterParametroResponse(
    Long id,
    String nombre,
    BigDecimal limiteHoras,
    String estado,
    Integer diferenciaOriginal) {}
