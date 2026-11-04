package oracle.bitacora.repository;

/**
 * Estado de las referencias de una incidencia leido en una sola consulta:
 * existencia de centro, sistema y area, a que sistema pertenece el tipo y a
 * que area el responsable (null = no existen) y si el ticket ya esta en uso.
 */
public record ReferenciasIncidencia(
        boolean centroExiste,
        boolean sistemaExiste,
        boolean areaExiste,
        Long sistemaDelTipo,
        Long areaDelResponsable,
        boolean ticketOcupado) {
}
