package oracle.common.support;

import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;

/**
 * Cursor (keyset) para recorrer una tabla en el orden {@link Paginacion#RECIENTES}
 * (creado_en DESC, id DESC) sin OFFSET: la siguiente pagina son las filas
 * "anteriores" a la ultima leida. PostgreSQL lo resuelve como rango sobre los indices
 * (..., creado_en) + id, asi que cada lote cuesta lo mismo sin importar cuantas
 * filas se hayan leido antes.
 */
public final class CursorRecientes {

    /**
     * Filas que van despues de (creadoEn, id) en el orden RECIENTES:
     * {@code creado_en <= x AND (creado_en < x OR id < y)}. Es lo mismo que
     * {@code creado_en < x OR (creado_en = x AND id < y)}, pero el primer termino
     * le da al indice el punto de partida: con el OR solo, PostgreSQL recorria el
     * indice desde el principio y descartaba todas las filas ya leidas (cada lote
     * mas lento que el anterior; exportar 1 M de filas tardaba 128 s).
     */
    public static <E> Specification<E> despuesDe(Instant creadoEn, Long id) {
        return (root, query, cb) -> cb.and(
                cb.lessThanOrEqualTo(root.<Instant>get("creadoEn"), creadoEn),
                cb.or(
                        cb.lessThan(root.<Instant>get("creadoEn"), creadoEn),
                        cb.lessThan(root.<Long>get("id"), id)));
    }

    private CursorRecientes() {
    }
}
