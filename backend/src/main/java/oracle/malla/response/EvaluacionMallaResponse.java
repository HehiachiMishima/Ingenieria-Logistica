package oracle.malla.response;

import java.time.Instant;
import java.util.List;

/** Respuesta completa del módulo Configuración: catálogo + evaluación vigente. */
public record EvaluacionMallaResponse(
        Instant evaluadoEn,
        ResumenEvaluacionMallaResponse resumen,
        List<ReglaEvaluadaResponse> reglas) {
}
