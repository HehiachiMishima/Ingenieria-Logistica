package oracle.simulacion.request;

/**
 * Paso del balanceo, desde el mismo botón: primero se reparten las rutas por
 * carga y después se asignan los operarios sobre esa carga.
 */
public enum FaseBalanceo {
  /** Paso 1: separa CL1/CL2 e iguala la carga de los chutes de cada ola; no toca operarios. */
  RUTAS,
  /** Paso 2: asigna operarios por ola según la carga y los límites; no mueve rutas. */
  OPERARIOS
}
