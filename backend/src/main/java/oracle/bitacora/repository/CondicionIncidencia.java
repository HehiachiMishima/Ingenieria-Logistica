package oracle.bitacora.repository;

import java.util.ArrayList;
import java.util.List;
import oracle.bitacora.request.FiltroIncidencia;
import oracle.common.config.ZonaNegocio;
import oracle.common.support.CondicionSql;
import oracle.common.support.Filtros;

/** WHERE dinamico de la tabla {@code incidencias} (alias {@code i}) a partir del filtro. */
final class CondicionIncidencia {

    /** Los dias del filtro son dias del negocio; se comparan como instantes UTC. */
    static CondicionSql de(FiltroIncidencia filtro, ZonaNegocio zona) {
        List<String> condiciones = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (filtro != null) {
            if (filtro.centro() != null) {
                condiciones.add("i.id_data_centro = ?");
                args.add(filtro.centro());
            }
            if (filtro.sistema() != null) {
                condiciones.add("i.id_data_sistema = ?");
                args.add(filtro.sistema());
            }
            if (filtro.area() != null) {
                condiciones.add("i.id_data_area = ?");
                args.add(filtro.area());
            }
            if (filtro.responsable() != null) {
                condiciones.add("i.id_data_responsable = ?");
                args.add(filtro.responsable());
            }
            if (filtro.tipo() != null) {
                condiciones.add("i.id_data_incidencia = ?");
                args.add(filtro.tipo());
            }
            if (filtro.desde() != null) {
                condiciones.add("i.creado_en >= ?");
                args.add(zona.inicioDelDiaSql(filtro.desde()));
            }
            if (filtro.hasta() != null) {
                condiciones.add("i.creado_en < ?");
                args.add(zona.inicioDelDiaSql(filtro.hasta().plusDays(1)));
            }
            if (filtro.texto() != null && !filtro.texto().isBlank()) {
                condiciones.add("(LOWER(i.ticket) LIKE ? OR LOWER(i.descripcion) LIKE ?)");
                String patron = "%" + Filtros.escaparLike(filtro.texto().trim().toLowerCase()) + "%";
                args.add(patron);
                args.add(patron);
            }
        }
        String where = condiciones.isEmpty() ? "" : "WHERE " + String.join(" AND ", condiciones);
        return new CondicionSql(where, args);
    }

    private CondicionIncidencia() {
    }
}
