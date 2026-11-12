package oracle.simulacion.repository;

/** Rutas que pasaron de un chute de origen a uno de destino. */
public interface MovimientoRutas {

  Long getOrigen();

  Long getDestino();

  Long getRutas();

  /** Frecuencia de esas rutas (DIARIA, LXV o MJS): cada balanceo suma solo las suyas. */
  String getFrecuencia();
}
