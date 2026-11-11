package oracle.simulacion.response;

import java.math.BigDecimal;

/**
 * Tramo que recibiría un grupo con el rango y la prioridad elegidos: cuántos
 * chutes, cuáles («desde» y «hasta» incluidos; null sin rango) y cuántas de sus
 * rutas están hoy fuera de él (el balanceo las traerá, también las movidas a mano).
 */
public record TramoGrupoResponse(
    String grupo,
    int prioridad,
    long rutas,
    BigDecimal bultos,
    int chutes,
    Long chuteDesdeId,
    String chuteDesde,
    Long chuteHastaId,
    String chuteHasta,
    long rutasFuera) {}
