package oracle.simulacionsanta.response;

import java.util.List;

/** Resultado de leer un Excel sin guardarlo. */
public record ValidacionExcelResponse(
    String nombreArchivo,
    /** Filas de datos no vacías (válidas y con error). */
    int filasLeidas,
    int filasValidas,
    int rutas,
    int bultos,
    List<Integer> bultosEnvios,
    List<Integer> paletasCluster,
    /** Como máximo 50. */
    List<ErrorFila> errores,
    int totalErrores,
    /** Bultos de las filas válidas: 3 filas (C1, C2, C3) × 3 columnas (envío 1, 2, 3). */
    List<List<Integer>> bultosClusterEnvio) {}
