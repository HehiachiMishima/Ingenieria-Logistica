package oracle.bitacora.service;

import oracle.bitacora.repository.ReferenciasIncidencia;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.RecursoNoEncontradoException;

/**
 * Reglas puras de coherencia de una incidencia contra sus catalogos (las FK
 * compuestas del esquema): el tipo pertenece al sistema, el responsable al
 * area y el ticket es unico. Recibe lo ya leido de la base, no consulta nada.
 */
final class ValidacionIncidencia {

    static void validar(Long idCentro, Long idSistema, Long idTipo, Long idArea, Long idResponsable,
            String ticket, boolean edicion, ReferenciasIncidencia ref) {
        if (!ref.centroExiste()) {
            throw new RecursoNoEncontradoException("No existe el centro con id " + idCentro + ".");
        }
        if (!ref.sistemaExiste()) {
            throw new RecursoNoEncontradoException("No existe el sistema con id " + idSistema + ".");
        }
        if (!ref.areaExiste()) {
            throw new RecursoNoEncontradoException("No existe el área con id " + idArea + ".");
        }
        if (ref.sistemaDelTipo() == null) {
            throw new RecursoNoEncontradoException("No existe el tipo de incidencia con id " + idTipo + ".");
        }
        if (!idSistema.equals(ref.sistemaDelTipo())) {
            throw new ConflictoException("El tipo de incidencia con id " + idTipo
                    + " no pertenece al sistema con id " + idSistema + ".");
        }
        if (ref.areaDelResponsable() == null) {
            throw new RecursoNoEncontradoException("No existe el responsable con id " + idResponsable + ".");
        }
        if (!idArea.equals(ref.areaDelResponsable())) {
            throw new ConflictoException("El responsable con id " + idResponsable
                    + " no pertenece al área con id " + idArea + ".");
        }
        if (ref.ticketOcupado()) {
            throw new ConflictoException(edicion
                    ? "Ya existe otra incidencia con el ticket '" + ticket + "'."
                    : "Ya existe una incidencia con el ticket '" + ticket + "'.");
        }
    }

    private ValidacionIncidencia() {
    }
}
