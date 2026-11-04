package oracle.admin.request;

import jakarta.validation.constraints.Size;

/** Actualizacion parcial de rol: campos null = no se tocan. */
public record RolActualizarRequest(
        @Size(max = 50) String nombre,
        @Size(max = 200) String descripcion,
        Boolean activo) {
}
