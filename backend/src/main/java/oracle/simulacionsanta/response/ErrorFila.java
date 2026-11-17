package oracle.simulacionsanta.response;

/**
 * Error de una fila del Excel.
 *
 * @param fila número de fila en el Excel (1 = primera)
 */
public record ErrorFila(int fila, String mensaje) {}
