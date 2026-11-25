-- Simulación Titan: las rutas se crean y se ordenan en Configuración. El registro por Excel y el
-- módulo Registro (diccionario de rutas) desaparecen. Una sola migración, en una transacción:
--
--   1. Locales de cada ruta (simulacion.rutas_locales): el detalle que se ve al hacer clic en una
--      ruta.
--   2. Se quita el Registro (diccionario de rutas): sus tablas y el enlace con el escenario.
--   3. El escenario único de Configuración y el Dashboard (archivos_excel.es_unico), que el
--      backend crea solo. Los registros subidos por Excel se BORRAN con todo lo que cuelga de
--      ellos (rutas, parámetros, operarios, balanceos e historial).
--   4. DATA: el bloque de Configuración con las rutas que no se balancean. Son rutas del escenario
--      sin cluster; ahí reciben tipo, frecuencia y grupo, y solo completas pasan a un cluster.
--
-- Es re-ejecutable: crea lo que falta, pone al día lo que ya existía de versiones anteriores y
-- nunca borra el escenario único. Se aplica ANTES de reiniciar el backend. Requiere PostgreSQL 15
-- o superior (UNIQUE NULLS NOT DISTINCT).
--
--   psql -d ingenieria_logistica -v ON_ERROR_STOP=1 -f backend/migracion_registro_titan.sql

BEGIN;

-- =============================================================================================
-- 1. Locales de cada ruta
-- =============================================================================================

CREATE TABLE IF NOT EXISTS simulacion.rutas_locales (
    ruta_id   BIGINT        NOT NULL REFERENCES simulacion.rutas_proyeccion (id) ON DELETE CASCADE,
    orden     INTEGER       NOT NULL,
    cod       VARCHAR(100),
    filas     INTEGER       NOT NULL DEFAULT 1,
    bultos    NUMERIC(12,2) NOT NULL DEFAULT 0,
    unidades  NUMERIC(12,2) NOT NULL DEFAULT 0,
    lineas    NUMERIC(12,2) NOT NULL DEFAULT 0,
    -- Q: detalle de una ruta (WHERE ruta_id = ? ORDER BY orden) y la FK hacia la ruta.
    CONSTRAINT pk_rutas_locales PRIMARY KEY (ruta_id, orden),
    CONSTRAINT ck_rutas_locales CHECK (
        orden > 0 AND filas > 0 AND bultos >= 0 AND unidades >= 0 AND lineas >= 0)
);
COMMENT ON TABLE simulacion.rutas_locales IS 'Locales de cada ruta del escenario (el código repetido se suma); los totales de la ruta son su suma.';
COMMENT ON COLUMN simulacion.rutas_locales.orden IS 'Orden del local dentro de su ruta.';
COMMENT ON COLUMN simulacion.rutas_locales.cod IS 'Código del local.';
COMMENT ON COLUMN simulacion.rutas_locales.filas IS 'Veces que se ingresó ese código en la ruta (más de una: se sumaron).';

-- =============================================================================================
-- 2. Se quita el Registro (diccionario de rutas)
-- =============================================================================================

-- Sus índices (uq_rutas_diccionario, ix_rutas_diccionario) se van con la columna.
ALTER TABLE simulacion.rutas_proyeccion DROP COLUMN IF EXISTS diccionario_ruta_id;
DROP TABLE IF EXISTS simulacion.diccionario_locales;
DROP TABLE IF EXISTS simulacion.diccionario_rutas;
DROP TABLE IF EXISTS simulacion.diccionario_clusters;

-- =============================================================================================
-- 3. El escenario único
-- =============================================================================================

DO $$
BEGIN
    -- La versión anterior lo llamaba es_diccionario: se renombra (conserva el escenario).
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'simulacion' AND table_name = 'archivos_excel' AND column_name = 'es_diccionario')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = 'simulacion' AND table_name = 'archivos_excel' AND column_name = 'es_unico') THEN
        ALTER TABLE simulacion.archivos_excel RENAME COLUMN es_diccionario TO es_unico;
    END IF;
END $$;
ALTER TABLE simulacion.archivos_excel ADD COLUMN IF NOT EXISTS es_unico BOOLEAN NOT NULL DEFAULT FALSE;
DROP INDEX IF EXISTS simulacion.uq_archivos_es_diccionario;
-- Q: escenario único (WHERE es_unico); también impide que haya dos.
CREATE UNIQUE INDEX IF NOT EXISTS uq_archivos_es_unico
    ON simulacion.archivos_excel (es_unico) WHERE es_unico;
COMMENT ON COLUMN simulacion.archivos_excel.es_unico IS 'TRUE en el único escenario de Configuración y el Dashboard (lo crea el backend).';

-- Los registros por Excel se borran (en cascada: rutas, locales, parámetros, operarios,
-- lanzamientos e historial). El escenario único se conserva.
DELETE FROM simulacion.archivos_excel WHERE NOT es_unico;

-- =============================================================================================
-- 4. DATA: rutas del escenario sin cluster
-- =============================================================================================

-- En DATA una ruta no tiene cluster (ni original) y puede no tener tipo, frecuencia ni grupo; su
-- chute es la columna donde se ve. En un cluster está completa.
ALTER TABLE simulacion.rutas_proyeccion ALTER COLUMN cluster_id DROP NOT NULL;
ALTER TABLE simulacion.rutas_proyeccion ALTER COLUMN cluster_original_id DROP NOT NULL;
ALTER TABLE simulacion.rutas_proyeccion ALTER COLUMN tipo DROP NOT NULL;
ALTER TABLE simulacion.rutas_proyeccion ALTER COLUMN frecuencia DROP NOT NULL;
ALTER TABLE simulacion.rutas_proyeccion ALTER COLUMN grupo DROP NOT NULL;

DO $$
BEGIN
    -- cluster_cambiado con «<>» da NULL en DATA (sin cluster): pasa a IS DISTINCT FROM (FALSE en
    -- DATA). Una columna generada no cambia de expresión en PostgreSQL 15/16: se vuelve a crear.
    IF NOT EXISTS (SELECT 1 FROM pg_attrdef d
                   JOIN pg_attribute a ON a.attrelid = d.adrelid AND a.attnum = d.adnum
                   WHERE d.adrelid = 'simulacion.rutas_proyeccion'::regclass AND a.attname = 'cluster_cambiado'
                     AND pg_get_expr(d.adbin, d.adrelid) LIKE '%DISTINCT%') THEN
        ALTER TABLE simulacion.rutas_proyeccion DROP COLUMN IF EXISTS cluster_cambiado;
        ALTER TABLE simulacion.rutas_proyeccion ADD COLUMN cluster_cambiado BOOLEAN
            GENERATED ALWAYS AS (cluster_id IS DISTINCT FROM cluster_original_id) STORED;
    END IF;
    -- Sin tipo (en DATA) el número tampoco se repite.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'simulacion.rutas_proyeccion'::regclass AND conname = 'uq_rutas_archivo_ruta_tipo'
                     AND pg_get_constraintdef(oid) LIKE '%NULLS NOT DISTINCT%') THEN
        ALTER TABLE simulacion.rutas_proyeccion DROP CONSTRAINT IF EXISTS uq_rutas_archivo_ruta_tipo;
        ALTER TABLE simulacion.rutas_proyeccion ADD CONSTRAINT uq_rutas_archivo_ruta_tipo
            UNIQUE NULLS NOT DISTINCT (archivo_id, codigo_ruta, tipo);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'simulacion.rutas_proyeccion'::regclass AND conname = 'ck_rutas_data') THEN
        ALTER TABLE simulacion.rutas_proyeccion ADD CONSTRAINT ck_rutas_data CHECK (
            (cluster_id IS NULL AND cluster_original_id IS NULL)
            OR (cluster_id IS NOT NULL AND cluster_original_id IS NOT NULL
                AND tipo IS NOT NULL AND frecuencia IS NOT NULL AND grupo IS NOT NULL));
    END IF;
END $$;
-- Q: rutas cambiadas de cluster (historial y dashboard); se recrea si la columna se volvió a crear.
CREATE INDEX IF NOT EXISTS idx_rutas_cluster_cambiado
    ON simulacion.rutas_proyeccion (archivo_id, cluster_original_id, cluster_id) WHERE cluster_cambiado;
COMMENT ON COLUMN simulacion.rutas_proyeccion.cluster_id IS 'Cluster de hoy; NULL = la ruta está en DATA (no se balancea).';
COMMENT ON COLUMN simulacion.rutas_proyeccion.cluster_original_id IS 'Cluster al pasar de DATA (el «antes» del Dashboard); un cambio manual cambia cluster_id. NULL en DATA.';
COMMENT ON COLUMN simulacion.rutas_proyeccion.chute_original_id IS 'Chute al pasar de DATA (el «antes»); el balanceo no lo cambia. En DATA, igual a chute_id.';
COMMENT ON COLUMN simulacion.rutas_proyeccion.cluster_cambiado IS 'Calculada: la ruta está hoy en un cluster distinto al original (FALSE en DATA).';
COMMENT ON COLUMN simulacion.rutas_proyeccion.tipo IS 'B2C o B2B; puede faltar solo en DATA.';
COMMENT ON COLUMN simulacion.rutas_proyeccion.frecuencia IS 'Días de la ruta: DIARIA (L-M-X-J-V-S) aparece en los dos balanceos; LXV solo en L-X-V; MJS solo en M-J-S. Puede faltar solo en DATA.';
COMMENT ON COLUMN simulacion.rutas_proyeccion.grupo IS 'Grupo de la ruta dentro de su cluster; el balanceo automático reparte cada grupo en su propio tramo de chutes. Puede faltar solo en DATA.';

-- =============================================================================================
-- Privilegios de la aplicación
-- =============================================================================================

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'atlas_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON simulacion.rutas_locales TO atlas_app;
    END IF;
END $$;

COMMIT;
