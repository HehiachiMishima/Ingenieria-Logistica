package oracle.bitacora.repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import oracle.bitacora.repository.CondicionesIncidencia.Condicion;
import oracle.bitacora.repository.CondicionesIncidencia.Desde;
import oracle.bitacora.repository.CondicionesIncidencia.Estado;
import oracle.bitacora.repository.CondicionesIncidencia.Hasta;
import oracle.bitacora.repository.CondicionesIncidencia.Igual;
import oracle.bitacora.repository.CondicionesIncidencia.Texto;
import oracle.bitacora.request.EstadoIncidencia;
import oracle.bitacora.request.FiltroIncidencia;
import oracle.common.config.ZonaNegocio;
import oracle.common.support.CondicionSql;

/**
 * WHERE dinamico de la tabla {@code incidencias} (alias {@code i}) a partir del
 * filtro. Traduce las condiciones de {@link CondicionesIncidencia}, las mismas
 * que usa el listado: la analitica y la lista nunca filtran distinto.
 */
final class CondicionIncidencia {

    /** Los dias del filtro son dias del negocio; se comparan como instantes UTC. */
    static CondicionSql de(FiltroIncidencia filtro, ZonaNegocio zona) {
        List<String> condiciones = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Condicion c : CondicionesIncidencia.de(filtro, zona)) {
            switch (c) {
                case Igual igual -> {
                    condiciones.add(igual.columna() + " = ?");
                    args.add(igual.valor());
                }
                case Desde desde -> {
                    condiciones.add("i.creado_en >= ?");
                    args.add(Timestamp.from(desde.instante()));
                }
                case Hasta hasta -> {
                    condiciones.add("i.creado_en < ?");
                    args.add(Timestamp.from(hasta.instante()));
                }
                case Texto texto -> {
                    condiciones.add("(LOWER(i.ticket) LIKE ? ESCAPE '\\' OR LOWER(i.descripcion) LIKE ? ESCAPE '\\')");
                    args.add(texto.patron());
                    args.add(texto.patron());
                }
                case Estado estado -> condiciones.add(estado.estado() == EstadoIncidencia.ABIERTA
                        ? "i.cerrado_en IS NULL" : "i.cerrado_en IS NOT NULL");
            }
        }
        String where = condiciones.isEmpty() ? "" : "WHERE " + String.join(" AND ", condiciones);
        return new CondicionSql(where, args);
    }

    private CondicionIncidencia() {
    }
}
