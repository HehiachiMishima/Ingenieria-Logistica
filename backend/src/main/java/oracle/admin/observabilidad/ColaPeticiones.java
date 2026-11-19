package oracle.admin.observabilidad;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Function;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Las trazas técnicas se agrupan fuera del hilo HTTP. La auditoría usa la transacción del cambio. */
@Component
public class ColaPeticiones {
    record Traza(UUID solicitud, Long actor, String usuario, String metodo, String ruta, int estado,
                 long ms, String correlacion, java.time.Instant fecha) {}

    /** Peticiones de sesión que no son trabajo del usuario: no cuentan como actividad. */
    static final String RUTAS_TECNICAS = "'/api/auth/refresh','/api/auth/sesion','/api/health'";

    /** Panel al que pertenece cada prefijo de API (el segmento tras /api/). */
    private static final String PANEL = """
        CASE split_part(ruta, '/', 3)
            WHEN 'admin' THEN 'PANEL_ADMIN' WHEN 'usuarios' THEN 'PANEL_ADMIN' WHEN 'roles' THEN 'PANEL_ADMIN'
            WHEN 'permisos' THEN 'PANEL_ADMIN' WHEN 'resolucion' THEN 'PANEL_ADMIN'
            WHEN 'incidencias' THEN 'PANEL_BITACORA' WHEN 'areas' THEN 'PANEL_BITACORA'
            WHEN 'centros' THEN 'PANEL_BITACORA' WHEN 'sistemas' THEN 'PANEL_BITACORA'
            WHEN 'simulacion' THEN 'PANEL_SIMULACION' WHEN 'simulacion-santa' THEN 'PANEL_SIMULACION_SANTA'
            WHEN 'malla' THEN 'PANEL_MALLA' WHEN 'picking' THEN 'PANEL_PICKING'
            WHEN 'auth' THEN 'SESION' ELSE 'OTROS' END""";

    /**
     * Una sola sentencia por lote: inserta las trazas y suma a los resúmenes por hora
     * solo las que entraron de verdad (RETURNING), así un lote reintentado no cuenta doble.
     * Las filas de resumen se escriben en orden de clave para que dos instancias no se bloqueen.
     */
    static final String INSERTAR = """
        WITH lote AS (
            SELECT * FROM unnest(?::text[], ?::int8[], ?::text[], ?::text[], ?::text[], ?::int4[], ?::int8[], ?::text[], ?::text[])
                AS l(solicitud, actor_id, usuario, metodo, ruta, estado, duracion_ms, correlacion, ocurrido_en)
        ), nuevas AS (
            INSERT INTO bitacora.peticiones
              (solicitud, actor_id, usuario, metodo, ruta, estado, duracion_ms, correlacion, ocurrido_en)
            SELECT solicitud::uuid, actor_id, usuario, metodo, ruta, estado, duracion_ms, correlacion, ocurrido_en::timestamptz
            FROM lote
            ON CONFLICT (solicitud) DO NOTHING
            RETURNING ocurrido_en, actor_id, usuario, metodo, ruta, estado, duracion_ms
        ), por_api AS (
            INSERT INTO bitacora.peticiones_hora AS h
              (hora, metodo, ruta, peticiones, errores_cliente, errores_servidor, duracion_total_ms, %s)
            SELECT date_trunc('hour', ocurrido_en, 'UTC'), metodo, ruta, count(*),
                   count(*) FILTER (WHERE estado BETWEEN 400 AND 499), count(*) FILTER (WHERE estado >= 500),
                   sum(duracion_ms), %s
            FROM nuevas
            GROUP BY 1, 2, 3
            ORDER BY 1, 2, 3
            ON CONFLICT (hora, metodo, ruta) DO UPDATE SET
                peticiones = h.peticiones + EXCLUDED.peticiones,
                errores_cliente = h.errores_cliente + EXCLUDED.errores_cliente,
                errores_servidor = h.errores_servidor + EXCLUDED.errores_servidor,
                duracion_total_ms = h.duracion_total_ms + EXCLUDED.duracion_total_ms,
                %s
        )
        INSERT INTO bitacora.actividad_hora AS a (hora, usuario, panel, peticiones)
        SELECT date_trunc('hour', ocurrido_en, 'UTC'), usuario, %s, count(*)
        FROM nuevas
        WHERE actor_id IS NOT NULL AND usuario IS NOT NULL AND ruta NOT IN (%s)
        GROUP BY 1, 2, 3
        ORDER BY 1, 2, 3
        ON CONFLICT (hora, usuario, panel) DO UPDATE SET peticiones = a.peticiones + EXCLUDED.peticiones
        """.formatted(Histograma.columnas(), Histograma.conteos("duracion_ms"), Histograma.acumular("h"),
            PANEL, RUTAS_TECNICAS);

    private static final Logger LOG = LoggerFactory.getLogger(ColaPeticiones.class);
    private final ArrayBlockingQueue<Traza> pendientes = new ArrayBlockingQueue<>(8192);
    private final Counter descartadas;
    private final JdbcTemplate jdbc;

    public ColaPeticiones(JdbcTemplate jdbc, MeterRegistry metricas) {
        this.jdbc = jdbc;
        descartadas = metricas.counter("atlas.trazas.descartadas");
        metricas.gauge("atlas.trazas.pendientes", pendientes, ArrayBlockingQueue::size);
    }

    void ofrecer(Traza traza) {
        if (!pendientes.offer(traza)) descartadas.increment();
    }

    private static final int LOTE = 500;
    /**
     * Lotes por pasada mientras la cola siga llena. Con un solo lote cada 250 ms la cola
     * absorbía ~1 700 trazas/s por instancia (lote de 500 ≈ 45 ms); así llega a ~7 000/s.
     */
    private static final int LOTES_POR_PASADA = 10;

    @Scheduled(fixedDelayString="PT0.25S")
    public synchronized void escribir() {
        for (int i = 0; i < LOTES_POR_PASADA; i++) {
            List<Traza> lote = new ArrayList<>();
            pendientes.drainTo(lote, LOTE);
            if (lote.isEmpty()) return;
            try {
                guardar(lote);
            } catch (DataAccessException e) {
                lote.forEach(this::ofrecer);
                LOG.warn("No se pudo escribir el lote de trazas: {}", e.getClass().getSimpleName());
                return; // la base falla: se reintenta en la próxima pasada
            }
            if (lote.size() < LOTE) return;
        }
    }

    /** Guarda el lote y actualiza los resúmenes por hora en una sola ida a la base. */
    void guardar(List<Traza> lote) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(INSERTAR);
            ps.setArray(1, con.createArrayOf("text", columna(lote, t -> t.solicitud().toString())));
            ps.setArray(2, con.createArrayOf("int8", columna(lote, Traza::actor)));
            ps.setArray(3, con.createArrayOf("text", columna(lote, Traza::usuario)));
            ps.setArray(4, con.createArrayOf("text", columna(lote, Traza::metodo)));
            ps.setArray(5, con.createArrayOf("text", columna(lote, Traza::ruta)));
            ps.setArray(6, con.createArrayOf("int4", columna(lote, Traza::estado)));
            ps.setArray(7, con.createArrayOf("int8", columna(lote, Traza::ms)));
            ps.setArray(8, con.createArrayOf("text", columna(lote, Traza::correlacion)));
            // ISO-8601 con Z: PostgreSQL lo lee como UTC sin depender de la zona de la JVM.
            ps.setArray(9, con.createArrayOf("text", columna(lote, t -> t.fecha().toString())));
            return ps;
        });
    }

    private static Object[] columna(List<Traza> lote, Function<Traza, ?> valor) {
        return lote.stream().map(valor).toArray();
    }

    @PreDestroy
    public synchronized void vaciar() {
        // Una pasada acotada, también si la base sigue indisponible durante el cierre.
        int pasadas = (pendientes.size() + LOTE * LOTES_POR_PASADA - 1) / (LOTE * LOTES_POR_PASADA);
        for (int i = 0; i < pasadas; i++) escribir();
    }
}
