package oracle.common.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Puerto con el que la capa de seguridad obtiene el estado y los permisos
 * finales de un usuario sin depender del modulo admin (que lo implementa).
 */
public interface FuenteAutorizacion {

    /** Estado de la cuenta y nombres de permisos permitidos (vista final, deny-overrides). */
    /**
     * @param credencialesDesde ultimo cambio de contrasena: los tokens emitidos
     *        antes dejan de valer.
     */
    record Identidad(boolean activo, String usuario, List<String> permisos, Instant credencialesDesde) {
    }

    /** Identidad del usuario, o vacio si no existe. */
    Optional<Identidad> buscar(Long usuarioId);
}
