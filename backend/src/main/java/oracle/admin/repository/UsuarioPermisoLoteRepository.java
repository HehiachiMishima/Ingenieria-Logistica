package oracle.admin.repository;

import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import oracle.admin.response.ExcepcionPermisoResponse;
import oracle.admin.response.PermisoDelUsuarioResponse;
import oracle.common.support.Listas;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lecturas y escrituras en lote de usuarios_permisos (excepciones) para el tablero de permisos
 * individuales: una consulta para el catálogo con lo que dan los roles, la excepción y si el
 * actor puede conceder cada permiso; una sentencia por tipo de cambio.
 */
@Repository
public class UsuarioPermisoLoteRepository {

    private final JdbcTemplate jdbc;

    public UsuarioPermisoLoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Catálogo visto desde el usuario. «Por rol» = lo concede algún rol activo del usuario (como
     * v_permisos_efectivos, sin mirar si el permiso está activo: la ficha ya lo muestra);
     * «otorgable» replica ControlEscalamiento para conceder (el actor lo tiene permitido; el
     * actor 0 puede todo). Todo sale de lecturas por usuario (PK de usuarios_roles y de
     * usuarios_permisos), no recorre las tablas completas.
     */
    public List<PermisoDelUsuarioResponse> tablero(Long usuarioId, Long actor) {
        return jdbc.query("""
                WITH propios AS (
                    SELECT permiso_id FROM v_permisos_finales WHERE usuario_id = ? AND permitido
                ),
                por_rol AS (
                    SELECT DISTINCT rp.permiso_id
                    FROM usuarios_roles ur
                    JOIN roles r ON r.id = ur.rol_id AND r.activo
                    JOIN roles_permisos rp ON rp.rol_id = ur.rol_id
                    WHERE ur.usuario_id = ?
                )
                SELECT p.id, p.nombre, p.descripcion, p.activo,
                       (pr.permiso_id IS NOT NULL) AS por_rol,
                       up.otorgado AS excepcion,
                       (? = 0 OR pp.permiso_id IS NOT NULL) AS otorgable
                FROM permisos p
                LEFT JOIN por_rol pr ON pr.permiso_id = p.id
                LEFT JOIN usuarios_permisos up ON up.usuario_id = ? AND up.permiso_id = p.id
                LEFT JOIN propios pp ON pp.permiso_id = p.id
                ORDER BY p.nombre
                LIMIT ?
                """,
                (rs, fila) -> new PermisoDelUsuarioResponse(
                        rs.getLong("id"),
                        rs.getString("nombre"),
                        rs.getString("descripcion"),
                        rs.getBoolean("activo"),
                        rs.getBoolean("por_rol"),
                        rs.getObject("excepcion", Boolean.class),
                        rs.getBoolean("otorgable")),
                actor, usuarioId, actor, usuarioId, Listas.MAX_FILAS);
    }

    /** Excepciones vigentes del usuario (permiso → TRUE concede / FALSE deniega). */
    public Map<Long, Boolean> excepciones(Long usuarioId) {
        Map<Long, Boolean> mapa = new LinkedHashMap<>();
        jdbc.query("SELECT permiso_id, otorgado FROM usuarios_permisos WHERE usuario_id = ? ORDER BY permiso_id LIMIT ?",
                rs -> { mapa.put(rs.getLong(1), rs.getBoolean(2)); }, usuarioId, Listas.MAX_FILAS);
        return mapa;
    }

    public List<ExcepcionPermisoResponse> listaExcepciones(Long usuarioId) {
        return excepciones(usuarioId).entrySet().stream()
                .map(e -> new ExcepcionPermisoResponse(e.getKey(), e.getValue()))
                .toList();
    }

    /** Nombre de cada permiso existente entre {@code ids} (los que no existen no aparecen). */
    public Map<Long, String> nombres(Collection<Long> ids) {
        Map<Long, String> nombres = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return nombres;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT id, nombre FROM permisos WHERE id = ANY (?) ORDER BY nombre");
            ps.setArray(1, arreglo(con, ids));
            return ps;
        }, rs -> { nombres.put(rs.getLong("id"), rs.getString("nombre")); });
        return nombres;
    }

    /**
     * Fija la excepción de esos permisos en {@code otorgado} (crea o cambia); las que ya estaban
     * así no se tocan. Devuelve cuántas filas cambiaron.
     */
    public int fijar(Long usuarioId, Collection<Long> permisoIds, boolean otorgado, Long actor) {
        if (permisoIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO usuarios_permisos (usuario_id, permiso_id, otorgado, creado_por, actualizado_por)
                    SELECT ?, permiso_id, ?, ?, ? FROM unnest(?::int8[]) AS permiso_id
                    ON CONFLICT (usuario_id, permiso_id) DO UPDATE
                        SET otorgado = EXCLUDED.otorgado,
                            actualizado_por = EXCLUDED.actualizado_por,
                            actualizado_en = now()
                        WHERE usuarios_permisos.otorgado IS DISTINCT FROM EXCLUDED.otorgado
                    """);
            ps.setLong(1, usuarioId);
            ps.setBoolean(2, otorgado);
            ps.setLong(3, actor);
            ps.setLong(4, actor);
            ps.setArray(5, arreglo(con, permisoIds));
            return ps;
        });
    }

    /** Quita las excepciones (vuelven a heredar del rol). Devuelve cuántas se quitaron. */
    public int quitar(Long usuarioId, Collection<Long> permisoIds) {
        if (permisoIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(con -> {
            var ps = con.prepareStatement("DELETE FROM usuarios_permisos WHERE usuario_id = ? AND permiso_id = ANY (?)");
            ps.setLong(1, usuarioId);
            ps.setArray(2, arreglo(con, permisoIds));
            return ps;
        });
    }

    private static Array arreglo(Connection con, Collection<Long> ids) throws SQLException {
        return con.createArrayOf("int8", ids.toArray());
    }
}
