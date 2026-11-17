package oracle.simulacionsanta.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Envíos ya recibidos del registro: 0 (ninguno) a 3 (todos). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EnvioRequest(
    @NotNull(message = "Indica el envío.")
        @Min(value = 0, message = "El envío debe estar entre 0 y 3.")
        @Max(value = 3, message = "El envío debe estar entre 0 y 3.")
        Integer envio) {}
