package oracle.malla.repository;

import java.sql.PreparedStatement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import oracle.malla.request.CentroMallaRequest;
import oracle.malla.request.CentroMallaRequest.Capacidad;
import oracle.malla.request.CentroMallaRequest.Dotacion;
import oracle.malla.request.CentroMallaRequest.DotacionReal;
import oracle.malla.request.CentroMallaRequest.Flujo;
import oracle.malla.request.CentroMallaRequest.Ola;
import oracle.malla.request.CentroMallaRequest.Turno;
import oracle.malla.request.CentroMallaRequest.Zona;
import oracle.malla.response.CambioMallaResponse;
import oracle.malla.response.CentroMallaResponse;
import oracle.malla.response.CentroMallaResumenResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Lectura y escritura de la malla de un centro con JdbcTemplate, como
 * {@link MallaDatosRepository}: son tablas hijas que se guardan completas
 * (se reemplazan) y no necesitan entidades.
 */
@Repository
public class MallaCentroRepository {

    /** Columna que devuelve el INSERT (PostgreSQL devolvería la fila entera). */
    private static final String[] COLUMNA_ID = {"id"};

    private final JdbcTemplate jdbc;

    public MallaCentroRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CentroMallaResumenResponse> listar() {
        return jdbc.query("""
                SELECT c.id, c.codigo, c.nombre, c.actualizado_en,
                       (SELECT COUNT(*) FROM malla_turno t WHERE t.centro_id = c.id) AS turnos,
                       (SELECT COUNT(*) FROM malla_flujo f WHERE f.centro_id = c.id) AS flujos,
                       (SELECT COUNT(*) FROM malla_ola o WHERE o.centro_id = c.id) AS olas,
                       (SELECT COUNT(*) FROM malla_zona z WHERE z.centro_id = c.id) AS zonas
                FROM malla_centro c
                WHERE c.activo = TRUE
                ORDER BY c.id
                """, (rs, fila) -> new CentroMallaResumenResponse(
                rs.getLong("id"),
                rs.getString("codigo"),
                rs.getString("nombre"),
                rs.getInt("turnos"),
                rs.getInt("flujos"),
                rs.getInt("olas"),
                rs.getInt("zonas"),
                rs.getTimestamp("actualizado_en").toInstant()));
    }

    public Optional<CentroMallaResponse> obtener(Long id) {
        List<Object[]> centro = jdbc.query(
                "SELECT codigo, nombre, actualizado_en FROM malla_centro WHERE id = ? AND activo = TRUE",
                (rs, fila) -> new Object[] {
                        rs.getString("codigo"), rs.getString("nombre"), rs.getTimestamp("actualizado_en")},
                id);
        if (centro.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, String> codigosTurno = new HashMap<>();
        List<Turno> turnos = jdbc.query("""
                SELECT id, codigo, nombre, hora_inicio, hora_fin, hora_refrigerio, tipo_refrigerio,
                       nota, confirmado
                FROM malla_turno WHERE centro_id = ? ORDER BY id
                """, (rs, fila) -> {
            codigosTurno.put(rs.getLong("id"), rs.getString("codigo"));
            return new Turno(
                    rs.getString("codigo"),
                    rs.getString("nombre"),
                    hora(rs.getTime("hora_inicio")),
                    hora(rs.getTime("hora_fin")),
                    hora(rs.getTime("hora_refrigerio")),
                    rs.getString("tipo_refrigerio"),
                    rs.getString("nota"),
                    rs.getBoolean("confirmado"));
        }, id);
        List<Flujo> flujos = jdbc.query("""
                SELECT proceso, responsable, hora_inicio, hora_fin, actividad, confirmado
                FROM malla_flujo WHERE centro_id = ?
                ORDER BY array_position(ARRAY['REC', 'ALM', 'REA', 'OLA', 'DES']::varchar[], proceso), orden
                """, (rs, fila) -> new Flujo(
                rs.getString("proceso"),
                rs.getString("responsable"),
                hora(rs.getTime("hora_inicio")),
                hora(rs.getTime("hora_fin")),
                rs.getString("actividad"),
                rs.getBoolean("confirmado")), id);
        List<Ola> olas = jdbc.query("""
                SELECT cluster, nombre, caida_pedidos, lanzamiento, cierre_picking, despacho,
                       confirmado, nota
                FROM malla_ola WHERE centro_id = ?
                ORDER BY COALESCE(substring(cluster FROM '^[0-9]+')::int, 0), cluster, lanzamiento, nombre
                """, (rs, fila) -> new Ola(
                rs.getString("cluster"),
                rs.getString("nombre"),
                hora(rs.getTime("caida_pedidos")),
                hora(rs.getTime("lanzamiento")),
                hora(rs.getTime("cierre_picking")),
                hora(rs.getTime("despacho")),
                rs.getBoolean("confirmado"),
                rs.getString("nota")), id);
        List<Zona> zonas = jdbc.query(
                "SELECT nombre, lph_real, lph_diseno FROM malla_zona WHERE centro_id = ? ORDER BY id",
                (rs, fila) -> new Zona(
                        rs.getString("nombre"),
                        entero(rs.getObject("lph_real")),
                        entero(rs.getObject("lph_diseno"))), id);
        Capacidad capacidad = jdbc.query("""
                SELECT unidades_dia, lineas_dia, bultos_recepcion_dia, proveedores_dia
                FROM malla_capacidad WHERE centro_id = ?
                """, (rs, fila) -> new Capacidad(
                entero(rs.getObject("unidades_dia")),
                entero(rs.getObject("lineas_dia")),
                entero(rs.getObject("bultos_recepcion_dia")),
                entero(rs.getObject("proveedores_dia"))), id)
                .stream().findFirst().orElse(new Capacidad(null, null, null, null));
        List<Dotacion> dotacion = new ArrayList<>();
        jdbc.query("SELECT turno_id, proceso, personas FROM malla_dotacion WHERE centro_id = ?",
                (rs) -> {
                    String turno = codigosTurno.get(rs.getLong("turno_id"));
                    if (turno != null) {
                        dotacion.add(new Dotacion(turno, rs.getString("proceso"),
                                entero(rs.getObject("personas"))));
                    }
                }, id);
        DotacionReal dotacionReal = jdbc.query("""
                SELECT inbound, outbound, despacho, total, semana
                FROM malla_dotacion_real WHERE centro_id = ?
                """, (rs, fila) -> new DotacionReal(
                entero(rs.getObject("inbound")),
                entero(rs.getObject("outbound")),
                entero(rs.getObject("despacho")),
                entero(rs.getObject("total")),
                rs.getString("semana")), id)
                .stream().findFirst().orElse(new DotacionReal(null, null, null, null, null));
        Object[] datos = centro.get(0);
        return Optional.of(new CentroMallaResponse(
                id,
                (String) datos[0],
                (String) datos[1],
                turnos,
                flujos,
                olas,
                zonas,
                capacidad,
                dotacion,
                dotacionReal,
                ((Timestamp) datos[2]).toInstant()));
    }

    /** Id del centro con ese código (sin distinguir mayúsculas ni tildes: colación comun.ci), si existe. */
    public Optional<Long> idPorCodigo(String codigo) {
        return jdbc.query("SELECT id FROM malla_centro WHERE codigo = ?",
                (rs, fila) -> rs.getLong("id"), codigo).stream().findFirst();
    }

    /**
     * Bloquea el centro hasta el fin de la transacción (dos guardados no se
     * mezclan) y devuelve su última actualización; vacío si no existe.
     */
    public Optional<Instant> bloquear(Long id) {
        return jdbc.query("SELECT actualizado_en FROM malla_centro WHERE id = ? AND activo = TRUE FOR UPDATE",
                (rs, fila) -> rs.getTimestamp("actualizado_en").toInstant(), id).stream().findFirst();
    }

    /** Deja constancia de un cambio en la malla de un centro. */
    public void registrarCambio(Long centroId, String codigo, String accion, String resumen, Long actor) {
        jdbc.update("""
                INSERT INTO malla_cambio (centro_id, codigo_centro, accion, resumen, creado_por)
                VALUES (?, ?, ?, ?, ?)
                """, centroId, codigo, accion, resumen, actor);
    }

    /** Últimos cambios del centro, del más reciente al más antiguo. */
    public List<CambioMallaResponse> cambios(Long centroId, int limite) {
        return jdbc.query("""
                SELECT accion, resumen, creado_por, creado_en
                FROM malla_cambio WHERE centro_id = ? ORDER BY id DESC LIMIT ?
                """, (rs, fila) -> new CambioMallaResponse(
                rs.getString("accion"),
                rs.getString("resumen"),
                rs.getLong("creado_por"),
                rs.getTimestamp("creado_en").toInstant()), centroId, limite);
    }

    public Long crear(String codigo, String nombre, Long actor) {
        KeyHolder clave = new GeneratedKeyHolder();
        jdbc.update(conexion -> {
            PreparedStatement ps = conexion.prepareStatement("""
                    INSERT INTO malla_centro (codigo, nombre, activo, creado_por, actualizado_por)
                    VALUES (?, ?, TRUE, ?, ?)
                    """, COLUMNA_ID);
            ps.setString(1, codigo);
            ps.setString(2, nombre);
            ps.setLong(3, actor);
            ps.setLong(4, actor);
            return ps;
        }, clave);
        return clave.getKey().longValue();
    }

    /**
     * Reemplaza la malla del centro: datos del centro, turnos (y con ellos su
     * dotación, por cascada), flujos, olas, zonas, capacidad y dotación real.
     * actualizado_en lo fija el trigger comun.fijar_actualizado_en.
     */
    public void guardar(Long id, CentroMallaRequest req, Long actor) {
        jdbc.update("""
                UPDATE malla_centro
                SET codigo = ?, nombre = ?, actualizado_por = ?
                WHERE id = ?
                """, req.codigo().trim(), req.nombre().trim(), actor, id);
        for (String tabla : List.of("malla_turno", "malla_flujo", "malla_ola", "malla_zona")) {
            jdbc.update("DELETE FROM " + tabla + " WHERE centro_id = ?", id);
        }

        Map<String, Long> turnos = new HashMap<>();
        for (Turno turno : req.turnos()) {
            KeyHolder clave = new GeneratedKeyHolder();
            jdbc.update(conexion -> {
                PreparedStatement ps = conexion.prepareStatement("""
                        INSERT INTO malla_turno (centro_id, codigo, nombre, hora_inicio, hora_fin,
                            hora_refrigerio, tipo_refrigerio, nota, confirmado, creado_por, actualizado_por)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, COLUMNA_ID);
                ps.setLong(1, id);
                ps.setString(2, turno.codigo().trim());
                ps.setString(3, turno.nombre().trim());
                ps.setTime(4, sqlHora(turno.inicio()));
                ps.setTime(5, sqlHora(turno.fin()));
                ps.setTime(6, sqlHora(turno.refrigerio()));
                ps.setString(7, texto(turno.tipoRefrigerio()));
                ps.setString(8, texto(turno.nota()));
                ps.setBoolean(9, turno.confirmado());
                ps.setLong(10, actor);
                ps.setLong(11, actor);
                return ps;
            }, clave);
            turnos.put(clave(turno.codigo()), clave.getKey().longValue());
        }

        Map<String, Integer> ordenes = new HashMap<>();
        List<Object[]> flujos = new ArrayList<>();
        for (Flujo paso : req.flujos()) {
            int orden = ordenes.merge(paso.proceso(), 1, Integer::sum);
            flujos.add(new Object[] {id, paso.proceso(), orden, paso.responsable(), sqlHora(paso.inicio()),
                    sqlHora(paso.fin()), paso.actividad().trim(), paso.confirmado(), actor, actor});
        }
        jdbc.batchUpdate("""
                INSERT INTO malla_flujo (centro_id, proceso, orden, responsable, hora_inicio, hora_fin,
                    actividad, confirmado, creado_por, actualizado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, flujos);

        jdbc.batchUpdate("""
                INSERT INTO malla_ola (centro_id, cluster, nombre, caida_pedidos, lanzamiento,
                    cierre_picking, despacho, confirmado, nota, creado_por, actualizado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, req.olas().stream().map(ola -> new Object[] {id, ola.cluster().trim(),
                        ola.nombre().trim(), sqlHora(ola.caidaPedidos()), sqlHora(ola.lanzamiento()),
                        sqlHora(ola.cierrePicking()), sqlHora(ola.despacho()), ola.confirmado(),
                        texto(ola.nota()), actor, actor}).toList());

        jdbc.batchUpdate("""
                INSERT INTO malla_zona (centro_id, nombre, lph_real, lph_diseno, creado_por, actualizado_por)
                VALUES (?, ?, ?, ?, ?, ?)
                """, req.zonas().stream().map(zona -> new Object[] {id, zona.nombre().trim(),
                        zona.lphReal(), zona.lphDiseno(), actor, actor}).toList(),
                new int[] {Types.BIGINT, Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.BIGINT,
                        Types.BIGINT});

        jdbc.batchUpdate("""
                INSERT INTO malla_dotacion (centro_id, turno_id, proceso, personas, creado_por, actualizado_por)
                VALUES (?, ?, ?, ?, ?, ?)
                """, req.dotacion().stream().map(fila -> new Object[] {id, turnos.get(clave(fila.turno())),
                        fila.proceso(), fila.personas(), actor, actor}).toList(),
                new int[] {Types.BIGINT, Types.BIGINT, Types.VARCHAR, Types.INTEGER, Types.BIGINT,
                        Types.BIGINT});

        Capacidad cap = req.capacidad();
        jdbc.update("""
                INSERT INTO malla_capacidad (centro_id, unidades_dia, lineas_dia, bultos_recepcion_dia,
                    proveedores_dia, creado_por, actualizado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (centro_id) DO UPDATE SET unidades_dia = EXCLUDED.unidades_dia,
                    lineas_dia = EXCLUDED.lineas_dia, bultos_recepcion_dia = EXCLUDED.bultos_recepcion_dia,
                    proveedores_dia = EXCLUDED.proveedores_dia, actualizado_por = EXCLUDED.actualizado_por
                """, new Object[] {id, cap.unidadesDia(), cap.lineasDia(), cap.bultosRecepcionDia(),
                        cap.proveedoresDia(), actor, actor},
                new int[] {Types.BIGINT, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER,
                        Types.BIGINT, Types.BIGINT});

        DotacionReal real = req.dotacionReal();
        jdbc.update("""
                INSERT INTO malla_dotacion_real (centro_id, inbound, outbound, despacho, total, semana,
                    creado_por, actualizado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (centro_id) DO UPDATE SET inbound = EXCLUDED.inbound,
                    outbound = EXCLUDED.outbound, despacho = EXCLUDED.despacho, total = EXCLUDED.total,
                    semana = EXCLUDED.semana, actualizado_por = EXCLUDED.actualizado_por
                """, new Object[] {id, real.inbound(), real.outbound(), real.despacho(), real.total(),
                        texto(real.semana()), actor, actor},
                new int[] {Types.BIGINT, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER,
                        Types.VARCHAR, Types.BIGINT, Types.BIGINT});
    }

    /** Borra el centro y toda su malla (las tablas hijas caen por cascada). */
    public boolean eliminar(Long id) {
        return jdbc.update("DELETE FROM malla_centro WHERE id = ?", id) > 0;
    }

    /** Códigos de turno sin distinguir mayúsculas, igual que la colación comun.ci de la base. */
    public static String clave(String codigo) {
        return codigo.trim().toUpperCase(Locale.ROOT);
    }

    private static String hora(Time valor) {
        return valor == null ? null : valor.toLocalTime().toString().substring(0, 5);
    }

    private static Time sqlHora(String valor) {
        return valor == null || valor.isBlank() ? null : Time.valueOf(LocalTime.parse(valor));
    }

    private static String texto(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }

    private static Integer entero(Object valor) {
        return valor == null ? null : ((Number) valor).intValue();
    }
}
