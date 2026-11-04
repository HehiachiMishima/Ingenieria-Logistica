package oracle.simulacion.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistroActualizarRequest(
    @NotBlank @Size(max = 150) String nombreRegistro,
    @Size(max = 50) String etiqueta,
    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "El color debe tener formato #RRGGBB.") String colorHex) {}
