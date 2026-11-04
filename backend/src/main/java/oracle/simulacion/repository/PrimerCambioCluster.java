package oracle.simulacion.repository;

/**
 * Primer cambio de cluster de una ruta: el chute que tenía en ese momento
 * (a donde vuelve al restablecer) y el id del historial desde el que todo lo
 * manual de la ruta es posterior al cambio.
 */
public interface PrimerCambioCluster {

  Long getRutaId();

  Long getChuteId();

  Long getHistorialId();
}
