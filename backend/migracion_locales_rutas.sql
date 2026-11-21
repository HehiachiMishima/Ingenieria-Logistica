-- Locales de cada ruta del Excel de Simulación Titan: el detalle que se ve al hacer clic en una
-- ruta (COD, bultos, unidades y líneas de cada local).
--
-- Se aplica ANTES de desplegar el backend que la usa (al subir un Excel se escriben aquí los
-- locales). Solo crea una tabla vacía: se puede aplicar con el backend encendido y es
-- re-ejecutable (IF NOT EXISTS).
--
-- Los registros subidos antes de esta migración no tienen el detalle: al hacer clic en sus rutas
-- se avisa que hay que volver a subir el Excel para ver sus locales.
--
--   psql -d ingenieria_logistica -v ON_ERROR_STOP=1 -f backend/migracion_locales_rutas.sql

BEGIN;

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
COMMENT ON TABLE simulacion.rutas_locales IS 'Locales de cada ruta tal como vinieron en el Excel (una fila = un local; el COD repetido se suma).';
COMMENT ON COLUMN simulacion.rutas_locales.orden IS 'Orden de aparición del local en el Excel, dentro de su ruta.';
COMMENT ON COLUMN simulacion.rutas_locales.cod IS 'Código del local; NULL si la fila no traía COD.';
COMMENT ON COLUMN simulacion.rutas_locales.filas IS 'Filas del Excel con ese COD en la ruta (más de una: se sumaron).';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'atlas_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON simulacion.rutas_locales TO atlas_app;
    END IF;
END $$;

COMMIT;
