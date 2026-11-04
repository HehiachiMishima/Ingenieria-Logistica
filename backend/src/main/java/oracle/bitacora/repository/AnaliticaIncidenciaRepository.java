package oracle.bitacora.repository;

import java.util.List;
import oracle.bitacora.request.FiltroIncidencia;
import oracle.bitacora.response.AnaliticaBitacoraResponse.Grupo;
import oracle.bitacora.response.AnaliticaBitacoraResponse.Kpis;
import oracle.bitacora.response.AnaliticaBitacoraResponse.Tipo;
import oracle.common.response.CeldaMatriz;
import oracle.common.config.ZonaNegocio;
import oracle.common.support.CondicionSql;
import oracle.common.support.Periodos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Agregados analiticos de {@code incidencias} resueltos en PostgreSQL (GROUP BY).
 * Se agrupa SIEMPRE por los ids (enteros) y los nombres se unen despues sobre
 * el resultado ya agregado: los nombres usan la colacion comun.ci (ICU) y
 * agrupar por ellos fila a fila costaba ~10 veces mas (2,2 s vs 0,19 s en 1 M).
 * Todas las consultas comparten el WHERE de {@link CondicionIncidencia}, de modo
 * que respetan exactamente los filtros del listado y aprovechan sus indices.
 * Las columnas y tablas de agrupacion son constantes internas (sin inyeccion).
 */
@Repository
public class AnaliticaIncidenciaRepository {

    private final JdbcTemplate jdbc;
    private final ZonaNegocio zona;

    public AnaliticaIncidenciaRepository(JdbcTemplate jdbc, ZonaNegocio zona) {
        this.jdbc = jdbc;
        this.zona = zona;
    }

    public Kpis kpis(FiltroIncidencia filtro) {
        CondicionSql c = CondicionIncidencia.de(filtro, zona);
        // Primero por (sistema, centro) -unas decenas de grupos- y luego el total:
        // evita el COUNT(DISTINCT) sobre todas las filas (ordenarlas). AVG = SUM / COUNT.
        String sql = "SELECT COALESCE(SUM(g.n), 0) AS total, "
                + "COALESCE(SUM(g.resueltas), 0) AS resueltas, "
                + "COALESCE(SUM(g.segundos), 0) AS segundos, "
                + "COALESCE(ROUND(SUM(g.segundos)::numeric / NULLIF(SUM(g.resueltas), 0)), 0) AS mttr, "
                + "COUNT(DISTINCT g.id_data_sistema) AS sistemas, "
                + "COUNT(DISTINCT g.id_data_centro) AS centros "
                + "FROM (SELECT i.id_data_sistema, i.id_data_centro, COUNT(*) AS n, "
                + "COUNT(i.tiempo_solucion_seg) AS resueltas, SUM(i.tiempo_solucion_seg) AS segundos "
                + "FROM incidencias i " + c.where() + " GROUP BY i.id_data_sistema, i.id_data_centro) g";
        Kpis actual = jdbc.queryForObject(sql, (rs, n) -> new Kpis(
                rs.getLong("total"), rs.getLong("resueltas"),
                rs.getLong("segundos"), rs.getLong("mttr"), rs.getLong("sistemas"),
                rs.getLong("centros"), null), c.argumentos());
        FiltroIncidencia anterior = periodoAnterior(filtro);
        if (actual == null || anterior == null) {
            return actual;
        }
        CondicionSql ca = CondicionIncidencia.de(anterior, zona);
        Long previo = jdbc.queryForObject("SELECT COUNT(*) FROM incidencias i " + ca.where(), Long.class,
                ca.argumentos());
        return new Kpis(actual.total(), actual.resueltas(), actual.segundosSolucion(),
                actual.mttrSegundos(), actual.sistemasAfectados(), actual.centrosAfectados(), previo);
    }

    public List<Grupo> porSistema(FiltroIncidencia filtro) {
        return agrupar(filtro, "data_sistema", "sistema", "i.id_data_sistema");
    }

    public List<Grupo> porCentro(FiltroIncidencia filtro) {
        return agrupar(filtro, "data_centro", "centro", "i.id_data_centro");
    }

    public List<Grupo> porArea(FiltroIncidencia filtro) {
        return agrupar(filtro, "data_area", "area", "i.id_data_area");
    }

    public List<Tipo> porTipo(FiltroIncidencia filtro) {
        CondicionSql c = CondicionIncidencia.de(filtro, zona);
        String sql = "SELECT COALESCE(t.incidencia, '—') AS tipo, COALESCE(s.sistema, '—') AS sistema, "
                + "a.total, a.mttr "
                + "FROM (SELECT i.id_data_incidencia, i.id_data_sistema, COUNT(*) AS total, "
                + "COALESCE(ROUND(AVG(i.tiempo_solucion_seg)), 0) AS mttr FROM incidencias i "
                + c.where()
                + " GROUP BY i.id_data_incidencia, i.id_data_sistema) a "
                + "LEFT JOIN data_incidencia t ON t.id = a.id_data_incidencia "
                + "LEFT JOIN data_sistema s ON s.id = a.id_data_sistema "
                + "ORDER BY a.total DESC, tipo ASC";
        return jdbc.query(sql, (rs, n) -> new Tipo(rs.getString("tipo"), rs.getString("sistema"),
                rs.getLong("total"), rs.getLong("mttr")), c.argumentos());
    }

    public List<CeldaMatriz> centroPorSistema(FiltroIncidencia filtro) {
        CondicionSql c = CondicionIncidencia.de(filtro, zona);
        String sql = "SELECT COALESCE(ce.centro, '—') AS fila, COALESCE(s.sistema, '—') AS columna, a.valor "
                + "FROM (SELECT i.id_data_centro, i.id_data_sistema, COUNT(*) AS valor FROM incidencias i "
                + c.where()
                + " GROUP BY i.id_data_centro, i.id_data_sistema) a "
                + "LEFT JOIN data_centro ce ON ce.id = a.id_data_centro "
                + "LEFT JOIN data_sistema s ON s.id = a.id_data_sistema";
        return jdbc.query(sql, (rs, n) -> new CeldaMatriz(rs.getString("fila"), rs.getString("columna"),
                rs.getLong("valor")), c.argumentos());
    }

    private List<Grupo> agrupar(FiltroIncidencia filtro, String tabla, String columna, String fk) {
        CondicionSql c = CondicionIncidencia.de(filtro, zona);
        String sql = "SELECT COALESCE(t." + columna + ", '—') AS etiqueta, a.total, a.mttr "
                + "FROM (SELECT " + fk + " AS id, COUNT(*) AS total, "
                + "COALESCE(ROUND(AVG(i.tiempo_solucion_seg)), 0) AS mttr FROM incidencias i "
                + c.where()
                + " GROUP BY " + fk + ") a "
                + "LEFT JOIN " + tabla + " t ON t.id = a.id "
                + "ORDER BY a.total DESC, etiqueta ASC";
        return jdbc.query(sql, (rs, n) -> new Grupo(rs.getString("etiqueta"), rs.getLong("total"),
                rs.getLong("mttr")), c.argumentos());
    }

    /** Mismo filtro desplazado al periodo anterior de igual duracion (requiere desde y hasta). */
    private static FiltroIncidencia periodoAnterior(FiltroIncidencia f) {
        Periodos.Rango previo = f == null ? null : Periodos.anterior(f.desde(), f.hasta());
        if (previo == null) {
            return null;
        }
        return new FiltroIncidencia(f.centro(), f.sistema(), f.area(), f.responsable(), f.tipo(),
                previo.desde(), previo.hasta(), f.texto());
    }
}
