package oracle.simulacion.response;

import java.math.BigDecimal;

/**
 * Límite de un cluster, su avance (PENDIENTE, RUTAS, LISTO o CERRADO), la
 * diferencia de bultos entre sus chutes al cargar el Excel (%; null con un
 * solo chute) y el último rango de chutes con que se balancearon sus rutas
 * (null si nunca se eligió).
 */
public record ClusterParametroResponse(
    Long id,
    String nombre,
    BigDecimal limiteHoras,
    String estado,
    Integer diferenciaOriginal,
    Long chuteDesdeId,
    Long chuteHastaId) {}
