package oracle.auth;

import java.util.List;
import oracle.admin.response.UsuarioResponse;

/** Estado vigente de la sesion: identidad + permisos resueltos ahora mismo. */
public record SesionResponse(UsuarioResponse usuario, List<String> permisos) {
}
