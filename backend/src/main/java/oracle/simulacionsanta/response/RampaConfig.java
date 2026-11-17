package oracle.simulacionsanta.response;

/** Una rampa en la configuración: cluster (null = sin cluster), capacidad y posiciones habilitadas. */
public record RampaConfig(int numero, Integer cluster, int capacidad, int posiciones) {}
