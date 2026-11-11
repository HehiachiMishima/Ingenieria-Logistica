package oracle.simulacion.response;

import java.math.BigDecimal;

/**
 * Grupo de rutas de un cluster: su carga de hoy, su prioridad (null si nunca
 * se eligió) y el tramo de chutes que le dio el último balanceo (null si aún no
 * se balanceó o se restableció).
 */
public record GrupoClusterResponse(
    String nombre,
    Integer prioridad,
    long rutas,
    BigDecimal bultos,
    Long chuteDesdeId,
    String chuteDesde,
    Long chuteHastaId,
    String chuteHasta) {}
