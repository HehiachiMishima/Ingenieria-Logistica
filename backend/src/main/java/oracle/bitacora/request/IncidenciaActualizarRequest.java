package oracle.bitacora.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Edición de incidencia (reemplazo completo de los campos editables).
 * La fecha de creación se recompone con la hora de inicio.
 */
public record IncidenciaActualizarRequest(
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
        /** Opcional: sin hora de fin la incidencia queda abierta. */
        LocalTime horaFin,
        @NotNull(message = "La fecha es obligatoria.") LocalDate fecha,
        /** Opcional: si falta y hay hora de fin, se deduce (fin < inicio = día siguiente). */
        LocalDate fechaFin,
        /** Ficha {@code version} de la incidencia tal como se leyo: si cambio, 409. */
        @NotNull(message = "Falta la versión del registro: recargue la página e intente de nuevo.")
        Instant version) {
}
