package oracle.common.response;

import java.time.Instant;

/**
 * Envoltorio estandar de toda respuesta de la API.
 * exito=true => datos con payload; exito=false => mensaje de error.
 */
public record ApiResponse<T>(
        boolean exito,
        String mensaje,
        T datos,
        Instant timestamp) {

    public static <ApiResponseT> ApiResponse<ApiResponseT> ok(String mensaje, ApiResponseT datos) {
        return new ApiResponse<>(true, mensaje, datos, Instant.now());
    }
}
