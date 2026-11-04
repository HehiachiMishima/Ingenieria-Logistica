package oracle.malla.request;

import java.util.Map;

/**
 * Actualización parcial de una regla. {@code activa} permite apagarla sin
 * tocar sus parámetros y {@code parametros} cambia sólo las claves enviadas.
 */
public record MallaReglaActualizarRequest(
        Boolean activa,
        Map<String, String> parametros) {
}
