package oracle.admin.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Cambia varios permisos de un rol en una sola transacción. Cada lista admite
 * hasta {@value #MAXIMO} ids; omitir una equivale a enviarla vacía.
 */
public record RolPermisosLoteRequest(
        @Size(max = MAXIMO, message = "Se pueden agregar hasta 500 permisos por vez.")
        List<@NotNull(message = "Los ids de permiso no pueden ser nulos.") Long> agregar,
        @Size(max = MAXIMO, message = "Se pueden quitar hasta 500 permisos por vez.")
        List<@NotNull(message = "Los ids de permiso no pueden ser nulos.") Long> quitar) {

    public static final int MAXIMO = 500;

    public RolPermisosLoteRequest {
        agregar = agregar == null ? List.of() : agregar;
        quitar = quitar == null ? List.of() : quitar;
    }
}
