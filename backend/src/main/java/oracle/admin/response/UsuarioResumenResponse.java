package oracle.admin.response;

import java.util.List;

/** Roles asignados y permisos efectivos (deny-wins) de un usuario, para el listado. */
public record UsuarioResumenResponse(Long usuarioId, List<String> roles, List<String> permisos) {
}
