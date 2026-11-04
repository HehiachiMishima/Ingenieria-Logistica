package oracle.malla.response;

/** Una regla del catálogo, sin evaluar. */
public record ReglaMallaResponse(
        Long id,
        String codigo,
        String nombre,
        String descripcion,
        String ambito,
        String severidad,
        boolean activa,
        int orden,
        java.util.List<ParametroMallaResponse> parametros) {
}
