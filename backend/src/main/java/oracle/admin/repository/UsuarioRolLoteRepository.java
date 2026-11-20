package oracle.admin.repository;

import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import oracle.admin.response.RolTableroResponse;
import oracle.common.support.Listas;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lecturas y escrituras en lote de usuarios_roles para el tablero de roles de un usuario:
 * una consulta para el catálogo con uso, paneles y si el actor puede otorgar cada rol, y una
 * sentencia por sentido (agregar / quitar) sin importar cuántos roles viajan.
 */
@Repository
public class UsuarioRolLoteRepository {

    private final JdbcTemplate jdbc;

    public UsuarioRolLoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Catálogo de roles visto desde el usuario. Cada agregado recorre su tabla una vez
     * (idx_usuarios_roles_rol, PK de roles_permisos) en lugar de una consulta por rol.
     * «Otorgable» replica ControlEscalamiento: el actor tiene permitidos todos los permisos
     * del rol (los inactivos nunca lo están); el actor 0 (sistema) puede todo.
     */
    public List<RolTableroResponse> tablero(Long usuarioId, Long actor) {
        return jdbc.query("""
                WITH propios AS (
                    SELECT permiso_id FROM v_permisos_finales WHERE usuario_id = ? AND permitido
                ),
                uso AS (
                    SELECT rol_id, count(*) AS usuarios, bool_or(usuario_id = ?) AS asignado
                    FROM usuarios_roles
                    GROUP BY rol_id
                ),
                contenido AS (
                    SELECT rp.rol_id, count(*) AS permisos, bool_and(pr.permiso_id IS NOT NULL) AS cubierto
                    FROM roles_permisos rp
                    LEFT JOIN propios pr ON pr.permiso_id = rp.permiso_id
                    GROUP BY rp.rol_id
                ),
                paneles AS (
                    SELECT rp.rol_id, string_agg(p.nombre, chr(31)) AS paneles
                    FROM roles_permisos rp
                    JOIN permisos p ON p.id = rp.permiso_id
                    WHERE p.activo AND p.nombre LIKE 'PANEL\\_%'
                    GROUP BY rp.rol_id
                )
                SELECT r.id, r.nombre, r.descripcion, r.activo,
                       COALESCE(u.asignado, FALSE) AS asignado,
                       COALESCE(c.permisos, 0) AS permisos,
                       COALESCE(u.usuarios, 0) AS usuarios,
                       COALESCE(pa.paneles, '') AS paneles,
                       (? = 0 OR COALESCE(c.cubierto, TRUE)) AS otorgable
                FROM roles r
                LEFT JOIN uso u ON u.rol_id = r.id
                LEFT JOIN contenido c ON c.rol_id = r.id
                LEFT JOIN paneles pa ON pa.rol_id = r.id
                ORDER BY r.nombre
                LIMIT ?
                """,
                (rs, fila) -> new RolTableroResponse(
                        rs.getLong("id"),
                        rs.getString("nombre"),
                        rs.getString("descripcion"),
                        rs.getBoolean("activo"),
                        rs.getBoolean("asignado"),
                        rs.getInt("permisos"),
                        rs.getInt("usuarios"),
                        separar(rs.getString("paneles")),
                        rs.getBoolean("otorgable")),
                actor, usuarioId, actor, Listas.MAX_FILAS);
    }

    /**
     * Paneles en un texto separado por el carácter de control 31 (no se escribe desde un
     * formulario). Con string_agg y en una CTE aparte, solo sobre las filas PANEL_*: un
     * array_agg en el mismo GROUP BY que el conteo derramaba a disco con 2 000 roles
     * (estado por grupo) y tardaba 118 ms; así son 36 ms y ningún buffer temporal.
     */
    private static List<String> separar(String paneles) {
        return paneles.isEmpty() ? List.of() : List.of(paneles.split(String.valueOf((char) 31)));
    }

    /** Ids de los roles del usuario, en orden. */
    public List<Long> asignados(Long usuarioId) {
        return jdbc.queryForList(
                "SELECT rol_id FROM usuarios_roles WHERE usuario_id = ? ORDER BY rol_id LIMIT ?",
                Long.class, usuarioId, Listas.MAX_FILAS);
    }

    /** Nombre de cada rol existente entre {@code ids} (los que no existen no aparecen). */
    public Map<Long, String> nombres(Collection<Long> ids) {
        Map<Long, String> nombres = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return nombres;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT id, nombre FROM roles WHERE id = ANY (?) ORDER BY nombre");
            ps.setArray(1, arreglo(con, ids));
            return ps;
        }, rs -> { nombres.put(rs.getLong("id"), rs.getString("nombre")); });
        return nombres;
    }

    /** Nombres de los permisos que traen esos roles, sin repetir (para el control de escalada). */
    public List<String> permisosDeRoles(Collection<Long> rolIds) {
        if (rolIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT DISTINCT p.nombre
                    FROM roles_permisos rp
                    JOIN permisos p ON p.id = rp.permiso_id
                    WHERE rp.rol_id = ANY (?)
                    ORDER BY p.nombre
                    """);
            ps.setArray(1, arreglo(con, rolIds));
            return ps;
        }, (rs, fila) -> rs.getString(1));
    }

    /** Agrega los roles al usuario; los que ya tenía se ignoran. Devuelve cuántos se agregaron. */
    public int agregar(Long usuarioId, Collection<Long> rolIds, Long actor) {
        if (rolIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO usuarios_roles (usuario_id, rol_id, creado_por)
                    SELECT ?, rol_id, ? FROM unnest(?::int8[]) AS rol_id
                    ON CONFLICT DO NOTHING
                    """);
            ps.setLong(1, usuarioId);
            ps.setLong(2, actor);
            ps.setArray(3, arreglo(con, rolIds));
            return ps;
        });
    }

    /** Quita los roles al usuario; los que no tenía se ignoran. Devuelve cuántos se quitaron. */
    public int quitar(Long usuarioId, Collection<Long> rolIds) {
        if (rolIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(con -> {
            var ps = con.prepareStatement("DELETE FROM usuarios_roles WHERE usuario_id = ? AND rol_id = ANY (?)");
            ps.setLong(1, usuarioId);
            ps.setArray(2, arreglo(con, rolIds));
            return ps;
        });
    }

    private static Array arreglo(Connection con, Collection<Long> ids) throws SQLException {
        return con.createArrayOf("int8", ids.toArray());
    }
}
