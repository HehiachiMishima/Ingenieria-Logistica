package oracle.admin.repository;

import java.sql.Array;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import oracle.admin.response.PermisoTableroResponse;
import oracle.common.support.Listas;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lecturas y escrituras en lote de roles_permisos para el tablero de permisos
 * de un rol: una sola consulta para el catálogo, lo asignado y el uso, y una
 * sentencia por sentido (agregar / quitar) sin importar cuántos permisos viajan.
 */
@Repository
public class RolPermisoLoteRepository {

    private final JdbcTemplate jdbc;

    public RolPermisoLoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Catálogo completo con, por permiso, si el rol lo tiene y en cuántos roles está.
     * El uso se agrega una vez sobre idx_roles_permisos_permiso (permiso_id, rol_id),
     * sin una consulta por permiso.
     */
    public List<PermisoTableroResponse> tablero(Long rolId) {
        return jdbc.query("""
                SELECT p.id, p.nombre, p.descripcion, p.activo,
                       COALESCE(u.asignado, FALSE) AS asignado,
                       COALESCE(u.roles, 0) AS roles
                FROM permisos p
                LEFT JOIN (
                    SELECT permiso_id, count(*) AS roles, bool_or(rol_id = ?) AS asignado
                    FROM roles_permisos
                    GROUP BY permiso_id
                ) u ON u.permiso_id = p.id
                ORDER BY p.nombre
                LIMIT ?
                """,
                (rs, fila) -> new PermisoTableroResponse(
                        rs.getLong("id"),
                        rs.getString("nombre"),
                        rs.getString("descripcion"),
                        rs.getBoolean("activo"),
                        rs.getBoolean("asignado"),
                        rs.getInt("roles")),
                rolId, Listas.MAX_FILAS);
    }

    /** Ids de los permisos del rol, en orden. */
    public List<Long> asignados(Long rolId) {
        return jdbc.queryForList(
                "SELECT permiso_id FROM roles_permisos WHERE rol_id = ? ORDER BY permiso_id LIMIT ?",
                Long.class, rolId, Listas.MAX_FILAS);
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

    /** Agrega los permisos al rol; los que ya tenía se ignoran. Devuelve cuántos se agregaron. */
    public int agregar(Long rolId, Collection<Long> permisoIds, Long actor) {
        if (permisoIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO roles_permisos (rol_id, permiso_id, creado_por)
                    SELECT ?, permiso_id, ? FROM unnest(?::int8[]) AS permiso_id
                    ON CONFLICT DO NOTHING
                    """);
            ps.setLong(1, rolId);
            ps.setLong(2, actor);
            ps.setArray(3, arreglo(con, permisoIds));
            return ps;
        });
    }

    /** Quita los permisos del rol; los que no tenía se ignoran. Devuelve cuántos se quitaron. */
    public int quitar(Long rolId, Collection<Long> permisoIds) {
        if (permisoIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(con -> {
            var ps = con.prepareStatement("DELETE FROM roles_permisos WHERE rol_id = ? AND permiso_id = ANY (?)");
            ps.setLong(1, rolId);
            ps.setArray(2, arreglo(con, permisoIds));
            return ps;
        });
    }

    private static Array arreglo(java.sql.Connection con, Collection<Long> ids) throws java.sql.SQLException {
        return con.createArrayOf("int8", ids.toArray());
    }
}
