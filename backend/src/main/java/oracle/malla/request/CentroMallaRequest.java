package oracle.malla.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Todo lo que necesita la malla de un centro: se guarda completo (reemplaza
 * turnos, flujos, olas, zonas y dotación). Las horas van como "HH:mm" y los
 * pasos de cada proceso se ordenan según su posición en la lista.
 */
public record CentroMallaRequest(
        @NotBlank @Size(max = 20) String codigo,
        @NotBlank @Size(max = 80) String nombre,
        @NotNull @Size(max = 12) List<@Valid @NotNull Turno> turnos,
        @NotNull @Size(max = 400) List<@Valid @NotNull Flujo> flujos,
        @NotNull @Size(max = 400) List<@Valid @NotNull Ola> olas,
        @NotNull @Size(max = 100) List<@Valid @NotNull Zona> zonas,
        @NotNull @Valid Capacidad capacidad,
        @NotNull @Size(max = 200) List<@Valid @NotNull Dotacion> dotacion,
        @NotNull @Valid DotacionReal dotacionReal) {

    /** Hora del día "HH:mm" (00:00–23:59). */
    public static final String HORA = "^([01]\\d|2[0-3]):[0-5]\\d$";
    public static final String PROCESO = "^(REC|ALM|REA|OLA|DES)$";

    public record Turno(
            @NotBlank @Size(max = 8) String codigo,
            @NotBlank @Size(max = 40) String nombre,
            @NotNull @Pattern(regexp = HORA) String inicio,
            @NotNull @Pattern(regexp = HORA) String fin,
            @Pattern(regexp = HORA) String refrigerio,
            @Size(max = 24) String tipoRefrigerio,
            @Size(max = 200) String nota,
            boolean confirmado) {
    }

    /** Un paso de un proceso: IL (Ingeniería Logística) u OPS (Operaciones). */
    public record Flujo(
            @NotNull @Pattern(regexp = PROCESO) String proceso,
            @NotNull @Pattern(regexp = "^(IL|OPS)$") String responsable,
            @NotNull @Pattern(regexp = HORA) String inicio,
            @Pattern(regexp = HORA) String fin,
            @NotBlank @Size(max = 200) String actividad,
            boolean confirmado) {
    }

    public record Ola(
            @NotBlank @Size(max = 10) String cluster,
            @NotBlank @Size(max = 20) String nombre,
            @Pattern(regexp = HORA) String caidaPedidos,
            @Pattern(regexp = HORA) String lanzamiento,
            @Pattern(regexp = HORA) String cierrePicking,
            @Pattern(regexp = HORA) String despacho,
            boolean confirmado,
            @Size(max = 320) String nota) {
    }

    /** Zona de almacenamiento y su productividad en líneas por hora. */
    public record Zona(
            @NotBlank @Size(max = 80) String nombre,
            @PositiveOrZero Integer lphReal,
            @PositiveOrZero Integer lphDiseno) {
    }

    /** Capacidad diaria declarada; null = por definir. */
    public record Capacidad(
            @PositiveOrZero Integer unidadesDia,
            @PositiveOrZero Integer lineasDia,
            @PositiveOrZero Integer bultosRecepcionDia,
            @PositiveOrZero Integer proveedoresDia) {
    }

    /** Personas planificadas de un proceso en un turno (por código de turno). */
    public record Dotacion(
            @NotBlank @Size(max = 8) String turno,
            @NotNull @Pattern(regexp = PROCESO) String proceso,
            @PositiveOrZero Integer personas) {
    }

    /** Personas reales por flujo en la semana de la captura. */
    public record DotacionReal(
            @PositiveOrZero Integer inbound,
            @PositiveOrZero Integer outbound,
            @PositiveOrZero Integer despacho,
            @PositiveOrZero Integer total,
            @Size(max = 10) String semana) {
    }
}
