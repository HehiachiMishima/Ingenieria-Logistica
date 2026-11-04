package oracle.malla.response;

import java.util.List;

/** Resultado de evaluar una regla contra la malla de todos los centros. */
public record ReglaEvaluadaResponse(
        Long id,
        String codigo,
        String nombre,
        String descripcion,
        String ambito,
        String severidad,
        boolean activa,
        int orden,
        String estado,
        int hallazgos,
        List<ParametroMallaResponse> parametros,
        List<HallazgoMallaResponse> detalles) {
}
