package oracle.common.support;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Construccion segura de las paginas que piden los listados paginados. */
public final class Paginacion {

    /** Orden por defecto de los listados: mas recientes primero, desempate por id. */
    public static final Sort RECIENTES =
            Sort.by(Sort.Direction.DESC, "creadoEn").and(Sort.by(Sort.Direction.DESC, "id"));

    /**
     * Pageable a partir de la pagina 1-based del cliente: la pagina minima es 1
     * y el tamano se acota a [1, maximo] para que nadie pida la tabla completa.
     */
    public static PageRequest pagina(int pagina, int tamano, int maximo, Sort orden) {
        return PageRequest.of(Math.max(pagina, 1) - 1, Math.min(Math.max(tamano, 1), maximo), orden);
    }

    private Paginacion() {
    }
}
