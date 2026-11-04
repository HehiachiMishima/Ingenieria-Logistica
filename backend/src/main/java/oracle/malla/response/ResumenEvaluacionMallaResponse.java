package oracle.malla.response;

/** Conteos de la evaluación, para las tarjetas de resumen del módulo. */
public record ResumenEvaluacionMallaResponse(
        int reglas,
        int activas,
        int cumplen,
        int noCumplen,
        int inactivas,
        int noEvaluables,
        int hallazgos) {
}
