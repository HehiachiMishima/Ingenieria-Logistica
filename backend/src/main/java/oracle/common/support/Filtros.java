package oracle.common.support;

import org.springframework.data.jpa.domain.Specification;

/** Utilidades para filtros de listado. */
public final class Filtros {

    /**
     * Normaliza un texto de filtro: recorta y convierte vacio en null,
     * para que el repository lo ignore en el LIKE.
     */
    public static String normalizar(String valor) {
        if (valor == null) {
            return null;
        }
        String recortado = valor.trim();
        return recortado.isEmpty() ? null : recortado;
    }

    /**
     * Condicion "el campo contiene el texto" sin distinguir mayusculas; si el
     * texto llega vacio no filtra.
     */
    public static <E> Specification<E> contiene(String campo, String texto) {
        String termino = normalizar(texto);
        return (root, query, cb) -> termino == null
                ? cb.conjunction()
                : cb.like(cb.lower(root.get(campo)), "%" + termino.toLowerCase() + "%");
    }

    /**
     * Escapa los comodines de LIKE (\\, % y _) para que el texto del usuario se
     * busque literal. Usar con ESCAPE '\\' (el predeterminado de PostgreSQL).
     */
    public static String escaparLike(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Filtros() {
    }
}
