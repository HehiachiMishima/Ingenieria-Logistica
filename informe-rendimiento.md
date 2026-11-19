# Informe de auditoría de rendimiento — Administración: Dashboard de operación, Monitoreo, Actividad y Auditoría

## 1. Contexto

| Campo | Valor |
|---|---|
| Fecha | 2026-11-19 |
| Commit | 19a57b7 + cambios sin commit (rama update_16) |
| Entorno | local, macOS arm64, una instancia; volumen sintético de 1 000 000 trazas y 200 000 eventos de auditoría dentro de transacciones revertidas |
| Backend | Spring Boot 4.1.2, Hibernate 7.4.13, HikariCP 7.0.2, driver PostgreSQL 42.7.14, JDK 25, virtual threads sí (`spring.threads.virtual.enabled=true`), open-in-view false |
| Base de datos | PostgreSQL 18.6 local, max_connections 100, sin PgBouncer, pg_stat_statements no cargado |
| Frontend | Angular 22.1, builder application (esbuild), zoneless, RxJS 7.8 |
| Umbrales | LCP ≤ 2,5 s · INP ≤ 200 ms · CLS ≤ 0,1 · p95 API ≤ 200 ms · bundle inicial ≤ 500 KB |
| Fuera de alcance | Seguridad, SEO, accesibilidad, costes cloud |

## 2. Línea base

| Métrica | Escenario | Valor | Umbral | Fuente | Fecha |
|---|---|---|---|---|---|
| Consulta de Monitoreo por API (percentile_disc sobre trazas) | 1 M trazas, 30 días, caché caliente | 1 148,5 ms | 200 ms | `EXPLAIN (ANALYZE, BUFFERS)` en `BenchObservabilidad.java` (scratchpad) | 2026-11-19 |
| Consulta de Monitoreo por API (percentile_disc sobre trazas) | 1 M trazas, 24 h | 316,9 ms | 200 ms | ídem | 2026-11-19 |
| Buffers de Monitoreo por API | 1 M trazas, 30 días | 1 012 146 buffers | — | ídem (`shared hit=1000040 read=12106`) | 2026-11-19 |
| Usuarios activos (count distinct sobre trazas) | 1 M trazas, 30 días | 4 349,0 ms | 200 ms | ídem | 2026-11-19 |
| Usuarios por semana del Dashboard (count distinct sobre `actividad_hora`) | 300 usuarios activos cada hora, 12 semanas | 190,3 ms con derrame a disco | 200 ms | `EXPLAIN (ANALYZE, BUFFERS)` en `BenchTablero2.java` (`temp read=1032`) | 2026-11-19 |
| Usuarios por día del Dashboard (count distinct sobre `actividad_hora`) | ídem, 30 días | 192,6 ms con derrame a disco | 200 ms | ídem | 2026-11-19 |
| p95 por día × franja del Dashboard (`peticiones_hora`) | 1 M trazas, 30 días | 73,0 ms | 200 ms | `BenchTablero.java` | 2026-11-19 |
| Matriz usuario × registro del Dashboard | 200 000 eventos, 30 días | 109,7 ms | 200 ms | ídem | 2026-11-19 |
| Intentos de cambio día × hora del Dashboard | 200 000 eventos, 30 días | 69,6 ms | 200 ms | ídem | 2026-11-19 |
| Escritura de un lote de 500 trazas + resúmenes | 20 lotes, media | 44,5 ms | — | `BenchObservabilidad.java` con la sentencia real de `ColaPeticiones.INSERTAR` | 2026-11-19 |
| Capacidad de la cola de trazas por instancia | 1 lote de 500 cada 250 ms | 1 700 trazas/s | — | Cálculo: 500 / (0,25 s + 0,0445 s) | 2026-11-19 |
| Bundle inicial | `ng build` producción | 449,55 kB | 500 kB | `npx ng build --verbose` | 2026-11-19 |
| Chunks perezosos de los tres módulos | `ng build` producción | 11,41 kB · 10,56 kB · 8,55 kB | — | ídem (monitoreo, auditoria, actividad) | 2026-11-19 |
| p95 HTTP de los tres `/resumen` | servidor en marcha con carga | Pendiente | 200 ms | Levantar el backend con datos y ejecutar `k6 run` con 50 VUs sobre los tres `/resumen` y `/api/admin/monitoreo` | 2026-11-19 |
| LCP /admin/dashboard (móvil) | Lighthouse ×3, mediana | Pendiente | 2,5 s | `npx lighthouse http://127.0.0.1:4287/admin/dashboard --preset=perf --output=json` con `node qa/observabilidad/servidor.mjs` | 2026-11-19 |
| Pool Hikari (`pending`, `acquire`) | carga nominal | Pendiente | 0 · 10 ms | `scripts/analyze_pool_health.sh -n 60 -i 5` contra Actuator con carga | 2026-11-19 |

## 3. Hallazgos

| Severidad | Dominio | Evidencia | Anti-patrón | Corrección propuesta | Métrica esperada |
|---|---|---|---|---|---|
| Alta | Base de datos | Versión previa de `ConsultasObservabilidad.java` (`/api/admin/monitoreo`): `percentile_disc(0.95) … FROM bitacora.peticiones` — 1 148,5 ms y 1 012 146 buffers con 1 M trazas en 30 días | Agregación y percentil exacto sobre la tabla de trazas en cada carga de la pantalla; el costo crece con el volumen | Resumen por hora `bitacora.peticiones_hora` con histograma acumulado, llenado en la misma sentencia que guarda el lote (`ColaPeticiones.java:44`), y p95 interpolado en `ResumenObservabilidad.java` | ≤ 200 ms a 30 días con 1 M trazas (medido: 31,4 ms y 2 279 buffers) |
| Media | Backend | `ColaPeticiones.java:103` escribía un solo lote de 500 cada 250 ms; lote medido en 44,5 ms → techo de 1 700 trazas/s por instancia; por encima se llena la cola de 8 192 (`ColaPeticiones.java:82`) y sube `atlas.trazas.descartadas` | Consumidor de cola con caudal fijo menor que la producción en picos | Hasta 10 lotes por pasada mientras la cola siga llena (`LOTES_POR_PASADA`, `ColaPeticiones.java:101`) | ≈ 7 000 trazas/s por instancia (5 000 / 0,695 s) y `atlas.trazas.descartadas` = 0 a 5 000 peticiones/s |
| Media | Base de datos | Usuarios por semana y por día del Dashboard con `count(DISTINCT usuario)`: 190,3 ms y 192,6 ms, `temp read=1032 written=1035` (derrame a disco con `work_mem` por defecto) | Agregado distinto que ordena todas las filas hora × usuario del período | Agregación en dos niveles (usuario por período y luego conteo) en `ResumenObservabilidad.usuariosPorPeriodo`, en el total y en los paneles | Sin buffers temporales; ≤ 120 ms a 12 semanas en el peor caso sintético (medido: 116,8 ms y 73,1 ms) |
| Baja | Base de datos | Resumen de Actividad a 30 días en el peor caso sintético (300 usuarios activos cada hora del mes, 214 061 filas en `actividad_hora`): 36,2 + 23,0 + 73,1 + 116,8 ms solo en las cuatro consultas de usuarios distintos | Contar usuarios distintos sobre filas por hora cuando la pregunta es por día o semana | Si el volumen real se acerca a ese peor caso: resumen diario `bitacora.actividad_dia (dia, usuario, panel, peticiones)` escrito en la misma sentencia del lote, y las cuatro consultas sobre él | ≤ 60 ms para todo el resumen de Actividad a 30 días con 300 usuarios |

## 4. Clasificación

| Verificación | Dominio | Estado | Motivo / siguiente paso |
|---|---|---|---|
| Percentil y agregados sobre trazas en Monitoreo | Base de datos | Aplicable | 1 148,5 ms a 30 días con 1 M trazas; corregido con `peticiones_hora` (31,4 ms) |
| Caudal de la cola de trazas | Backend | Aplicable | Techo de 1 700 trazas/s por instancia; corregido con varios lotes por pasada |
| Derrame a disco de los usuarios distintos del Dashboard | Base de datos | Aplicable | `temp read=1032`; corregido con agregación en dos niveles (sin derrame) |
| Costo total del resumen de Actividad en el peor caso | Base de datos | Aplicable | Suma de consultas > 200 ms solo con 300 usuarios activos 24/7; corrección propuesta, no aplicada |
| Open-in-view | Backend | No aplicable | `application.properties:37` `spring.jpa.open-in-view=false` |
| N+1 / JPA en las consultas nuevas | Backend | No aplicable | Proyecciones JDBC agregadas (`ResumenObservabilidad.java`), sin entidades ni colecciones perezosas |
| `@Transactional` con I/O externo | Backend | No aplicable | Los resúmenes usan `transacciones.consultar` solo con SQL; la escritura de trazas va en el hilo del planificador |
| Doble conteo al reintentar un lote | Backend | No aplicable | Solo se suma lo que devuelve `RETURNING` del `INSERT … ON CONFLICT DO NOTHING`; prueba `elLoteSumaAlResumenPorHoraUnaSolaVezAunqueSeReintente` en verde |
| Índices de las consultas nuevas | Base de datos | No aplicable | Rango de hora por prefijo de PK en `peticiones_hora` y `actividad_hora`; `auditoria_fecha` para Auditoría (planes de 962 a 6 308 buffers) |
| Interbloqueos entre instancias al sumar resúmenes | Base de datos | No aplicable | Las filas se escriben con `ORDER BY` de la clave en ambos `INSERT … ON CONFLICT` |
| Peticiones del Dashboard | API | No aplicable | Tres resúmenes en paralelo, uno por módulo y solo si el usuario tiene su permiso; los módulos ya no piden resúmenes |
| Bundle inicial | Frontend | No aplicable | 449,55 kB < 500 kB; los módulos quedaron en 8,5–11,4 kB y el Dashboard creció 0,84 kB comprimido |
| Suscripciones sin limpieza | Frontend | No aplicable | `RecursoRemoto` usa `takeUntilDestroyed`; las páginas no hacen `subscribe` |
| p95 HTTP con carga | API | Pendiente | Backend en marcha con datos + `k6` sobre los tres `/resumen` |
| LCP / INP del Dashboard | Frontend | Pendiente | Lighthouse contra el servidor de QA; INP de campo con `web-vitals` |
| Salud del pool Hikari | Backend | Pendiente | `analyze_pool_health.sh` con Actuator y carga |
| Top de consultas en producción | Observabilidad | Pendiente | Cargar `pg_stat_statements` (`shared_preload_libraries`) en la base desplegada y leer el top tras 7 días |

## 5. Plan priorizado

| Orden | Nivel | Hallazgo | Impacto/Esfuerzo |
|---|---|---|---|
| 1 | N2 | Caudal de la cola de trazas: capacidad del ejecutor que escribe (aplicado) | 3/2 · Aprovechar (hecho) |
| 2 | N3 | Percentil y agregados sobre trazas en Monitoreo (aplicado) | 5/3 · Planificar (hecho) |
| 3 | N3 | Derrame a disco de los usuarios distintos del Dashboard (aplicado) | 3/2 · Aprovechar (hecho) |
| 4 | N3 | Resumen diario de actividad para el peor caso | 1/3 · Backlog hasta medir el volumen real |

## 6. Verificación antes/después

| Métrica | Antes | Después | Δ | Estado |
|---|---|---|---|---|
| Monitoreo por API, 30 días, 1 M trazas | 1 148,5 ms | 31,4 ms | −97 % | Mejora |
| Monitoreo por API, 24 h | 316,9 ms | 2,4 ms | −99 % | Mejora |
| Buffers de Monitoreo por API, 30 días | 1 012 146 | 2 279 | −99,8 % | Mejora |
| Usuarios activos, 30 días | 4 349,0 ms | 36,2 ms | −99 % | Mejora |
| Usuarios por semana del Dashboard, 12 semanas | 190,3 ms | 116,8 ms | −39 % | Mejora |
| Usuarios por día del Dashboard, 30 días | 192,6 ms | 73,1 ms | −62 % | Mejora |
| Buffers temporales de usuarios distintos | 2 067 | 0 | −100 % | Mejora |
| Capacidad de la cola de trazas por instancia | 1 700 trazas/s | 7 000 trazas/s (cálculo) | +312 % | Pendiente |
| p95 HTTP de los tres `/resumen` | Pendiente | Pendiente | — | Pendiente |

## 7. Veredicto

El Dashboard de operación no depende del volumen de trazas: lee resúmenes por hora que se llenan en la misma sentencia que guarda cada lote, y todas sus consultas quedan por debajo de 120 ms en el peor caso sintético (1 M trazas, 200 000 eventos, 300 usuarios activos cada hora). Lo único que derramaba a disco eran los usuarios distintos por día y semana; la agregación en dos niveles lo eliminó. PostgreSQL no es el cuello de botella en estas consultas. Falta medir el p95 HTTP con carga real, el LCP del Dashboard y la salud del pool con Actuator; el resumen diario de actividad solo hace falta si el uso real se acerca al peor caso.
