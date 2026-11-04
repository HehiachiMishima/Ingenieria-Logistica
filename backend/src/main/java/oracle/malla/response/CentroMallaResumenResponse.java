package oracle.malla.response;

import java.time.Instant;

/** Un centro en la lista, con cuánto tiene cargado. */
public record CentroMallaResumenResponse(
        Long id,
        String codigo,
        String nombre,
        int turnos,
        int flujos,
        int olas,
        int zonas,
        Instant actualizadoEn) {
}
