package oracle.simulacionsanta.repository;

/**
 * Una fila de simulacion_santa.ubicaciones o de predeterminados_ubicaciones.
 *
 * @param tipo RAMPA o PASILLO
 * @param cluster 1 a 3; null = sin cluster
 * @param posiciones posiciones habilitadas de la rampa; null en los pasillos
 */
public record UbicacionGuardada(String tipo, int numero, Integer cluster, Integer posiciones) {}
