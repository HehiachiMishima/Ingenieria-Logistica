-- ============================================================
-- MIGRACIÓN: cambio de cluster de una ruta (Simulación)
-- ============================================================
-- Para una base que ya tiene la estructura de backend/postgresql.sql y datos
-- que se quieren conservar. Una base nueva no la necesita: postgresql.sql ya
-- trae estos cambios.
--
--   psql "<conexión>" -v ON_ERROR_STOP=1 -f backend/migracion_cambio_cluster.sql
--
-- Aplícala ANTES de desplegar el backend nuevo: con ddl-auto=validate, el
-- backend no arranca si faltan las columnas. Re-ejecutable y en una sola
-- transacción. Las rutas existentes quedan con cluster_original_id = cluster_id
-- (ninguna figura como cambiada).
-- ============================================================

BEGIN;

SET LOCAL client_min_messages = warning;

-- 1. Cluster original de cada ruta.
ALTER TABLE simulacion.rutas_proyeccion
    ADD COLUMN IF NOT EXISTS cluster_original_id BIGINT;

UPDATE simulacion.rutas_proyeccion
   SET cluster_original_id = cluster_id
 WHERE cluster_original_id IS NULL;

ALTER TABLE simulacion.rutas_proyeccion
    ALTER COLUMN cluster_original_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'rutas_proyeccion_cluster_original_id_fkey') THEN
        ALTER TABLE simulacion.rutas_proyeccion
            ADD CONSTRAINT rutas_proyeccion_cluster_original_id_fkey
            FOREIGN KEY (cluster_original_id) REFERENCES simulacion.clusters (id);
    END IF;
END;
$$;

ALTER TABLE simulacion.rutas_proyeccion
    ADD COLUMN IF NOT EXISTS cluster_cambiado BOOLEAN
        GENERATED ALWAYS AS (cluster_id <> cluster_original_id) STORED;

CREATE INDEX IF NOT EXISTS idx_rutas_cluster_cambiado
    ON simulacion.rutas_proyeccion (archivo_id, cluster_original_id, cluster_id)
    WHERE cluster_cambiado;

COMMENT ON COLUMN simulacion.rutas_proyeccion.cluster_original_id IS 'Cluster al cargar el Excel; solo un cambio manual (antes de balancear) cambia cluster_id.';
COMMENT ON COLUMN simulacion.rutas_proyeccion.cluster_cambiado IS 'Calculada: la ruta está hoy en un cluster distinto al original.';

-- 2. Historial: clusters del cambio y el nuevo tipo de acción.
ALTER TABLE simulacion.historial_balanceo
    ADD COLUMN IF NOT EXISTS cluster_origen_id BIGINT REFERENCES simulacion.clusters (id),
    ADD COLUMN IF NOT EXISTS cluster_destino_id BIGINT REFERENCES simulacion.clusters (id);

ALTER TABLE simulacion.historial_balanceo DROP CONSTRAINT IF EXISTS ck_historial_accion;
ALTER TABLE simulacion.historial_balanceo
    ADD CONSTRAINT ck_historial_accion
    CHECK (tipo_accion IN ('MOVER_RUTA', 'ASIGNAR_OPERARIOS', 'CAMBIAR_CLUSTER'));

ALTER TABLE simulacion.historial_balanceo DROP CONSTRAINT IF EXISTS ck_historial_cambio_cluster;
ALTER TABLE simulacion.historial_balanceo
    ADD CONSTRAINT ck_historial_cambio_cluster CHECK (
        tipo_accion <> 'CAMBIAR_CLUSTER'
        OR (lanzamiento_id IS NULL AND motivo = 'MANUAL'
            AND cluster_origen_id IS NOT NULL AND cluster_destino_id IS NOT NULL
            AND cluster_origen_id <> cluster_destino_id));

COMMIT;
