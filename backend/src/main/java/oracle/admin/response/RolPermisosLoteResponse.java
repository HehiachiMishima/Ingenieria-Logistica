package oracle.admin.response;

import java.util.List;

/**
 * Resultado de cambiar en lote los permisos de un rol: cuántos se agregaron y
 * quitaron de verdad (los repetidos no cuentan) y los ids que el rol tiene ahora.
 */
public record RolPermisosLoteResponse(
        Long rolId,
        int agregados,
        int quitados,
        List<Long> asignados) {
}
