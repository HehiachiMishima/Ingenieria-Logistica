package oracle.simulacionsanta.service;

/**
 * Una fila guardada del Excel (una paleta: un solo local con todos sus bultos), como la necesita
 * el asignador.
 *
 * @param orden número de fila en el Excel: las paletas ocupan los huecos en este orden
 */
public record PaletaSanta(
    Long id, int orden, String ruta, String local, int cluster, int envio1, int envio2, int envio3) {

  /** Bultos de los 3 envíos. */
  public int total() {
    return envio1 + envio2 + envio3;
  }

  /** Bultos recibidos hasta el envío {@code envio} (0 a 3), incluido. */
  public int recibidos(int envio) {
    int recibidos = 0;
    if (envio >= 1) {
      recibidos += envio1;
    }
    if (envio >= 2) {
      recibidos += envio2;
    }
    if (envio >= 3) {
      recibidos += envio3;
    }
    return recibidos;
  }
}
