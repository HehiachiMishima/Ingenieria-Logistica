package oracle.common.support;

import java.util.List;

/**
 * Fragmento WHERE parametrizado ({@code ?}) y sus argumentos, construido a
 * partir de un filtro. Solo contiene condiciones realmente informadas para que
 * PostgreSQL pueda usar los indices compuestos (id_data_* + creado_en).
 */
public record CondicionSql(String where, List<Object> args) {

    public Object[] argumentos() {
        return args.toArray();
    }
}
