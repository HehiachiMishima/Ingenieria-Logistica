package oracle.common.response;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Envoltorio de paginacion. El cliente recibe solo la pagina solicitada mas
 * los totales necesarios para dibujar el paginador, sin descargar la tabla
 * completa.
 */
public record PaginaResponse<T>(
        List<T> contenido,
        long total,
        int pagina,
        int tamano,
        int totalPaginas) {

    public static <T> PaginaResponse<T> of(List<T> contenido, long total, int pagina, int tamano) {
        int totalPaginas = tamano <= 0 ? 0 : (int) ((total + tamano - 1) / tamano);
        return new PaginaResponse<>(contenido, total, pagina, tamano, totalPaginas);
    }

    public static <E, T> PaginaResponse<T> de(Page<E> pagina, List<T> contenido) {
        return of(contenido, pagina.getTotalElements(), pagina.getNumber() + 1, pagina.getSize());
    }
}
