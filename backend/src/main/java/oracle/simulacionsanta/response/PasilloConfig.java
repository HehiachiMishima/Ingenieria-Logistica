package oracle.simulacionsanta.response;

/** Un pasillo en la configuración: cluster (null = sin cluster) y sus posiciones (siempre 24). */
public record PasilloConfig(int numero, Integer cluster, int posiciones) {}
