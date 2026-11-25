-- Active: 1793813862107@@localhost@5432@ingenieria_logistica
-- Registro v2 de Simulación Titan: diccionario de rutas. Módulo independiente (no lo leen ni el
-- registro por Excel, ni Configuración, ni el Dashboard): las rutas se crean a mano con sus
-- locales y se ordenan arrastrándolas a bloques de cluster.
--
-- Una ruta en «Datos» (fuera de todo cluster) no tiene cluster, frecuencia ni grupo: la base lo
-- exige (ck_dicc_rutas_ubicacion). Frecuencia y grupo se asignan solo a rutas que ya están en un
-- cluster. El tipo (B2C/B2B) también empieza vacío y se asigna después, esté donde esté la ruta.
--
-- Se puede aplicar con el backend encendido y es re-ejecutable: crea las tablas si no existen y,
-- si ya existían de una versión anterior de este archivo, las pone al día sin perder datos.
-- Se aplica ANTES de usar el módulo Registro v2 (y antes de la carga del Excel).
--
--   psql -d ingenieria_logistica -v ON_ERROR_STOP=1 -f backend/migracion_diccionario_rutas.sql

BEGIN;

-- Clusters del diccionario: bloques CL1…CLn. Se agregan o quitan desde el final (orden).
CREATE TABLE IF NOT EXISTS simulacion.diccionario_clusters (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    orden     INTEGER      NOT NULL,
    nombre    VARCHAR(50)  NOT NULL,
    creado_en TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Q: lista de clusters (ORDER BY orden) y quitar los últimos (WHERE orden > ?).
    CONSTRAINT uq_dicc_clusters_orden UNIQUE (orden),
    CONSTRAINT ck_dicc_clusters_orden CHECK (orden > 0)
);
COMMENT ON TABLE simulacion.diccionario_clusters IS 'Registro v2: bloques de cluster del diccionario de rutas (CL1…CLn).';

-- Rutas del diccionario: número y tipo las identifican; tipo, cluster, frecuencia y grupo empiezan
-- vacíos. Sin tipo, el número tampoco se repite (NULLS NOT DISTINCT).
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
    CONSTRAINT ck_dicc_rutas_ubicacion CHECK (cluster_id IS NOT NULL OR (frecuencia IS NULL AND grupo IS NULL))
);

-- Puesta al día si la tabla ya existía de una versión anterior de este archivo (tipo obligatorio,
-- sin grupo): el tipo pasa a opcional y una ruta sin tipo tampoco se repite. Sin efecto si ya está.
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
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'simulacion.diccionario_rutas'::regclass AND conname = 'ck_dicc_rutas_grupo') THEN
        ALTER TABLE simulacion.diccionario_rutas
            ADD CONSTRAINT ck_dicc_rutas_grupo CHECK (grupo IS NULL OR btrim(grupo) <> '');
    END IF;
    -- La regla de Datos incluye el grupo.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'simulacion.diccionario_rutas'::regclass AND conname = 'ck_dicc_rutas_ubicacion' AND pg_get_constraintdef(oid) LIKE '%grupo%') THEN
        ALTER TABLE simulacion.diccionario_rutas DROP CONSTRAINT IF EXISTS ck_dicc_rutas_ubicacion;
        ALTER TABLE simulacion.diccionario_rutas ADD CONSTRAINT ck_dicc_rutas_ubicacion
            CHECK (cluster_id IS NOT NULL OR (frecuencia IS NULL AND grupo IS NULL));
    END IF;
END $$;
-- Q: al quitar clusters, sus rutas vuelven a Datos (WHERE cluster_id IN (…)); y la FK.
CREATE INDEX IF NOT EXISTS ix_dicc_rutas_cluster
    ON simulacion.diccionario_rutas (cluster_id) WHERE cluster_id IS NOT NULL;
COMMENT ON TABLE simulacion.diccionario_rutas IS 'Registro v2: diccionario de rutas. Sin cluster (bloque Datos) no hay frecuencia ni grupo.';
COMMENT ON COLUMN simulacion.diccionario_rutas.tipo IS 'B2C o B2B; NULL hasta asignarlo (no depende del bloque: Datos lo conserva).';
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
COMMENT ON TABLE simulacion.diccionario_locales IS 'Registro v2: locales de cada ruta del diccionario (los totales de la ruta son su suma).';
COMMENT ON COLUMN simulacion.diccionario_locales.filas IS 'Veces que se ingresó ese código en la ruta (más de una: se sumaron).';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'atlas_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON simulacion.diccionario_clusters,
            simulacion.diccionario_rutas, simulacion.diccionario_locales TO atlas_app;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA simulacion TO atlas_app;
    END IF;
END $$;

COMMIT;
