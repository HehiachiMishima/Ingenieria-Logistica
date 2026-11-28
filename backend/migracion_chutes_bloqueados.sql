-- Active: 1793813862107@@localhost@5432@ingenieria_logistica
-- =====================================================================================
-- Simulación Titan · Chutes bloqueados elegibles (la franja de peligro: solo a mano)
--
-- Cada escenario elige en Configuración («Chutes bloqueados») qué chutes no usa el
-- balanceo automático: ahí las rutas entran solo a mano y quedan fijas al balancear.
-- Hasta ahora eran fijos (19, 20 y 21, en application.properties).
--
--   * parametros_chutes.manual  → el chute está bloqueado en ese escenario.
--   * predeterminados.chutes_bloqueados → los que se fijan con el candado «Bloquear»
--     (NULL = aún no se fijaron: valen los del plano, simulacion.chutes.manuales).
--
-- Lo que ya había no cambia: el 19, 20 y 21 con fila guardada quedan bloqueados; los
-- que no tienen fila los sigue bloqueando el plano por defecto.
--
-- Correr ANTES de actualizar el backend (la entidad ya lee la columna nueva):
--   psql -v ON_ERROR_STOP=1 -f backend/migracion_chutes_bloqueados.sql
-- Una sola transacción; se puede correr más de una vez.
-- =====================================================================================

BEGIN;

ALTER TABLE simulacion.parametros_chutes
    ADD COLUMN IF NOT EXISTS manual BOOLEAN NOT NULL DEFAULT FALSE;

-- El 19, 20 y 21 que ya tienen fila siguen bloqueados, como hasta ahora (sin buff: el
-- balanceo automático no los usa). El número se lee del nombre igual que en el backend:
-- «19», «CHUTE 19», «CH-19», «CHUTE N° 19», «019».
UPDATE simulacion.parametros_chutes pc
   SET manual = TRUE,
       buff   = FALSE
  FROM simulacion.chutes c
 WHERE c.id = pc.chute_id
   AND NOT pc.manual
   AND upper(btrim(c.nombre)) COLLATE "C"
       ~ '^(CH(UTE)?)?[[:space:]_#.:-]*(N[°º.O]?[[:space:]]*)?0*(19|20|21)$';

-- Bloqueado y con buff a la vez, nunca: el buff es para el balanceo automático.
ALTER TABLE simulacion.parametros_chutes
    DROP CONSTRAINT IF EXISTS ck_param_chute_buff_manual;
ALTER TABLE simulacion.parametros_chutes
    ADD CONSTRAINT ck_param_chute_buff_manual CHECK (NOT (buff AND manual));

COMMENT ON COLUMN simulacion.parametros_chutes.manual IS
    'Chute bloqueado (franja de peligro): el balanceo automático, el rango y el reparto al azar no lo usan; recibe rutas solo a mano y quedan fijas al balancear.';

ALTER TABLE simulacion.predeterminados
    ADD COLUMN IF NOT EXISTS chutes_bloqueados BIGINT[];

COMMENT ON COLUMN simulacion.predeterminados.chutes_bloqueados IS
    'Ids del catálogo de chutes bloqueados (solo a mano) que fija el candado; NULL = los del plano (simulacion.chutes.manuales).';

COMMIT;
