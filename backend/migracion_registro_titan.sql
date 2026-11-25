-- Simulación Titan: el Registro (diccionario de rutas) reemplaza al registro por Excel.
-- Una sola migración con todo lo necesario, en una transacción:
--
--   1. Locales de las rutas de Configuración (simulacion.rutas_locales): el detalle que se ve al
--      hacer clic en una ruta.
--   2. El Registro: clusters, rutas y locales del diccionario. Una ruta en «Datos» (fuera de todo
--      cluster) no tiene cluster, tipo, frecuencia ni grupo: la base lo exige. Tipo, frecuencia y
--      grupo se asignan solo a rutas que ya están en un cluster; volver a Datos los vacía.
--   3. El escenario único de Configuración y el Dashboard (archivos_excel.es_diccionario), que el
--      backend crea solo y llena con las rutas COMPLETAS del Registro (cluster, tipo, frecuencia y
--      grupo), cada una con un chute al azar que se fija la primera vez. Los registros subidos por
--      Excel se BORRAN con todo lo que cuelga de ellos (rutas, parámetros, operarios, balanceos e
--      historial).
--
-- Es re-ejecutable: crea lo que falta, pone al día lo que ya existía de versiones anteriores sin
-- perder datos del Registro y nunca borra el escenario del Registro. Se aplica ANTES de reiniciar
-- el backend que trae el Registro. Reemplaza a migracion_locales_rutas.sql,
-- migracion_diccionario_rutas.sql y migracion_registro_diccionario.sql.
--
--   psql -d ingenieria_logistica -v ON_ERROR_STOP=1 -f backend/migracion_registro_titan.sql

BEGIN;

-- =============================================================================================
-- 1. Locales de las rutas de Configuración
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
COMMENT ON TABLE simulacion.rutas_locales IS 'Locales de cada ruta del escenario (salen del Registro; el código repetido se suma).';
COMMENT ON COLUMN simulacion.rutas_locales.orden IS 'Orden del local dentro de su ruta.';
COMMENT ON COLUMN simulacion.rutas_locales.cod IS 'Código del local.';
COMMENT ON COLUMN simulacion.rutas_locales.filas IS 'Veces que se ingresó ese código en la ruta (más de una: se sumaron).';

-- =============================================================================================
-- 2. El Registro: diccionario de rutas
-- =============================================================================================

-- Clusters del Registro: bloques CL1…CLn. Se agregan o quitan desde el final (orden).
CREATE TABLE IF NOT EXISTS simulacion.diccionario_clusters (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    orden     INTEGER      NOT NULL,
    nombre    VARCHAR(50)  NOT NULL,
    creado_en TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Q: lista de clusters (ORDER BY orden) y quitar los últimos (WHERE orden > ?).
    CONSTRAINT uq_dicc_clusters_orden UNIQUE (orden),
    CONSTRAINT ck_dicc_clusters_orden CHECK (orden > 0)
);
COMMENT ON TABLE simulacion.diccionario_clusters IS 'Registro: bloques de cluster del diccionario de rutas (CL1…CLn).';

-- Rutas del Registro: número y tipo las identifican; tipo, cluster, frecuencia y grupo empiezan
-- vacíos. Sin tipo (en Datos), el número tampoco se repite (NULLS NOT DISTINCT).
CREATE TABLE IF NOT EXISTS simulacion.diccionario_rutas (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo_ruta     VARCHAR(50)  COLLATE "C" NOT NULL,
    tipo            VARCHAR(10),
    cluster_id      BIGINT       REFERENCES simulacion.diccionario_clusters (id),
    frecuencia      VARCHAR(10),
    grupo           VARCHAR(50),
    creado_por      BIGINT       NOT NULL,
    actualizado_por BIGINT       NOT NULL,
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actualizado_en  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Q: la ruta no se repite (crear/editar) y la lista ordenada por número y tipo.
    CONSTRAINT uq_dicc_rutas_codigo_tipo UNIQUE NULLS NOT DISTINCT (codigo_ruta, tipo),
    CONSTRAINT ck_dicc_rutas_tipo CHECK (tipo IN ('B2C', 'B2B')),
    CONSTRAINT ck_dicc_rutas_frecuencia CHECK (frecuencia IN ('LXV', 'MJS', 'DIARIA')),
    CONSTRAINT ck_dicc_rutas_grupo CHECK (grupo IS NULL OR btrim(grupo) <> ''),
    CONSTRAINT ck_dicc_rutas_ubicacion
        CHECK (cluster_id IS NOT NULL OR (tipo IS NULL AND frecuencia IS NULL AND grupo IS NULL))
);

-- Puesta al día si la tabla ya existía de una versión anterior (tipo obligatorio, sin grupo,
-- Datos con tipo): el tipo pasa a opcional, una ruta sin tipo tampoco se repite y en Datos no hay
-- tipo. Sin efecto si ya está.
ALTER TABLE simulacion.diccionario_rutas ADD COLUMN IF NOT EXISTS grupo VARCHAR(50);
ALTER TABLE simulacion.diccionario_rutas ALTER COLUMN tipo DROP NOT NULL;
DO $$
BEGIN
    -- La unicidad vieja (NULLS DISTINCT) dejaba repetir un número sin tipo: se reemplaza.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'simulacion.diccionario_rutas'::regclass AND conname = 'uq_dicc_rutas_codigo_tipo'
                     AND pg_get_constraintdef(oid) LIKE '%NULLS NOT DISTINCT%') THEN
        ALTER TABLE simulacion.diccionario_rutas DROP CONSTRAINT IF EXISTS uq_dicc_rutas_codigo_tipo;
        ALTER TABLE simulacion.diccionario_rutas
            ADD CONSTRAINT uq_dicc_rutas_codigo_tipo UNIQUE NULLS NOT DISTINCT (codigo_ruta, tipo);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'simulacion.diccionario_rutas'::regclass AND conname = 'ck_dicc_rutas_grupo') THEN
        ALTER TABLE simulacion.diccionario_rutas
            ADD CONSTRAINT ck_dicc_rutas_grupo CHECK (grupo IS NULL OR btrim(grupo) <> '');
    END IF;
    -- La regla de Datos incluye el tipo y el grupo. Las rutas que ya estén en Datos con tipo lo
    -- pierden; si dos quedaran con el mismo número sin tipo, se detiene sin cambiar nada.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'simulacion.diccionario_rutas'::regclass AND conname = 'ck_dicc_rutas_ubicacion'
                     AND pg_get_constraintdef(oid) LIKE '%tipo IS NULL%') THEN
        IF EXISTS (SELECT 1 FROM simulacion.diccionario_rutas WHERE cluster_id IS NULL
                   GROUP BY codigo_ruta HAVING count(*) > 1) THEN
            RAISE EXCEPTION 'Hay rutas con el mismo número en Datos y distinto tipo: déjalas en un cluster o elimina una antes de migrar.';
        END IF;
        UPDATE simulacion.diccionario_rutas SET tipo = NULL WHERE cluster_id IS NULL AND tipo IS NOT NULL;
        ALTER TABLE simulacion.diccionario_rutas DROP CONSTRAINT IF EXISTS ck_dicc_rutas_ubicacion;
        ALTER TABLE simulacion.diccionario_rutas ADD CONSTRAINT ck_dicc_rutas_ubicacion
            CHECK (cluster_id IS NOT NULL OR (tipo IS NULL AND frecuencia IS NULL AND grupo IS NULL));
    END IF;
END $$;
-- Q: al quitar clusters, sus rutas vuelven a Datos (WHERE cluster_id IN (…)); y la FK.
CREATE INDEX IF NOT EXISTS ix_dicc_rutas_cluster
    ON simulacion.diccionario_rutas (cluster_id) WHERE cluster_id IS NOT NULL;
COMMENT ON TABLE simulacion.diccionario_rutas IS 'Registro: diccionario de rutas. Sin cluster (bloque Datos) no hay tipo, frecuencia ni grupo.';
COMMENT ON COLUMN simulacion.diccionario_rutas.tipo IS 'B2C o B2B; NULL hasta asignarlo (y siempre NULL en Datos). Se conserva al pasar a otro cluster.';
COMMENT ON COLUMN simulacion.diccionario_rutas.cluster_id IS 'Bloque de cluster; NULL = en Datos.';
COMMENT ON COLUMN simulacion.diccionario_rutas.frecuencia IS 'LXV, MJS o DIARIA; NULL hasta asignarla (y siempre NULL en Datos).';
COMMENT ON COLUMN simulacion.diccionario_rutas.grupo IS 'Grupo dentro de su cluster («1», «2», «A»); NULL hasta asignarlo (y siempre NULL en Datos). Se conserva al pasar a otro cluster.';

-- Locales de cada ruta: uno o varios códigos con sus volúmenes; el código repetido se suma.
CREATE TABLE IF NOT EXISTS simulacion.diccionario_locales (
    ruta_id  BIGINT        NOT NULL REFERENCES simulacion.diccionario_rutas (id) ON DELETE CASCADE,
    orden    INTEGER       NOT NULL,
    cod      VARCHAR(100)  NOT NULL,
    filas    INTEGER       NOT NULL DEFAULT 1,
    bultos   NUMERIC(12,2) NOT NULL DEFAULT 0,
    unidades NUMERIC(12,2) NOT NULL DEFAULT 0,
    lineas   NUMERIC(12,2) NOT NULL DEFAULT 0,
    -- Q: locales de una ruta (WHERE ruta_id = ? ORDER BY orden), totales por ruta y la FK.
    CONSTRAINT pk_dicc_locales PRIMARY KEY (ruta_id, orden),
    CONSTRAINT uq_dicc_locales_cod UNIQUE (ruta_id, cod),
    CONSTRAINT ck_dicc_locales CHECK (
        orden > 0 AND filas > 0 AND bultos >= 0 AND unidades >= 0 AND lineas >= 0)
);
COMMENT ON TABLE simulacion.diccionario_locales IS 'Registro: locales de cada ruta del diccionario (los totales de la ruta son su suma).';
COMMENT ON COLUMN simulacion.diccionario_locales.filas IS 'Veces que se ingresó ese código en la ruta (más de una: se sumaron).';

-- =============================================================================================
-- 3. El escenario de Configuración y el Dashboard
-- =============================================================================================

-- El escenario único del Registro (lo crea el backend la primera vez que se usa).
ALTER TABLE simulacion.archivos_excel ADD COLUMN IF NOT EXISTS es_diccionario BOOLEAN NOT NULL DEFAULT FALSE;
-- Q: escenario único (WHERE es_diccionario); también impide que haya dos.
CREATE UNIQUE INDEX IF NOT EXISTS uq_archivos_es_diccionario
    ON simulacion.archivos_excel (es_diccionario) WHERE es_diccionario;
COMMENT ON COLUMN simulacion.archivos_excel.es_diccionario IS 'TRUE en el único escenario que Configuración arma con las rutas completas del Registro.';

-- Cada ruta del escenario apunta a su ruta del Registro; si se elimina allá, sale de aquí.
ALTER TABLE simulacion.rutas_proyeccion ADD COLUMN IF NOT EXISTS diccionario_ruta_id BIGINT
    REFERENCES simulacion.diccionario_rutas (id) ON DELETE CASCADE;
-- Q: sincronizar una ruta del Registro con su fila del escenario (archivo_id, diccionario_ruta_id).
CREATE UNIQUE INDEX IF NOT EXISTS uq_rutas_diccionario
    ON simulacion.rutas_proyeccion (archivo_id, diccionario_ruta_id) WHERE diccionario_ruta_id IS NOT NULL;
-- Q: la FK (borrar una ruta del Registro busca su fila por diccionario_ruta_id).
CREATE INDEX IF NOT EXISTS ix_rutas_diccionario
    ON simulacion.rutas_proyeccion (diccionario_ruta_id) WHERE diccionario_ruta_id IS NOT NULL;
COMMENT ON COLUMN simulacion.rutas_proyeccion.diccionario_ruta_id IS 'Ruta del Registro (diccionario) de la que sale esta fila del escenario.';

-- Los registros por Excel se borran (en cascada: rutas, locales, parámetros, operarios,
-- lanzamientos e historial). El escenario del Registro se conserva.
DELETE FROM simulacion.archivos_excel WHERE NOT es_diccionario;

-- =============================================================================================
-- Privilegios de la aplicación
-- =============================================================================================

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'atlas_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON simulacion.rutas_locales, simulacion.diccionario_clusters,
            simulacion.diccionario_rutas, simulacion.diccionario_locales TO atlas_app;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA simulacion TO atlas_app;
    END IF;
END $$;

COMMIT;
