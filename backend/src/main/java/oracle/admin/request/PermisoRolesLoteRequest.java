package oracle.admin.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Da (agregar) o quita un permiso a varios roles en una sola transacción. Cada lista admite hasta
 * {@value #MAXIMO} ids de rol; omitir una equivale a enviarla vacía.
 */
public record PermisoRolesLoteRequest(
        @Size(max = MAXIMO, message = "Se puede dar el permiso a hasta 200 roles por vez.")
        List<@NotNull(message = "Los ids de rol no pueden ser nulos.") Long> agregar,
        @Size(max = MAXIMO, message = "Se puede quitar el permiso a hasta 200 roles por vez.")
        List<@NotNull(message = "Los ids de rol no pueden ser nulos.") Long> quitar) {

    public static final int MAXIMO = 200;

    public PermisoRolesLoteRequest {
        agregar = agregar == null ? List.of() : agregar;
        quitar = quitar == null ? List.of() : quitar;
    }
}
