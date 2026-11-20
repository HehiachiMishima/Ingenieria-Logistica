package oracle.admin.response;

import java.util.List;

/**
 * Tablero de permisos individuales de un usuario. {@code bloqueo} explica por qué quien edita no
 * puede cambiar sus excepciones (usuario protegido, él mismo o con más permisos); null si puede.
 */
public record PermisosUsuarioTableroResponse(
        Long usuarioId,
        String bloqueo,
        List<PermisoDelUsuarioResponse> permisos) {
}
