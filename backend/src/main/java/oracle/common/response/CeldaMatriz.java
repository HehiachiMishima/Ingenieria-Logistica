package oracle.common.response;

/** Celda de una matriz de cruce (fila x columna) con su conteo. */
public record CeldaMatriz(String fila, String columna, long valor) {
}
