package oracle.admin.repository;

import java.util.List;
import oracle.admin.response.AnaliticaAdminResponse.Conteo;
import oracle.admin.response.AnaliticaAdminResponse.Kpis;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Agregados ejecutivos del RBAC resueltos en PostgreSQL sobre tablas y vistas de resolucion. */
@Repository
public class AnaliticaAdminRepository {

    private static final RowMapper<Conteo> CONTEO = (rs, n) -> new Conteo(rs.getString("etiqueta"), rs.getLong("valor"));

    private final JdbcTemplate jdbc;

    public AnaliticaAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Tres recorridos en lugar de cinco: usuarios (total y activos juntos),
     * el anti-join de usuarios sin rol y usuarios_permisos (grants y denies juntos).
     */
    public Kpis kpis() {
        return jdbc.queryForObject("""
                SELECT u.usuarios, u.usuarios_activos,
                  (SELECT COUNT(*) FROM usuarios x
                     WHERE NOT EXISTS (SELECT 1 FROM usuarios_roles ur WHERE ur.usuario_id = x.id)) AS sin_rol,
                  o.grants, o.denies
                FROM (SELECT COUNT(*) AS usuarios, COUNT(*) FILTER (WHERE activo) AS usuarios_activos
                      FROM usuarios) u
                CROSS JOIN (SELECT COUNT(*) FILTER (WHERE otorgado) AS grants,
                                   COUNT(*) FILTER (WHERE NOT otorgado) AS denies
                            FROM usuarios_permisos) o
                """, (rs, n) -> new Kpis(rs.getLong("usuarios"), rs.getLong("usuarios_activos"),
                rs.getLong("sin_rol"), rs.getLong("grants"), rs.getLong("denies")));
    }

    /** Usuarios ACTIVOS con acceso efectivo a cada panel. */
    public List<Conteo> coberturaPaneles() {
        return jdbc.query("""
                SELECT p.nombre AS etiqueta, COUNT(*) AS valor
                FROM v_permisos_finales v
                JOIN permisos p ON p.id = v.permiso_id
                JOIN usuarios u ON u.id = v.usuario_id AND u.activo = TRUE
                WHERE v.permitido = TRUE
                  AND p.nombre IN ('PANEL_ADMIN', 'PANEL_BITACORA', 'PANEL_SIMULACION',
                                   'PANEL_MALLA', 'PANEL_PICKING')
                GROUP BY p.nombre
                ORDER BY p.nombre
                """, CONTEO);
    }

}
