-- Active: 1793765968991@@dpg-dblama6417fc7380aqog-a.oregon-postgres.render.com@5432@ingenieria_logistica
-- ============================================================
-- MIGRACIÓN: exportar en Administración (permisos)
-- ============================================================
-- Para una base que ya tiene backend/postgresql.sql aplicado. Una base nueva
-- no la necesita. Re-ejecutable. El trigger de seguridad concede cada permiso
-- nuevo al rol ADMINISTRADOR; a los demás roles se asignan desde Administración.
--
--   psql "<conexión>" -v ON_ERROR_STOP=1 -f backend/migracion_exportar_admin.sql
-- ============================================================

BEGIN;

INSERT INTO seguridad.permisos (nombre, descripcion, creado_por, actualizado_por)
SELECT nombre, descripcion, 0, 0
FROM (VALUES
    ('EXPORTAR_USUARIOS', 'Permite descargar en CSV el listado de usuarios.'),
    ('EXPORTAR_ROLES',    'Permite descargar en CSV el listado de roles.'),
    ('EXPORTAR_PERMISOS', 'Permite descargar en CSV el listado de permisos.')
) AS p (nombre, descripcion)
ON CONFLICT (nombre) DO NOTHING;

COMMIT;
