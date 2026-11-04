package oracle.bitacora.request;

import java.time.LocalDate;
import oracle.common.exception.SolicitudInvalidaException;

/**
 * Filtros de busqueda del listado de incidencias. Todos los campos son
 * opcionales; un campo nulo no aplica la condicion correspondiente, de modo
 * que las consultas sin filtrar sigan aprovechando los indices por defecto.
 *
 * Las condiciones se construyen en el mismo orden que los indices compuestos
 * (id_data_* + creado_en) para que PostgreSQL pueda usarlos.
 */
public record FiltroIncidencia(
        Long centro,
        Long sistema,
        Long area,
        Long responsable,
        Long tipo,
        LocalDate desde,
        LocalDate hasta,
        String texto) {

    /** Largo maximo del texto de busqueda: frena patrones LIKE enormes. */
    public static final int MAX_TEXTO = 100;

    public FiltroIncidencia {
        if (texto != null && texto.length() > MAX_TEXTO) {
            throw new SolicitudInvalidaException("El texto de busqueda admite hasta " + MAX_TEXTO + " caracteres.");
        }
    }
}
