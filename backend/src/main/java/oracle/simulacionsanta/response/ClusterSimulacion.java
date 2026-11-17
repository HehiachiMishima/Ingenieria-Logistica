package oracle.simulacionsanta.response;

import java.util.List;

/** Resumen de un cluster en la simulación. */
public record ClusterSimulacion(
    int cluster,
    int paletas,
    /** Huecos habilitados del cluster (posiciones de sus rampas + 24 por pasillo). */
    int posiciones,
    int ubicadas,
    int sinUbicar,
    List<Integer> rampas,
    List<Integer> pasillos) {}
