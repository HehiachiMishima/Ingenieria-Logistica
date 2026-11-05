-- Active: 1793765968991@@dpg-dblama6417fc7380aqog-a.oregon-postgres.render.com@5432@ingenieria_logistica
-- ============================================================
-- MIGRACIÓN: panel Picking (permisos)
-- ============================================================
-- Para una base que ya tiene backend/postgresql.sql aplicado. Una base nueva
-- no la necesita. Re-ejecutable. El trigger de seguridad concede cada permiso
-- nuevo al rol ADMINISTRADOR; a los demás roles se asignan desde Administración.
--
--   psql "<conexión>" -v ON_ERROR_STOP=1 -f backend/migracion_picking.sql
-- ============================================================

BEGIN;

INSERT INTO seguridad.permisos (nombre, descripcion, creado_por, actualizado_por)
SELECT nombre, descripcion, 0, 0
FROM (VALUES
    ('PANEL_PICKING',       'Permite el ingreso al panel de avance de picking.'),
    ('VER_RESUMEN_PICKING', 'Permite ver el resumen del avance de picking.'),
    ('VER_ORDENES_PICKING', 'Permite ver el control de órdenes de picking.'),
    ('VER_SKU_PICKING',     'Permite ver el detalle de picking por SKU.'),
    ('EXPORTAR_PICKING',    'Permite descargar en CSV las órdenes y los SKU de picking.')
) AS p (nombre, descripcion)
ON CONFLICT (nombre) DO NOTHING;

COMMIT;
