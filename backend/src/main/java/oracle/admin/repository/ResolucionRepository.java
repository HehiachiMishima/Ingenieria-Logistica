package oracle.admin.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import oracle.admin.response.PermisoEfectivoResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Consultas de resolucion de permisos sobre las vistas de PostgreSQL
 * (v_permisos_efectivos y v_permisos_finales, esquema seguridad).
 * Al ser vistas (no entidades), se leen con JdbcTemplate y se mapean
 * directamente a las respuestas, evitando el problema de identidad de una
 * entidad JPA cuando la vista devuelve filas con la misma clave.
 */
@Repository
public class ResolucionRepository {

    private static final RowMapper<PermisoEfectivoResponse> EFECTIVO = (rs, fila) ->
            new PermisoEfectivoResponse(
                    rs.getLong("usuario_id"),
                    rs.getLong("permiso_id"),
                    rs.getBoolean("otorgado"),
                    rs.getString("origen"));

    private final JdbcTemplate jdbc;

    public ResolucionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PermisoEfectivoResponse> efectivos(Long usuarioId) {
        return jdbc.query("""
                SELECT usuario_id, permiso_id, otorgado, origen
                FROM v_permisos_efectivos
                WHERE usuario_id = ?
                ORDER BY permiso_id, origen
                """, EFECTIVO, usuarioId);
    }

    public List<String> nombresPermitidos(Long usuarioId) {
        return jdbc.queryForList("""
                SELECT p.nombre
                FROM v_permisos_finales v
                JOIN permisos p ON p.id = v.permiso_id
                WHERE v.usuario_id = ? AND v.permitido = TRUE
                ORDER BY p.nombre
                """, String.class, usuarioId);
    }

    /**
     * Roles por usuario en una sola consulta (usuario_id -> nombres), para el
     * listado de usuarios sin una peticion por fila.
     */
    public Map<Long, List<String>> rolesPorUsuario() {
        return agrupar("""
                SELECT ur.usuario_id AS usuario_id, r.nombre AS nombre
                FROM usuarios_roles ur
                JOIN roles r ON r.id = ur.rol_id
                ORDER BY ur.usuario_id, r.nombre
                """);
    }

    /** Permisos efectivos (permitido = TRUE) por usuario en una sola consulta. */
    public Map<Long, List<String>> permitidosPorUsuario() {
        return agrupar("""
                SELECT v.usuario_id AS usuario_id, p.nombre AS nombre
                FROM v_permisos_finales v
                JOIN permisos p ON p.id = v.permiso_id
                WHERE v.permitido = TRUE
                ORDER BY v.usuario_id, p.nombre
                """);
    }

    private Map<Long, List<String>> agrupar(String sql) {
        Map<Long, List<String>> mapa = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            mapa.computeIfAbsent(rs.getLong("usuario_id"), k -> new ArrayList<>()).add(rs.getString("nombre"));
        });
        return mapa;
    }
}
