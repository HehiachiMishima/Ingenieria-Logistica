package oracle.bitacora.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Alta de incidencia. Fecha y horas van en hora de negocio: las horas se
 * guardan tal cual y la fecha + hora de inicio se registra como creado_en
 * (instante UTC).
 */
public record IncidenciaCrearRequest(
        @NotNull(message = "El centro es obligatorio.") Long idDataCentro,
        @NotNull(message = "El sistema es obligatorio.") Long idDataSistema,
        @NotNull(message = "El tipo de incidencia es obligatorio.") Long idDataIncidencia,
        @NotBlank(message = "El ticket es obligatorio.")
        @Size(max = 59, message = "El ticket no debe superar 59 caracteres.") String ticket,
        @NotNull(message = "El área es obligatoria.") Long idDataArea,
        @NotNull(message = "El responsable es obligatorio.") Long idDataResponsable,
        @NotBlank(message = "La descripción es obligatoria.")
        @Size(max = 255, message = "La descripción no debe superar 255 caracteres.") String descripcion,
        @NotNull(message = "La hora de inicio es obligatoria.") LocalTime horaInicio,
        @NotNull(message = "La hora de fin es obligatoria.") LocalTime horaFin,
        @NotNull(message = "La fecha es obligatoria.") LocalDate fecha) {
}
