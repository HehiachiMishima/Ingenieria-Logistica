package oracle.malla.response;

/** Un incumplimiento concreto localizado en un centro y una entidad de la malla. */
public record HallazgoMallaResponse(
        String centroCodigo,
        String centroNombre,
        String entidad,
        String detalle,
        String esperado,
        String obtenido) {
}
