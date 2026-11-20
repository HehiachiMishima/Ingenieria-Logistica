package oracle.admin.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Cambia varias excepciones de un usuario en una sola transacción: conceder, denegar o volver a
 * heredar del rol (quitar la excepción). Cada lista admite hasta {@value #MAXIMO} ids; omitir una
 * equivale a enviarla vacía.
 */
public record UsuarioPermisosLoteRequest(
        @Size(max = MAXIMO, message = "Se pueden conceder hasta 500 permisos por vez.")
        List<@NotNull(message = "Los ids de permiso no pueden ser nulos.") Long> conceder,
        @Size(max = MAXIMO, message = "Se pueden denegar hasta 500 permisos por vez.")
        List<@NotNull(message = "Los ids de permiso no pueden ser nulos.") Long> denegar,
        @Size(max = MAXIMO, message = "Se pueden devolver a la herencia hasta 500 permisos por vez.")
        List<@NotNull(message = "Los ids de permiso no pueden ser nulos.") Long> heredar) {

    public static final int MAXIMO = 500;

    public UsuarioPermisosLoteRequest {
        conceder = conceder == null ? List.of() : conceder;
        denegar = denegar == null ? List.of() : denegar;
        heredar = heredar == null ? List.of() : heredar;
    }
}
