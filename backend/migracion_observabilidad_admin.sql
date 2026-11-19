BEGIN;

CREATE TABLE IF NOT EXISTS bitacora.peticiones (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    solicitud uuid NOT NULL UNIQUE,
    ocurrido_en timestamptz NOT NULL DEFAULT now(),
    actor_id bigint,
    usuario varchar(50),
    metodo text NOT NULL,
    ruta text NOT NULL,
    estado integer NOT NULL,
    duracion_ms bigint NOT NULL,
    correlacion text NOT NULL
);
CREATE INDEX IF NOT EXISTS peticiones_fecha ON bitacora.peticiones (ocurrido_en, id);
CREATE INDEX IF NOT EXISTS peticiones_usuario ON bitacora.peticiones (usuario, id DESC);
CREATE INDEX IF NOT EXISTS peticiones_ruta ON bitacora.peticiones (ruta, metodo, id DESC);

CREATE TABLE IF NOT EXISTS bitacora.auditoria_eventos (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    solicitud uuid NOT NULL UNIQUE,
    ocurrido_en timestamptz NOT NULL DEFAULT now(),
    actor_id bigint,
    usuario varchar(50),
    accion text NOT NULL,
    entidad text NOT NULL,
    registro text NOT NULL,
    resultado text NOT NULL CHECK (resultado IN ('Éxito','Fallo','Denegado')),
    correlacion text NOT NULL
);
CREATE INDEX IF NOT EXISTS auditoria_fecha ON bitacora.auditoria_eventos (ocurrido_en, id);
CREATE INDEX IF NOT EXISTS auditoria_objeto ON bitacora.auditoria_eventos (registro, id DESC);
CREATE INDEX IF NOT EXISTS auditoria_usuario ON bitacora.auditoria_eventos (usuario, id DESC);
CREATE OR REPLACE FUNCTION bitacora.conservar_auditoria() RETURNS trigger
LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    RAISE EXCEPTION 'Los eventos de auditoría son de solo lectura';
END;
$$;
CREATE OR REPLACE TRIGGER conservar_auditoria BEFORE UPDATE OR DELETE OR TRUNCATE
ON bitacora.auditoria_eventos FOR EACH STATEMENT EXECUTE FUNCTION bitacora.conservar_auditoria();

-- Resumen por hora y API con histograma acumulado de duraciones (hasta_N = peticiones de N ms o menos).
-- Lo escribe la misma sentencia que guarda cada lote de trazas; Monitoreo lo lee sin recorrer las trazas.
-- Q: KPIs, serie y tabla de Monitoreo: WHERE hora >= now() - n días (prefijo de la PK).
CREATE TABLE IF NOT EXISTS bitacora.peticiones_hora (
    hora timestamptz NOT NULL,
    metodo text NOT NULL,
    ruta text NOT NULL,
    peticiones bigint NOT NULL,
    errores_cliente bigint NOT NULL,
    errores_servidor bigint NOT NULL,
    duracion_total_ms bigint NOT NULL,
    hasta_50 bigint NOT NULL,
    hasta_100 bigint NOT NULL,
    hasta_200 bigint NOT NULL,
    hasta_300 bigint NOT NULL,
    hasta_500 bigint NOT NULL,
    hasta_750 bigint NOT NULL,
    hasta_1000 bigint NOT NULL,
    hasta_2000 bigint NOT NULL,
    hasta_5000 bigint NOT NULL,
    PRIMARY KEY (hora, metodo, ruta)
);

-- Peticiones de cada usuario por hora y panel (sin las técnicas de sesión).
-- Q: KPIs, mapa de calor y paneles de Actividad: WHERE hora >= now() - n días [AND usuario = ?].
CREATE TABLE IF NOT EXISTS bitacora.actividad_hora (
    hora timestamptz NOT NULL,
    usuario varchar(50) NOT NULL,
    panel text NOT NULL,
    peticiones bigint NOT NULL,
    PRIMARY KEY (hora, usuario, panel)
);

-- Relleno con las trazas que ya existen. ON CONFLICT DO NOTHING: reaplicar la migración no duplica.
-- Aplicar con el backend detenido para que ninguna hora quede contada a medias.
INSERT INTO bitacora.peticiones_hora (hora, metodo, ruta, peticiones, errores_cliente, errores_servidor,
    duracion_total_ms, hasta_50, hasta_100, hasta_200, hasta_300, hasta_500, hasta_750, hasta_1000, hasta_2000, hasta_5000)
SELECT date_trunc('hour', ocurrido_en, 'UTC'), metodo, ruta, count(*),
       count(*) FILTER (WHERE estado BETWEEN 400 AND 499), count(*) FILTER (WHERE estado >= 500), sum(duracion_ms),
       count(*) FILTER (WHERE duracion_ms <= 50), count(*) FILTER (WHERE duracion_ms <= 100),
       count(*) FILTER (WHERE duracion_ms <= 200), count(*) FILTER (WHERE duracion_ms <= 300),
       count(*) FILTER (WHERE duracion_ms <= 500), count(*) FILTER (WHERE duracion_ms <= 750),
       count(*) FILTER (WHERE duracion_ms <= 1000), count(*) FILTER (WHERE duracion_ms <= 2000),
       count(*) FILTER (WHERE duracion_ms <= 5000)
FROM bitacora.peticiones
GROUP BY 1, 2, 3
ON CONFLICT (hora, metodo, ruta) DO NOTHING;

INSERT INTO bitacora.actividad_hora (hora, usuario, panel, peticiones)
SELECT date_trunc('hour', ocurrido_en, 'UTC'), usuario,
       CASE split_part(ruta, '/', 3)
           WHEN 'admin' THEN 'PANEL_ADMIN' WHEN 'usuarios' THEN 'PANEL_ADMIN' WHEN 'roles' THEN 'PANEL_ADMIN'
           WHEN 'permisos' THEN 'PANEL_ADMIN' WHEN 'resolucion' THEN 'PANEL_ADMIN'
           WHEN 'incidencias' THEN 'PANEL_BITACORA' WHEN 'areas' THEN 'PANEL_BITACORA'
           WHEN 'centros' THEN 'PANEL_BITACORA' WHEN 'sistemas' THEN 'PANEL_BITACORA'
           WHEN 'simulacion' THEN 'PANEL_SIMULACION' WHEN 'simulacion-santa' THEN 'PANEL_SIMULACION_SANTA'
           WHEN 'malla' THEN 'PANEL_MALLA' WHEN 'picking' THEN 'PANEL_PICKING'
           WHEN 'auth' THEN 'SESION' ELSE 'OTROS' END,
       count(*)
FROM bitacora.peticiones
WHERE actor_id IS NOT NULL AND usuario IS NOT NULL
  AND ruta NOT IN ('/api/auth/refresh', '/api/auth/sesion', '/api/health')
GROUP BY 1, 2, 3
ON CONFLICT (hora, usuario, panel) DO NOTHING;

CREATE OR REPLACE FUNCTION seguridad.es_permiso_base(nombre text)
RETURNS boolean
LANGUAGE sql IMMUTABLE
SET search_path = ''
RETURN nombre COLLATE comun.ci IN (
    'ASIGNAR_ROLES', 'VER_ROLES', 'CREAR_ROLES', 'MODIFICAR_ROLES', 'ELIMINAR_ROLES',
    'ASIGNAR_PERMISOS', 'VER_PERMISOS', 'CREAR_PERMISOS', 'MODIFICAR_PERMISOS', 'ELIMINAR_PERMISOS',
    'VER_USUARIOS', 'CREAR_USUARIOS', 'MODIFICAR_USUARIOS', 'ELIMINAR_USUARIOS',
    'PANEL_ADMIN', 'DASHBOARD_ADMIN', 'VER_AUDITORIA', 'VER_MONITOREO', 'VER_ACTIVIDAD');

INSERT INTO seguridad.permisos(nombre, descripcion, creado_por, actualizado_por)
VALUES ('VER_AUDITORIA','Consultar cambios administrativos y de distribución Titan.',0,0),
       ('VER_MONITOREO','Consultar el rendimiento y los errores de las APIs.',0,0),
       ('VER_ACTIVIDAD','Consultar la actividad por usuario.',0,0)
ON CONFLICT (nombre) DO NOTHING;

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'atlas_app') THEN
        GRANT SELECT, INSERT ON bitacora.auditoria_eventos TO atlas_app;
        GRANT SELECT, INSERT, DELETE ON bitacora.peticiones TO atlas_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON bitacora.peticiones_hora, bitacora.actividad_hora TO atlas_app;
        GRANT USAGE ON SEQUENCE bitacora.auditoria_eventos_id_seq, bitacora.peticiones_id_seq TO atlas_app;
    END IF;
END;
$$;

COMMIT;
