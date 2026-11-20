package oracle.admin.response;

import java.util.List;

/**
 * Resultado de cambiar en lote los roles de un usuario: cuántos se agregaron y quitaron de
 * verdad (los repetidos no cuentan) y los ids de los roles que tiene ahora.
 */
public record UsuarioRolesLoteResponse(
        Long usuarioId,
        int agregados,
        int quitados,
        List<Long> asignados) {
}
