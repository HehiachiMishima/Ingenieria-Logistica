package oracle.bitacora.repository;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import oracle.bitacora.request.FiltroIncidencia;
import oracle.bitacora.response.AnaliticaBitacoraResponse.FilaEjecutiva;
import oracle.bitacora.response.AnaliticaBitacoraResponse.Grupo;
import oracle.bitacora.response.AnaliticaBitacoraResponse.Kpis;
import oracle.bitacora.response.AnaliticaBitacoraResponse.Reincidencia;
import oracle.bitacora.response.AnaliticaBitacoraResponse.Repetido;
import oracle.bitacora.response.AnaliticaBitacoraResponse.Tipo;
import oracle.bitacora.response.CasoLargoResponse;
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

    /** Dias hacia atras en que un mismo tipo en el mismo centro cuenta como reincidencia. */
    public static final int VENTANA_REINCIDENCIA_DIAS = 7;

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

    /** Cuadro ejecutivo: casos, horas, MTTR y caso mas largo por sistema. */
    public List<FilaEjecutiva> ejecutivoPorSistema(FiltroIncidencia filtro) {
        CondicionSql c = CondicionIncidencia.de(filtro, zona);
        String sql = "SELECT a.id, COALESCE(s.sistema, '—') AS etiqueta, a.total, a.segundos, a.mttr, a.maximo "
                + "FROM (SELECT i.id_data_sistema AS id, COUNT(*) AS total, "
                + "COALESCE(SUM(i.tiempo_solucion_seg), 0) AS segundos, "
                + "COALESCE(ROUND(AVG(i.tiempo_solucion_seg)), 0) AS mttr, "
                + "COALESCE(MAX(i.tiempo_solucion_seg), 0) AS maximo FROM incidencias i "
                + c.where()
                + " GROUP BY i.id_data_sistema) a "
                + "LEFT JOIN data_sistema s ON s.id = a.id "
                + "ORDER BY a.segundos DESC, a.total DESC, etiqueta ASC";
        return jdbc.query(sql, (rs, n) -> new FilaEjecutiva(rs.getLong("id"), rs.getString("etiqueta"),
                rs.getLong("total"), rs.getLong("segundos"), rs.getLong("mttr"), rs.getLong("maximo")),
                c.argumentos());
    }

    /**
     * Reincidencias dentro del filtro: LAG por (centro, tipo) ordenado por fecha.
     * Agrupa primero por (centro, tipo) y solo despues une los nombres (ver nota
     * de la clase). Devuelve totales y las {@code limite} combinaciones que mas reinciden.
     */
    public Reincidencia reincidencias(FiltroIncidencia filtro, int limite) {
        CondicionSql c = CondicionIncidencia.de(filtro, zona);
        String sql = "WITH x AS (SELECT i.id_data_centro AS c, i.id_data_incidencia AS t, i.id_data_sistema AS s, "
                + "COALESCE(i.creado_en - LAG(i.creado_en) OVER (PARTITION BY i.id_data_centro, i.id_data_incidencia "
                + "ORDER BY i.creado_en, i.id) <= INTERVAL '" + VENTANA_REINCIDENCIA_DIAS + " days', false) AS rep "
                + "FROM incidencias i " + c.where() + "), "
                + "g AS (SELECT c, t, s, COUNT(*) AS casos, COUNT(*) FILTER (WHERE rep) AS reps FROM x GROUP BY c, t, s), "
                + "r AS (SELECT g.*, SUM(casos) OVER () AS total, SUM(reps) OVER () AS total_reps FROM g "
                + "ORDER BY reps DESC, casos DESC LIMIT ?) "
                + "SELECT COALESCE(ce.centro, '—') AS centro, COALESCE(ti.incidencia, '—') AS tipo, "
                + "COALESCE(si.sistema, '—') AS sistema, r.casos, r.reps, r.total, r.total_reps FROM r "
                + "LEFT JOIN data_centro ce ON ce.id = r.c "
                + "LEFT JOIN data_incidencia ti ON ti.id = r.t "
                + "LEFT JOIN data_sistema si ON si.id = r.s "
                + "ORDER BY r.reps DESC, r.casos DESC, centro ASC, tipo ASC";
        Object[] args = new Object[c.argumentos().length + 1];
        System.arraycopy(c.argumentos(), 0, args, 0, c.argumentos().length);
        args[args.length - 1] = limite;
        long[] totales = new long[2];
        List<Repetido> repetidos = new ArrayList<>();
        jdbc.query(sql, rs -> {
            totales[0] = rs.getLong("total");
            totales[1] = rs.getLong("total_reps");
            if (rs.getLong("reps") > 0) {
                repetidos.add(new Repetido(rs.getString("centro"), rs.getString("tipo"), rs.getString("sistema"),
                        rs.getLong("casos"), rs.getLong("reps")));
            }
        }, args);
        return new Reincidencia(VENTANA_REINCIDENCIA_DIAS, totales[0], totales[1], repetidos);
    }

    /**
     * Incidencias resueltas mas largas del filtro (ORDER BY duracion LIMIT n):
     * PostgreSQL usa top-N heapsort sobre las filas del filtro, sin ordenar todo.
     */
    public List<CasoLargoResponse> casosMasLargos(FiltroIncidencia filtro, int limite) {
        CondicionSql c = CondicionIncidencia.de(filtro, zona);
        String where = c.where().isEmpty() ? "WHERE " : c.where() + " AND ";
        String sql = "SELECT a.ticket, a.descripcion, a.creado_en, a.hora_inicio, a.hora_fin, a.segundos, "
                + "COALESCE(t.incidencia, '—') AS tipo, COALESCE(ce.centro, '—') AS centro, "
                + "COALESCE(r.responsable, '—') AS responsable "
                + "FROM (SELECT i.id, i.ticket, i.descripcion, i.creado_en, i.hora_inicio, i.hora_fin, "
                + "i.tiempo_solucion_seg AS segundos, i.id_data_incidencia, i.id_data_centro, i.id_data_responsable "
                + "FROM incidencias i " + where + "i.tiempo_solucion_seg IS NOT NULL "
                + "ORDER BY i.tiempo_solucion_seg DESC, i.id DESC LIMIT ?) a "
                + "LEFT JOIN data_incidencia t ON t.id = a.id_data_incidencia "
                + "LEFT JOIN data_centro ce ON ce.id = a.id_data_centro "
                + "LEFT JOIN data_responsable r ON r.id = a.id_data_responsable "
                + "ORDER BY a.segundos DESC, a.id DESC";
        Object[] args = new Object[c.argumentos().length + 1];
        System.arraycopy(c.argumentos(), 0, args, 0, c.argumentos().length);
        args[args.length - 1] = limite;
        return jdbc.query(sql, (rs, n) -> new CasoLargoResponse(rs.getString("ticket"),
                rs.getString("descripcion"), rs.getString("tipo"), rs.getString("centro"),
                rs.getString("responsable"), zona.dia(rs.getTimestamp("creado_en").toInstant()),
                rs.getObject("hora_inicio", LocalTime.class), rs.getObject("hora_fin", LocalTime.class),
                rs.getLong("segundos")), args);
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
