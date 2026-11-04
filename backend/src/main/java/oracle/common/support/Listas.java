package oracle.common.support;

import org.slf4j.Logger;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Tope de filas para los listados que devuelven un array completo (catalogos y
 * usuarios) en lugar de una pagina.
 *
 * Las consultas se limitan en el servidor (LIMIT) para que un listado
 * inesperadamente grande nunca agote memoria; el contrato de respuesta sigue
 * siendo un array, asi que no se rompe el frontend. Si se alcanza el tope, se
 * registra una advertencia para que el operador sepa que el listado quedo
 * truncado y pueda usar un filtro o una variante paginada (/v2).
 */
public final class Listas {

    public static final int MAX_FILAS = 5_000;

    /** Pageable acotado al tope (sin orden explicito). */
    public static PageRequest limitado() {
        return PageRequest.of(0, MAX_FILAS);
    }

    /** Pageable acotado al tope con orden. */
    public static PageRequest limitado(Sort orden) {
        return PageRequest.of(0, MAX_FILAS, orden);
    }

    public static void avisarSiCorto(Logger log, int tamano, String origen) {
        if (tamano >= MAX_FILAS) {
            log.warn("{} devolvio el tope de {} filas: el listado quedo truncado. "
                    + "Use un filtro o una variante paginada.", origen, MAX_FILAS);
        }
    }

    private Listas() {
    }
}
