package oracle.simulacion.repository;

/**
 * Prioridad de un grupo dentro de su cluster y el tramo de chutes que le dio el
 * último balanceo («desde» y «hasta» incluidos; null mientras no se haya
 * balanceado o tras restablecer el cluster).
 */
public record GrupoGuardado(
    Long clusterId, String grupo, int prioridad, Long chuteDesdeId, Long chuteHastaId) {}
