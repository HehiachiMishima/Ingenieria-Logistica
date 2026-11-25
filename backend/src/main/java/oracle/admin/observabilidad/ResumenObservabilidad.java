package oracle.admin.observabilidad;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import oracle.common.config.Transacciones;
import oracle.common.exception.SolicitudInvalidaException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * KPIs y series de Monitoreo, Actividad y Auditoría. Monitoreo y Actividad leen los
 * resúmenes por hora (bitacora.peticiones_hora, bitacora.actividad_hora), nunca las
 * trazas: el costo depende de horas × APIs, no del volumen de peticiones.
 * Los KPIs comparan ventanas móviles (últimos N días vs los N anteriores); las series
 * van por hora (24 h) o por día en la zona del negocio.
 */
@Service
public class ResumenObservabilidad {
    /** Monitoreo y Actividad comparan contra el período anterior: 2 × 30 días caben en los 90 que se guardan. */
    static final Set<Integer> PERIODOS = Set.of(1, 7, 30);
    static final Set<Integer> PERIODOS_AUDITORIA = Set.of(1, 7, 30, 90);
    static final int RETENCION_RESUMEN_DIAS = 100; // 12 semanas de adopción + la semana en curso
    private static final double PERCENTIL = 0.95;

    /** Horas por franja en los mapas de calor día × franja. */
    static final int HORAS_POR_FRANJA = 4;
    static final int SEMANAS_ADOPCION = 12;

    public record PuntoMonitoreo(String periodo, long peticiones, long errores, long p95) {}
    /** p95 de un día de la semana (1 = lunes) y franja de 4 h que empieza en {@code desde}. */
    public record CeldaMonitoreo(int dia, int desde, long peticiones, long p95) {}
    public record ResumenMonitoreo(long peticiones, long peticionesPrevias, long errores, long erroresPrevios,
                                   long erroresServidor, long erroresServidorPrevios, long p95, long p95Previo,
                                   List<PuntoMonitoreo> serie, List<CeldaMonitoreo> calor) {}
    /** {@code maxima}: petición más lenta del período en ms (0 = sin dato, horas anteriores a la columna). */
    public record EndpointMonitoreo(String metodo, String ruta, long peticiones, long errores,
                                    double porcentaje, long p95, long maxima) {}
    public record CeldaActividad(int dia, int hora, long acciones) {}
    public record PanelActividad(String panel, long usuarios, long acciones) {}
    public record PuntoActividad(String periodo, long usuarios, long acciones) {}
    public record ResumenActividad(long usuarios, long usuariosPrevios, long acciones, long accionesPrevias,
                                   List<CeldaActividad> calor, List<PanelActividad> paneles,
                                   List<PuntoActividad> serie, List<PuntoActividad> semanas) {}
    public record PuntoAuditoria(String periodo, long exito, long fallo, long denegado) {}
    public record Conteo(String nombre, long total) {}
    public record CeldaAuditoria(int dia, int hora, long total) {}
    public record CeldaMatriz(String usuario, String entidad, long total) {}
    public record ResumenAuditoria(long cambios, long cambiosPrevios, long fallidos, long fallidosPrevios,
                                   long denegados, long denegadosPrevios, long personas, List<PuntoAuditoria> serie,
                                   List<Conteo> entidades, List<Conteo> denegadosPorUsuario,
                                   List<CeldaAuditoria> calor, List<CeldaMatriz> matriz) {}

    private record Totales(long peticiones, long cliente, long servidor, long[] hasta) {
        static final Totales VACIO = new Totales(0, 0, 0, new long[Histograma.BORDES.length]);
        long p95() { return Histograma.percentil(peticiones, hasta, PERCENTIL); }
    }

    /** Filtros opcionales por igualdad; los nombres de columna son constantes de este archivo. */
    private static final class Condiciones {
        final StringBuilder sql = new StringBuilder();
        final List<Object> args = new ArrayList<>();
        Condiciones igual(String columna, String valor) {
            if (valor != null && !valor.isBlank()) {
                if (valor.length() > 250) throw new SolicitudInvalidaException("Filtro demasiado largo.");
                sql.append(" AND ").append(columna).append(" = ?");
                args.add(valor.strip());
            }
            return this;
        }
        Object[] con(Object... antes) {
            List<Object> todos = new ArrayList<>(List.of(antes));
            todos.addAll(args);
            return todos.toArray();
        }
    }

    private final JdbcTemplate jdbc;
    private final Transacciones transacciones;
    private final String zona;

    public ResumenObservabilidad(JdbcTemplate jdbc, Transacciones transacciones,
                                 @Value("${app.zona-horaria:America/Lima}") String zona) {
        this.jdbc = jdbc;
        this.transacciones = transacciones;
        this.zona = zona;
    }

    static void validar(int dias, Set<Integer> permitidos) {
        if (!permitidos.contains(dias)) {
            throw new SolicitudInvalidaException("Período no válido: usa " + permitidos.stream().sorted().toList() + " días.");
        }
    }

    // --- Monitoreo -------------------------------------------------------------------------

    public ResumenMonitoreo monitoreo(int dias) {
        validar(dias, PERIODOS);
        return transacciones.consultar(() -> {
            Map<Boolean, Totales> t = new HashMap<>();
            // Q: peticiones_hora_pkey (hora, …) por rango de hora.
            jdbc.query("""
                SELECT hora >= now() - make_interval(days => ?) AS actual, coalesce(sum(peticiones), 0) AS peticiones,
                       coalesce(sum(errores_cliente), 0) AS cliente, coalesce(sum(errores_servidor), 0) AS servidor, %s
                FROM bitacora.peticiones_hora
                WHERE hora >= now() - make_interval(days => ?)
                GROUP BY 1
                """.formatted(Histograma.sumas()),
                rs -> { t.put(rs.getBoolean("actual"), new Totales(rs.getLong("peticiones"), rs.getLong("cliente"),
                        rs.getLong("servidor"), Histograma.leer(rs))); },
                dias, dias * 2);
            Totales actual = t.getOrDefault(true, Totales.VACIO);
            Totales previo = t.getOrDefault(false, Totales.VACIO);
            List<PuntoMonitoreo> serie = jdbc.query(serie(dias, "bitacora.peticiones_hora", "hora", """
                    sum(peticiones) AS peticiones, sum(errores_cliente + errores_servidor) AS errores, %s
                    """.formatted(Histograma.sumas()), ""),
                (rs, i) -> {
                    long peticiones = rs.getLong("peticiones");
                    return new PuntoMonitoreo(rs.getString("etiqueta"), peticiones, rs.getLong("errores"),
                            Histograma.percentil(peticiones, Histograma.leer(rs), PERCENTIL));
                },
                parametrosSerie(dias));
            List<CeldaMonitoreo> calor = jdbc.query("""
                SELECT extract(isodow FROM hora AT TIME ZONE ?)::int AS dia,
                       (extract(hour FROM hora AT TIME ZONE ?)::int / %1$d) * %1$d AS desde,
                       sum(peticiones) AS peticiones, %2$s
                FROM bitacora.peticiones_hora
                WHERE hora >= now() - make_interval(days => ?)
                GROUP BY 1, 2
                ORDER BY 1, 2
                """.formatted(HORAS_POR_FRANJA, Histograma.sumas()),
                (rs, i) -> {
                    long peticiones = rs.getLong("peticiones");
                    return new CeldaMonitoreo(rs.getInt("dia"), rs.getInt("desde"), peticiones,
                            Histograma.percentil(peticiones, Histograma.leer(rs), PERCENTIL));
                },
                zona, zona, dias);
            return new ResumenMonitoreo(actual.peticiones(), previo.peticiones(),
                    actual.cliente() + actual.servidor(), previo.cliente() + previo.servidor(),
                    actual.servidor(), previo.servidor(), actual.p95(), previo.p95(), serie, calor);
        });
    }

    /** Una fila por API, de la más problemática a la más tranquila. */
    public List<EndpointMonitoreo> endpoints(int dias) {
        validar(dias, PERIODOS);
        return transacciones.consultar(() -> jdbc.query("""
                SELECT metodo, ruta, sum(peticiones) AS peticiones,
                       sum(errores_cliente + errores_servidor) AS errores, max(duracion_max_ms) AS maxima, %s
                FROM bitacora.peticiones_hora
                WHERE hora >= now() - make_interval(days => ?)
                GROUP BY metodo, ruta
                """.formatted(Histograma.sumas()),
                (rs, i) -> {
                    long peticiones = rs.getLong("peticiones");
                    long errores = rs.getLong("errores");
                    double porcentaje = peticiones == 0 ? 0 : Math.round(1000.0 * errores / peticiones) / 10.0;
                    return new EndpointMonitoreo(rs.getString("metodo"), rs.getString("ruta"), peticiones, errores,
                            porcentaje, Histograma.percentil(peticiones, Histograma.leer(rs), PERCENTIL),
                            rs.getLong("maxima"));
                }, dias))
            .stream()
            .sorted(Comparator.comparingLong(EndpointMonitoreo::errores).reversed()
                    .thenComparing(Comparator.comparingLong(EndpointMonitoreo::peticiones).reversed())
                    .thenComparing(EndpointMonitoreo::metodo).thenComparing(EndpointMonitoreo::ruta))
            .toList();
    }

    // --- Actividad -------------------------------------------------------------------------

    public ResumenActividad actividad(int dias, String usuario) {
        validar(dias, PERIODOS);
        var filtro = new Condiciones().igual("usuario", usuario);
        return transacciones.consultar(() -> {
            long[] actual = new long[2];
            long[] previo = new long[2];
            // Q: actividad_hora_pkey (hora, usuario, panel) por rango de hora.
            // Usuarios distintos en dos niveles (agrupar por usuario y contar filas): count(DISTINCT)
            // ordena y derramaba a disco con 30 días de un panel muy usado.
            jdbc.query("""
                SELECT actual, count(*) AS usuarios, coalesce(sum(acciones), 0) AS acciones
                FROM (
                    SELECT hora >= now() - make_interval(days => ?) AS actual, usuario, sum(peticiones) AS acciones
                    FROM bitacora.actividad_hora
                    WHERE hora >= now() - make_interval(days => ?)%s
                    GROUP BY 1, 2
                ) u
                GROUP BY actual
                """.formatted(filtro.sql), rs -> {
                    long[] destino = rs.getBoolean("actual") ? actual : previo;
                    destino[0] = rs.getLong("usuarios");
                    destino[1] = rs.getLong("acciones");
                }, filtro.con(dias, dias * 2));
            List<CeldaActividad> calor = jdbc.query("""
                SELECT extract(isodow FROM hora AT TIME ZONE ?)::int AS dia,
                       extract(hour FROM hora AT TIME ZONE ?)::int AS hora, sum(peticiones) AS acciones
                FROM bitacora.actividad_hora
                WHERE hora >= now() - make_interval(days => ?)%s
                GROUP BY 1, 2
                ORDER BY 1, 2
                """.formatted(filtro.sql),
                (rs, i) -> new CeldaActividad(rs.getInt("dia"), rs.getInt("hora"), rs.getLong("acciones")),
                filtro.con(zona, zona, dias));
            List<PanelActividad> paneles = jdbc.query("""
                SELECT panel, count(*) AS usuarios, sum(acciones) AS acciones
                FROM (
                    SELECT panel, usuario, sum(peticiones) AS acciones
                    FROM bitacora.actividad_hora
                    WHERE hora >= now() - make_interval(days => ?)%s
                    GROUP BY 1, 2
                ) u
                GROUP BY panel
                ORDER BY usuarios DESC, acciones DESC, panel
                """.formatted(filtro.sql),
                (rs, i) -> new PanelActividad(rs.getString("panel"), rs.getLong("usuarios"), rs.getLong("acciones")),
                filtro.con(dias));
            List<PuntoActividad> serie = dias == 1
                ? usuariosPorPeriodo("hour", "23 hours", "YYYY-MM-DD\"T\"HH24:00", filtro)
                : usuariosPorPeriodo("day", (dias - 1) + " days", "YYYY-MM-DD", filtro);
            // Adopción: usuarios distintos por semana (lunes a domingo), siempre las últimas 12.
            List<PuntoActividad> semanas = usuariosPorPeriodo("week", (SEMANAS_ADOPCION - 1) + " weeks", "YYYY-MM-DD", filtro);
            return new ResumenActividad(actual[0], previo[0], actual[1], previo[1], calor, paneles, serie, semanas);
        });
    }

    // --- Auditoría -------------------------------------------------------------------------

    public ResumenAuditoria auditoria(int dias, String usuario, String registro) {
        validar(dias, PERIODOS_AUDITORIA);
        var filtro = new Condiciones().igual("usuario", usuario).igual("registro", registro);
        return transacciones.consultar(() -> {
            Map<String, long[]> porResultado = new HashMap<>();
            // Q: auditoria_fecha (ocurrido_en, id) por rango.
            jdbc.query("""
                SELECT ocurrido_en >= now() - make_interval(days => ?) AS actual, resultado, count(*) AS total
                FROM bitacora.auditoria_eventos
                WHERE ocurrido_en >= now() - make_interval(days => ?)%s
                GROUP BY 1, 2
                """.formatted(filtro.sql), rs -> {
                    long[] par = porResultado.computeIfAbsent(rs.getString("resultado"), k -> new long[2]);
                    par[rs.getBoolean("actual") ? 0 : 1] = rs.getLong("total");
                }, filtro.con(dias, dias * 2));
            long[] exito = porResultado.getOrDefault("Éxito", new long[2]);
            long[] fallo = porResultado.getOrDefault("Fallo", new long[2]);
            long[] denegado = porResultado.getOrDefault("Denegado", new long[2]);
            Long personas = jdbc.queryForObject("""
                SELECT count(DISTINCT usuario) FROM bitacora.auditoria_eventos
                WHERE ocurrido_en >= now() - make_interval(days => ?) AND resultado = 'Éxito'%s
                """.formatted(filtro.sql), Long.class, filtro.con(dias));
            List<PuntoAuditoria> serie = jdbc.query(serie(dias, "bitacora.auditoria_eventos", "ocurrido_en", """
                    count(*) FILTER (WHERE resultado = 'Éxito') AS exito,
                    count(*) FILTER (WHERE resultado = 'Fallo') AS fallo,
                    count(*) FILTER (WHERE resultado = 'Denegado') AS denegado
                    """, filtro.sql.toString()),
                (rs, i) -> new PuntoAuditoria(rs.getString("etiqueta"), rs.getLong("exito"), rs.getLong("fallo"),
                        rs.getLong("denegado")),
                filtro.con(parametrosSerie(dias)));
            List<Conteo> entidades = jdbc.query("""
                SELECT entidad AS nombre, count(*) AS total FROM bitacora.auditoria_eventos
                WHERE ocurrido_en >= now() - make_interval(days => ?) AND resultado = 'Éxito'%s
                GROUP BY entidad ORDER BY total DESC, entidad LIMIT 8
                """.formatted(filtro.sql), (rs, i) -> new Conteo(rs.getString("nombre"), rs.getLong("total")),
                filtro.con(dias));
            List<Conteo> denegados = jdbc.query("""
                SELECT coalesce(usuario, 'Anónimo') AS nombre, count(*) AS total FROM bitacora.auditoria_eventos
                WHERE ocurrido_en >= now() - make_interval(days => ?) AND resultado = 'Denegado'%s
                GROUP BY 1 ORDER BY total DESC, nombre LIMIT 3
                """.formatted(filtro.sql), (rs, i) -> new Conteo(rs.getString("nombre"), rs.getLong("total")),
                filtro.con(dias));
            // Todos los intentos (aplicados, fallidos y denegados) por día y hora: lo de fuera de horario es la alerta.
            List<CeldaAuditoria> calor = jdbc.query("""
                SELECT extract(isodow FROM ocurrido_en AT TIME ZONE ?)::int AS dia,
                       extract(hour FROM ocurrido_en AT TIME ZONE ?)::int AS hora, count(*) AS total
                FROM bitacora.auditoria_eventos
                WHERE ocurrido_en >= now() - make_interval(days => ?)%s
                GROUP BY 1, 2
                ORDER BY 1, 2
                """.formatted(filtro.sql),
                (rs, i) -> new CeldaAuditoria(rs.getInt("dia"), rs.getInt("hora"), rs.getLong("total")),
                filtro.con(zona, zona, dias));
            // Quién cambia qué: los 8 usuarios con más cambios aplicados × tipo de registro.
            List<CeldaMatriz> matriz = jdbc.query("""
                WITH top AS (
                    SELECT usuario FROM bitacora.auditoria_eventos
                    WHERE ocurrido_en >= now() - make_interval(days => ?) AND resultado = 'Éxito'
                      AND usuario IS NOT NULL%1$s
                    GROUP BY usuario ORDER BY count(*) DESC, usuario LIMIT 8
                )
                SELECT usuario, entidad, count(*) AS total
                FROM bitacora.auditoria_eventos JOIN top USING (usuario)
                WHERE ocurrido_en >= now() - make_interval(days => ?) AND resultado = 'Éxito'%1$s
                GROUP BY 1, 2
                ORDER BY 1, 2
                """.formatted(filtro.sql),
                (rs, i) -> new CeldaMatriz(rs.getString("usuario"), rs.getString("entidad"), rs.getLong("total")),
                matrizParametros(filtro, dias));
            return new ResumenAuditoria(exito[0], exito[1], fallo[0], fallo[1], denegado[0], denegado[1],
                    personas == null ? 0 : personas, serie, entidades, denegados, calor, matriz);
        });
    }

    // --- Series ----------------------------------------------------------------------------

    /**
     * Serie sin huecos: 24 horas (período de 1 día) o un punto por día en la zona del negocio,
     * con hoy incluido. Los nombres de tabla, columna y agregados son constantes de esta clase.
     */
    private static String serie(int dias, String tabla, String columna, String agregados, String filtro) {
        String unidad = dias == 1 ? "hour" : "day";
        String formato = dias == 1 ? "YYYY-MM-DD\"T\"HH24:00" : "YYYY-MM-DD";
        return """
            WITH p AS (
                SELECT generate_series(date_trunc('%1$s', now() AT TIME ZONE ?) - ?::interval,
                                       date_trunc('%1$s', now() AT TIME ZONE ?), interval '1 %1$s') AS periodo
            ), r AS (
                SELECT date_trunc('%1$s', %3$s AT TIME ZONE ?) AS periodo, %4$s
                FROM %2$s
                WHERE %3$s >= (SELECT min(periodo) FROM p) AT TIME ZONE ?%5$s
                GROUP BY 1
            )
            SELECT to_char(p.periodo, '%6$s') AS etiqueta, r.*
            FROM p LEFT JOIN r ON r.periodo = p.periodo
            ORDER BY p.periodo
            """.formatted(unidad, tabla, columna, agregados, filtro, formato);
    }

    /**
     * Usuarios distintos y acciones por hora, día o semana (zona del negocio), sin huecos.
     * Dos niveles (usuario por período y luego conteo) en lugar de count(DISTINCT).
     * La unidad y el formato son constantes de esta clase.
     */
    private List<PuntoActividad> usuariosPorPeriodo(String unidad, String atras, String formato, Condiciones filtro) {
        return jdbc.query("""
            WITH p AS (
                SELECT generate_series(date_trunc('%1$s', now() AT TIME ZONE ?) - ?::interval,
                                       date_trunc('%1$s', now() AT TIME ZONE ?), interval '1 %1$s') AS periodo
            ), u AS (
                SELECT date_trunc('%1$s', hora AT TIME ZONE ?) AS periodo, usuario, sum(peticiones) AS acciones
                FROM bitacora.actividad_hora
                WHERE hora >= (SELECT min(periodo) FROM p) AT TIME ZONE ?%3$s
                GROUP BY 1, 2
            ), r AS (
                SELECT periodo, count(*) AS usuarios, sum(acciones) AS acciones FROM u GROUP BY periodo
            )
            SELECT to_char(p.periodo, '%2$s') AS etiqueta, coalesce(r.usuarios, 0) AS usuarios,
                   coalesce(r.acciones, 0) AS acciones
            FROM p LEFT JOIN r ON r.periodo = p.periodo
            ORDER BY p.periodo
            """.formatted(unidad, formato, filtro.sql),
            (rs, i) -> new PuntoActividad(rs.getString("etiqueta"), rs.getLong("usuarios"), rs.getLong("acciones")),
            filtro.con(zona, atras, zona, zona, zona));
    }

    /** Los filtros aparecen dos veces (en el top y en el detalle). */
    private static Object[] matrizParametros(Condiciones filtro, int dias) {
        List<Object> args = new ArrayList<>();
        args.add(dias);
        args.addAll(filtro.args);
        args.add(dias);
        args.addAll(filtro.args);
        return args.toArray();
    }

    private Object[] parametrosSerie(int dias) {
        String atras = dias == 1 ? "23 hours" : (dias - 1) + " days";
        return new Object[] {zona, atras, zona, zona, zona};
    }
}
