package oracle.admin.repository;

import java.util.List;
import oracle.admin.response.AnaliticaAdminResponse.Conteo;
import oracle.admin.response.AnaliticaAdminResponse.Kpis;
import oracle.admin.response.AnaliticaAdminResponse.Mes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Agregados ejecutivos del RBAC resueltos en PostgreSQL sobre tablas y vistas de resolucion. */
@Repository
public class AnaliticaAdminRepository {

    private static final RowMapper<Conteo> CONTEO = (rs, n) -> new Conteo(rs.getString("etiqueta"), rs.getLong("valor"));

    private final JdbcTemplate jdbc;
    /** Zona del negocio: los meses del crecimiento se cortan en hora local, no en UTC. */
    private final String zona;

    public AnaliticaAdminRepository(JdbcTemplate jdbc, @Value("${app.zona-horaria}") String zona) {
        this.jdbc = jdbc;
        this.zona = zona;
    }

    /**
     * Una sola sentencia: los conjuntos de usuarios con rol activo y con
     * excepciones se calculan una vez (CTE) y cada indicador es un conteo
     * sobre tablas chicas (usuarios, roles, permisos y sus relaciones).
     */
    public Kpis kpis() {
        return jdbc.queryForObject("""
                WITH activos AS (SELECT id FROM usuarios WHERE activo),
                con_rol AS (
                  SELECT DISTINCT ur.usuario_id
                  FROM usuarios_roles ur JOIN roles r ON r.id = ur.rol_id AND r.activo),
                con_excepcion AS (SELECT DISTINCT usuario_id FROM usuarios_permisos)
                SELECT
                  (SELECT COUNT(*) FROM usuarios) AS usuarios,
                  (SELECT COUNT(*) FROM activos) AS usuarios_activos,
                  (SELECT COUNT(*) FROM activos a
                     WHERE NOT EXISTS (SELECT 1 FROM con_rol c WHERE c.usuario_id = a.id)) AS sin_rol,
                  (SELECT COUNT(*) FROM activos a
                     WHERE EXISTS (SELECT 1 FROM con_excepcion e WHERE e.usuario_id = a.id)) AS con_excepcion,
                  (SELECT COUNT(*) FROM activos a
                     WHERE EXISTS (SELECT 1 FROM con_rol c WHERE c.usuario_id = a.id)
                       AND NOT EXISTS (SELECT 1 FROM con_excepcion e WHERE e.usuario_id = a.id)) AS solo_rol,
                  o.grants, o.denies,
                  (SELECT COUNT(DISTINCT s.usuario_id)
                     FROM sesiones_refresh s JOIN usuarios u ON u.id = s.usuario_id AND u.activo
                     WHERE s.revocado_en IS NULL AND s.expira_en > now()) AS conectados,
                  (SELECT COUNT(*) FROM usuarios
                     WHERE creado_en >= now() - INTERVAL '30 days') AS altas_30,
                  (SELECT COUNT(*) FROM usuarios
                     WHERE creado_en >= now() - INTERVAL '60 days'
                       AND creado_en < now() - INTERVAL '30 days') AS altas_previas_30,
                  r.roles, r.roles_activos,
                  (SELECT COUNT(*) FROM roles x WHERE x.activo AND NOT EXISTS (
                     SELECT 1 FROM usuarios_roles ur JOIN usuarios u ON u.id = ur.usuario_id AND u.activo
                     WHERE ur.rol_id = x.id)) AS roles_sin_usuarios,
                  (SELECT COUNT(*) FROM roles x WHERE x.activo AND NOT EXISTS (
                     SELECT 1 FROM roles_permisos rp WHERE rp.rol_id = x.id)) AS roles_sin_permisos,
                  p.permisos, p.permisos_activos
                FROM (SELECT COUNT(*) FILTER (WHERE otorgado) AS grants,
                             COUNT(*) FILTER (WHERE NOT otorgado) AS denies
                      FROM usuarios_permisos) o
                CROSS JOIN (SELECT COUNT(*) AS roles, COUNT(*) FILTER (WHERE activo) AS roles_activos
                            FROM roles) r
                CROSS JOIN (SELECT COUNT(*) AS permisos, COUNT(*) FILTER (WHERE activo) AS permisos_activos
                            FROM permisos) p
                """, (rs, n) -> new Kpis(
                rs.getLong("usuarios"), rs.getLong("usuarios_activos"), rs.getLong("sin_rol"),
                rs.getLong("con_excepcion"), rs.getLong("solo_rol"), rs.getLong("grants"), rs.getLong("denies"),
                rs.getLong("conectados"), rs.getLong("altas_30"), rs.getLong("altas_previas_30"),
                rs.getLong("roles"), rs.getLong("roles_activos"), rs.getLong("roles_sin_usuarios"),
                rs.getLong("roles_sin_permisos"), rs.getLong("permisos"), rs.getLong("permisos_activos")));
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
                                   'PANEL_MALLA', 'PANEL_PICKING', 'PANEL_ANALISIS',
                                   'PANEL_TRAZABILIDAD', 'PANEL_LOGISTICA',
                                   'PANEL_AUTOMATIZACION', 'PANEL_SINCRONIZACION')
                GROUP BY p.nombre
                ORDER BY p.nombre
                """, CONTEO);
    }

    /** Usuarios ACTIVOS por rol activo (incluye los roles sin nadie, con 0). */
    public List<Conteo> usuariosPorRol() {
        return jdbc.query("""
                SELECT r.nombre AS etiqueta, COUNT(u.id) AS valor
                FROM roles r
                LEFT JOIN usuarios_roles ur ON ur.rol_id = r.id
                LEFT JOIN usuarios u ON u.id = ur.usuario_id AND u.activo
                WHERE r.activo
                GROUP BY r.nombre
                ORDER BY valor DESC, r.nombre
                """, CONTEO);
    }

    /**
     * Últimos 12 meses (hora local): altas del mes y total registrado al cierre.
     * Los límites se pasan a timestamptz para que filtren por el índice de creado_en.
     */
    public List<Mes> crecimiento() {
        return jdbc.query("""
                WITH meses AS (
                  SELECT m AS inicio, m + INTERVAL '1 month' AS fin
                  FROM generate_series(
                         date_trunc('month', now() AT TIME ZONE ?) - INTERVAL '11 months',
                         date_trunc('month', now() AT TIME ZONE ?),
                         INTERVAL '1 month') AS m)
                SELECT to_char(m.inicio, 'YYYY-MM') AS periodo,
                  (SELECT COUNT(*) FROM usuarios u
                     WHERE u.creado_en >= (m.inicio AT TIME ZONE ?)
                       AND u.creado_en < (m.fin AT TIME ZONE ?)) AS altas,
                  (SELECT COUNT(*) FROM usuarios u
                     WHERE u.creado_en < (m.fin AT TIME ZONE ?)) AS total
                FROM meses m
                ORDER BY m.inicio
                """, (rs, n) -> new Mes(rs.getString("periodo"), rs.getLong("altas"), rs.getLong("total")),
                zona, zona, zona, zona, zona);
    }
}
