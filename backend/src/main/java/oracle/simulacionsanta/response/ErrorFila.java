package oracle.simulacionsanta.response;

/**
 * Error de una fila del Excel.
 *
 * @param fila número de fila en el Excel (1 = primera)
 * @param regla código de la regla incumplida (E2 a E7, ver ExcelSantaAnita.Regla)
 */
public record ErrorFila(int fila, String regla, String mensaje) {}
