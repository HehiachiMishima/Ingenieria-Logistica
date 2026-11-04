package oracle.malla.repository;

import java.sql.Time;
import java.time.LocalTime;
import java.util.List;
import oracle.malla.model.MallaDatos;
import oracle.malla.model.MallaDatos.Capacidad;
import oracle.malla.model.MallaDatos.Centro;
import oracle.malla.model.MallaDatos.Dotacion;
import oracle.malla.model.MallaDatos.Flujo;
import oracle.malla.model.MallaDatos.Ola;
import oracle.malla.model.MallaDatos.Turno;
import oracle.malla.model.MallaDatos.Zona;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Lectura de la malla capturada. Se usa JdbcTemplate (y no entidades JPA)
 * porque la evaluación necesita una foto completa y de sólo lectura de las
 * ocho tablas; mapearlas a entidades agregaría ruido sin aportar nada.
 */
@Repository
public class MallaDatosRepository {

    private final JdbcTemplate jdbc;

    public MallaDatosRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Foto de todos los centros o, con {@code centroId}, sólo de ese centro. */
    public MallaDatos cargar(Long centroId) {
        return new MallaDatos(
                centros(centroId),
                turnos(centroId),
                flujos(centroId),
                olas(centroId),
                zonas(centroId),
                capacidades(centroId),
                dotaciones(centroId));
    }

    private List<Centro> centros(Long centroId) {
        RowMapper<Centro> centro = (rs, fila) -> new Centro(
                rs.getLong("id"),
                rs.getString("codigo"),
                rs.getString("nombre"));
        String consulta = "SELECT id, codigo, nombre FROM malla_centro WHERE activo = TRUE";
        return centroId == null
                ? jdbc.query(consulta + " ORDER BY id", centro)
                : jdbc.query(consulta + " AND id = ?", centro, centroId);
    }

    private List<Turno> turnos(Long centroId) {
        return consultar("""
                SELECT id, centro_id, codigo, nombre, hora_inicio, hora_fin, hora_refrigerio, confirmado
                FROM malla_turno
                """, "centro_id, id", centroId, (rs, fila) -> new Turno(
                rs.getLong("id"),
                rs.getLong("centro_id"),
                rs.getString("codigo"),
                rs.getString("nombre"),
                hora(rs.getTime("hora_inicio")),
                hora(rs.getTime("hora_fin")),
                hora(rs.getTime("hora_refrigerio")),
                rs.getBoolean("confirmado")));
    }

    private List<Flujo> flujos(Long centroId) {
        return consultar("""
                SELECT id, centro_id, proceso, orden, responsable, hora_inicio, hora_fin, actividad,
                       confirmado
                FROM malla_flujo
                """, "centro_id, proceso, orden", centroId, (rs, fila) -> new Flujo(
                rs.getLong("id"),
                rs.getLong("centro_id"),
                rs.getString("proceso"),
                rs.getInt("orden"),
                rs.getString("responsable"),
                hora(rs.getTime("hora_inicio")),
                hora(rs.getTime("hora_fin")),
                rs.getString("actividad"),
                rs.getBoolean("confirmado")));
    }

    private List<Ola> olas(Long centroId) {
        return consultar("""
                SELECT id, centro_id, cluster, nombre, caida_pedidos, lanzamiento, cierre_picking,
                       despacho, confirmado
                FROM malla_ola
                """, "centro_id, cluster, nombre", centroId, (rs, fila) -> new Ola(
                rs.getLong("id"),
                rs.getLong("centro_id"),
                rs.getString("cluster"),
                rs.getString("nombre"),
                hora(rs.getTime("caida_pedidos")),
                hora(rs.getTime("lanzamiento")),
                hora(rs.getTime("cierre_picking")),
                hora(rs.getTime("despacho")),
                rs.getBoolean("confirmado")));
    }

    private List<Zona> zonas(Long centroId) {
        return consultar("""
                SELECT id, centro_id, nombre, lph_real, lph_diseno
                FROM malla_zona
                """, "centro_id, nombre", centroId, (rs, fila) -> new Zona(
                rs.getLong("id"),
                rs.getLong("centro_id"),
                rs.getString("nombre"),
                entero(rs.getObject("lph_real")),
                entero(rs.getObject("lph_diseno"))));
    }

    private List<Capacidad> capacidades(Long centroId) {
        return consultar("""
                SELECT centro_id, unidades_dia, lineas_dia, bultos_recepcion_dia, proveedores_dia
                FROM malla_capacidad
                """, "centro_id", centroId, (rs, fila) -> new Capacidad(
                rs.getLong("centro_id"),
                entero(rs.getObject("unidades_dia")),
                entero(rs.getObject("lineas_dia")),
                entero(rs.getObject("bultos_recepcion_dia")),
                entero(rs.getObject("proveedores_dia"))));
    }

    private List<Dotacion> dotaciones(Long centroId) {
        return consultar("""
                SELECT id, centro_id, turno_id, proceso, personas
                FROM malla_dotacion
                """, "centro_id, proceso, turno_id", centroId, (rs, fila) -> new Dotacion(
                rs.getLong("id"),
                rs.getLong("centro_id"),
                rs.getLong("turno_id"),
                rs.getString("proceso"),
                entero(rs.getObject("personas"))));
    }

    /** Tablas hijas de la malla: todas las filas o sólo las del centro pedido. */
    private <T> List<T> consultar(String consulta, String orden, Long centroId, RowMapper<T> fila) {
        return centroId == null
                ? jdbc.query(consulta + "ORDER BY " + orden, fila)
                : jdbc.query(consulta + "WHERE centro_id = ? ORDER BY " + orden, fila, centroId);
    }

    private static LocalTime hora(Time valor) {
        return valor == null ? null : valor.toLocalTime();
    }

    private static Integer entero(Object valor) {
        return valor == null ? null : ((Number) valor).intValue();
    }
}
