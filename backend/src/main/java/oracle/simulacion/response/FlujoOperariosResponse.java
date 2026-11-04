package oracle.simulacion.response;

import java.util.List;

/**
 * Paso de operarios de una ola a la siguiente (el pool se reutiliza): los que
 * siguen en su chute, los que salen de un chute y los que llegan a otro.
 */
public record FlujoOperariosResponse(
    Long origenId,
    String origen,
    Long destinoId,
    String destino,
    int quedan,
    int salen,
    int llegan,
    List<FlujoChuteResponse> chutes) {}
