package oracle.admin.response;

import java.util.List;

/**
 * Resultado de cambiar en lote las excepciones de un usuario: cuántas se concedieron, denegaron y
 * devolvieron a la herencia del rol de verdad (lo que ya estaba así no cuenta) y las excepciones
 * que tiene ahora.
 */
public record UsuarioPermisosLoteResponse(
        Long usuarioId,
        int concedidos,
        int denegados,
        int heredados,
        List<ExcepcionPermisoResponse> excepciones) {
}
