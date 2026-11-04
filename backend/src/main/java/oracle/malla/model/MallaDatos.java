package oracle.malla.model;

import java.time.LocalTime;
import java.util.List;

/**
 * Fotografía de sólo lectura de la malla capturada. Se carga una vez por
 * evaluación y el evaluador de reglas trabaja en memoria, sin consultar la
 * base de datos por cada hallazgo.
 */
public record MallaDatos(
        List<Centro> centros,
        List<Turno> turnos,
        List<Flujo> flujos,
        List<Ola> olas,
        List<Zona> zonas,
        List<Capacidad> capacidades,
        List<Dotacion> dotaciones) {

    public record Centro(Long id, String codigo, String nombre) {
    }

    public record Turno(Long id, Long centroId, String codigo, String nombre,
            LocalTime inicio, LocalTime fin, LocalTime refrigerio, boolean confirmado) {
    }

    public record Flujo(Long id, Long centroId, String proceso, int orden, String responsable,
            LocalTime inicio, LocalTime fin, String actividad, boolean confirmado) {
    }

    public record Ola(Long id, Long centroId, String cluster, String nombre,
            LocalTime pedido, LocalTime lanzamiento, LocalTime cierre, LocalTime despacho,
            boolean confirmado) {
    }

    public record Zona(Long id, Long centroId, String nombre, Integer lphReal, Integer lphDiseno) {
    }

    public record Capacidad(Long centroId, Integer unidades, Integer lineas, Integer bultos,
            Integer proveedores) {
    }

    public record Dotacion(Long id, Long centroId, Long turnoId, String proceso, Integer personas) {
    }
}
