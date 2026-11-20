package oracle.admin.repository;

import java.sql.Array;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import oracle.admin.response.PermisoTableroResponse;
import oracle.admin.response.RolDelPermisoResponse;
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

    // ---------- Desde el permiso: a qué roles se da ----------

    /**
     * Catálogo de roles visto desde un permiso: si lo concede, cuántos permisos y usuarios tiene,
     * sus paneles y si es el rol protegido. Conteos en agregados simples y paneles en su propia
     * CTE con string_agg (un array_agg en el mismo GROUP BY derrama a disco con miles de roles).
     */
    public List<RolDelPermisoResponse> rolesDelTablero(Long permisoId) {
        return jdbc.query("""
                WITH uso AS (
                    SELECT rol_id, count(*) AS usuarios FROM usuarios_roles GROUP BY rol_id
                ),
                contenido AS (
                    SELECT rol_id, count(*) AS permisos, bool_or(permiso_id = ?) AS asignado
                    FROM roles_permisos
                    GROUP BY rol_id
                ),
                paneles AS (
                    SELECT rp.rol_id, string_agg(p.nombre, chr(31)) AS paneles
                    FROM roles_permisos rp
                    JOIN permisos p ON p.id = rp.permiso_id
                    WHERE p.activo AND p.nombre LIKE 'PANEL\\_%'
                    GROUP BY rp.rol_id
                )
                SELECT r.id, r.nombre, r.descripcion, r.activo,
                       COALESCE(c.asignado, FALSE) AS asignado,
                       COALESCE(c.permisos, 0) AS permisos,
                       COALESCE(u.usuarios, 0) AS usuarios,
                       COALESCE(pa.paneles, '') AS paneles,
                       es_rol_protegido(r.nombre) AS protegido
                FROM roles r
                LEFT JOIN uso u ON u.rol_id = r.id
                LEFT JOIN contenido c ON c.rol_id = r.id
                LEFT JOIN paneles pa ON pa.rol_id = r.id
                ORDER BY r.nombre
                LIMIT ?
                """,
                (rs, fila) -> new RolDelPermisoResponse(
                        rs.getLong("id"),
                        rs.getString("nombre"),
                        rs.getString("descripcion"),
                        rs.getBoolean("activo"),
                        rs.getBoolean("asignado"),
                        rs.getInt("permisos"),
                        rs.getInt("usuarios"),
                        separar(rs.getString("paneles")),
                        rs.getBoolean("protegido")),
                permisoId, Listas.MAX_FILAS);
    }

    /**
     * Usuarios distintos que tienen el permiso por algún rol activo (sin contar excepciones).
     * Distinto en dos niveles (DISTINCT y luego count) para que sea un HashAggregate sin ordenar;
     * recorre idx_roles_permisos_permiso e idx_usuarios_roles_rol.
     */
    public int usuariosAlcanzados(Long permisoId) {
        Integer total = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT DISTINCT ur.usuario_id
                    FROM roles_permisos rp
                    JOIN roles r ON r.id = rp.rol_id AND r.activo
                    JOIN usuarios_roles ur ON ur.rol_id = rp.rol_id
                    WHERE rp.permiso_id = ?
                ) alcanzados
                """, Integer.class, permisoId);
        return total == null ? 0 : total;
    }

    /** Ids de los roles que tienen el permiso, en orden. */
    public List<Long> rolesConPermiso(Long permisoId) {
        return jdbc.queryForList(
                "SELECT rol_id FROM roles_permisos WHERE permiso_id = ? ORDER BY rol_id LIMIT ?",
                Long.class, permisoId, Listas.MAX_FILAS);
    }

    /** Nombre de cada rol existente entre {@code ids} (los que no existen no aparecen). */
    public Map<Long, String> nombresDeRoles(Collection<Long> ids) {
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

    /** Da el permiso a esos roles; los que ya lo tenían se ignoran. Devuelve cuántos se agregaron. */
    public int darARoles(Long permisoId, Collection<Long> rolIds, Long actor) {
        if (rolIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO roles_permisos (rol_id, permiso_id, creado_por)
                    SELECT rol_id, ?, ? FROM unnest(?::int8[]) AS rol_id
                    ON CONFLICT DO NOTHING
                    """);
            ps.setLong(1, permisoId);
            ps.setLong(2, actor);
            ps.setArray(3, arreglo(con, rolIds));
            return ps;
        });
    }

    /** Quita el permiso a esos roles; los que no lo tenían se ignoran. Devuelve cuántos se quitaron. */
    public int quitarARoles(Long permisoId, Collection<Long> rolIds) {
        if (rolIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(con -> {
            var ps = con.prepareStatement("DELETE FROM roles_permisos WHERE permiso_id = ? AND rol_id = ANY (?)");
            ps.setLong(1, permisoId);
            ps.setArray(2, arreglo(con, rolIds));
            return ps;
        });
    }

    private static List<String> separar(String paneles) {
        return paneles.isEmpty() ? List.of() : List.of(paneles.split(String.valueOf((char) 31)));
    }

    private static Array arreglo(java.sql.Connection con, Collection<Long> ids) throws java.sql.SQLException {
        return con.createArrayOf("int8", ids.toArray());
    }
}
