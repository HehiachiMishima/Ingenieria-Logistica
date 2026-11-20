package oracle.admin.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Cambia varios roles de un usuario en una sola transacción. Cada lista admite hasta
 * {@value #MAXIMO} ids; omitir una equivale a enviarla vacía.
 */
public record UsuarioRolesLoteRequest(
        @Size(max = MAXIMO, message = "Se pueden agregar hasta 200 roles por vez.")
        List<@NotNull(message = "Los ids de rol no pueden ser nulos.") Long> agregar,
        @Size(max = MAXIMO, message = "Se pueden quitar hasta 200 roles por vez.")
        List<@NotNull(message = "Los ids de rol no pueden ser nulos.") Long> quitar) {

    public static final int MAXIMO = 200;

    public UsuarioRolesLoteRequest {
        agregar = agregar == null ? List.of() : agregar;
        quitar = quitar == null ? List.of() : quitar;
    }
}
