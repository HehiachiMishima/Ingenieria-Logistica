package oracle.simulacionsanta.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Nuevo nombre de una carga. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistroNombreRequest(
    @NotBlank(message = "Indica el nombre.")
        @Size(max = 150, message = "El nombre admite hasta 150 caracteres.")
        String nombre) {}
