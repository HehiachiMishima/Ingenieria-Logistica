package oracle.admin.response;

import java.time.Instant;
import oracle.admin.model.Usuario;

/** Salida publica de usuario. */
public record UsuarioResponse(
        Long id,
        String usuario,
        String nombre,
        String apellido,
        String email,
        String telefono,
        Boolean activo,
        Instant creadoEn,
        Instant actualizadoEn,
        Long creadoPor,
        Long actualizadoPor) {

    public static UsuarioResponse of(Usuario u) {
        return new UsuarioResponse(
                u.getId(), u.getUsuario(), u.getNombre(), u.getApellido(), u.getEmail(),
                u.getTelefono(), u.getActivo(), u.getCreadoEn(), u.getActualizadoEn(),
                u.getCreadoPor(), u.getActualizadoPor());
    }
}
