-- Active: 1793765968991@@dpg-dblama6417fc7380aqog-a.oregon-postgres.render.com@5432@ingenieria_logistica
ALTER TABLE simulacion.archivos_excel ADD COLUMN IF NOT EXISTS balanceo_guardado JSONB;
