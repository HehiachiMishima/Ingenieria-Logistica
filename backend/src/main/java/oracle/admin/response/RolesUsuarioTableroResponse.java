package oracle.admin.response;

import java.util.List;

/**
 * Tablero de roles de un usuario. {@code bloqueo} explica por qué quien edita no puede cambiar
 * los roles de este usuario (usuario protegido, él mismo o con más permisos); null si puede.
 */
public record RolesUsuarioTableroResponse(
        Long usuarioId,
        String bloqueo,
        List<RolTableroResponse> roles) {
}
