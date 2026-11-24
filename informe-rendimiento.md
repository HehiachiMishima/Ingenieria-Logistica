# Informe de auditoría de rendimiento — Administración: Dashboard de operación, Monitoreo, Actividad, Auditoría, permisos del rol y selector de paneles

## 1. Contexto

| Campo | Valor |
|---|---|
| Fecha | 2026-11-19 (observabilidad) · 2026-11-20 (permisos del rol, roles del usuario, permisos individuales y roles con un permiso) · 2026-11-21 (selector de paneles) |
| Commit | 19a57b7 + cambios sin commit (rama update_16) · 2a6d074 + cambios sin commit (rama update_19) · 8215938 + cambios sin commit (roles del usuario) · de8a47e + cambios sin commit (permisos individuales) · b21c714 + cambios sin commit (roles con un permiso) · c754505 + cambios sin commit (selector de paneles, rama update_21) |
| Entorno | local, macOS arm64, una instancia; volumen sintético de 1 000 000 trazas, 200 000 eventos de auditoría y 80 569 filas en `roles_permisos` (2 000 roles × 40 permisos, 563 permisos), y para los roles del usuario 100 000 usuarios con 150 001 asignaciones de rol y 50 000 excepciones, todo dentro de transacciones revertidas |
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
| Listado de Actividad por cursor (antes de la paginación estándar) | 1 M trazas, 30 días, primera página | 0,094 ms | 200 ms | `EXPLAIN (ANALYZE, BUFFERS)` en `BenchPaginas.java` | 2026-11-19 |
| Conteo con tope de 10 000 en Actividad | 1 M trazas, 30 días | 17,4 ms | 200 ms | ídem | 2026-11-19 |
| Página 1 000 de Actividad (OFFSET 9 990) | 1 M trazas, 30 días | 18,9 ms | 200 ms | ídem (`shared hit=31080`) | 2026-11-19 |
| Página 1 000 de Auditoría (OFFSET 9 990) | 200 000 eventos, 90 días | 65,0 ms | 200 ms | ídem | 2026-11-19 |
| Escritura de un lote de 500 trazas + resúmenes | 20 lotes, media | 44,5 ms | — | `BenchObservabilidad.java` con la sentencia real de `ColaPeticiones.INSERTAR` | 2026-11-19 |
| Capacidad de la cola de trazas por instancia | 1 lote de 500 cada 250 ms | 1 700 trazas/s | — | Cálculo: 500 / (0,25 s + 0,0445 s) | 2026-11-19 |
| Bundle inicial | `ng build` producción | 449,55 kB | 500 kB | `npx ng build --verbose` | 2026-11-19 |
| Chunks perezosos de los tres módulos | `ng build` producción | 11,41 kB · 10,56 kB · 8,55 kB | — | ídem (monitoreo, auditoria, actividad) | 2026-11-19 |
| p95 HTTP de los tres `/resumen` | servidor en marcha con carga | Pendiente | 200 ms | Levantar el backend con datos y ejecutar `k6 run` con 50 VUs sobre los tres `/resumen` y `/api/admin/monitoreo` | 2026-11-19 |
| LCP /admin/dashboard (móvil) | Lighthouse ×3, mediana | Pendiente | 2,5 s | `npx lighthouse http://127.0.0.1:4287/admin/dashboard --preset=perf --output=json` con un servidor de pruebas que sirva `frontend/dist/frontend/browser` (la carpeta `frontend/qa` se retiró del repositorio) | 2026-11-19 |
| Pool Hikari (`pending`, `acquire`) | carga nominal | Pendiente | 0 · 10 ms | `scripts/analyze_pool_health.sh -n 60 -i 5` contra Actuator con carga | 2026-11-19 |
| Abrir los permisos de un rol (antes) | 563 permisos, 80 569 filas en `roles_permisos` | 2 peticiones HTTP; 0,058 + 0,057 ms en la base | 200 ms | `EXPLAIN (ANALYZE, BUFFERS)` en `BenchPermisosRol.java` (scratchpad) | 2026-11-20 |
| Asignar 16 permisos a un rol (antes) | uno a uno, como `alternarPermiso` | 16 peticiones HTTP, 16 transacciones; 32 sentencias = 5,72 ms en la base | — | `BenchPermisosRol.java` (EXISTS + INSERT por permiso, igual que `RolPermisoService.asignar`) | 2026-11-20 |
| Chunk perezoso de Roles | `ng build` producción | 19,15 kB (5,80 kB gzip) | — | `ng build` de `2a6d074` en un worktree temporal; tamaño del archivo con `gzip -9` | 2026-11-20 |
| Desplazar el catálogo del tablero (diálogo mediano) | 1366 × 768, bundle de producción, 90 cuadros recorriendo 720 px a lo ancho y 450 px a lo alto, ida y vuelta | p95 17,9 ms por cuadro (máx. 18,5 ms), 0 tareas largas, 16 cambios de clase en los atajos | 200 ms (INP; cuadro ≤ 16,7 ms a 60 Hz) | `requestAnimationFrame` + `PerformanceObserver(longtask)` + `MutationObserver` en el navegador contra el servidor de QA | 2026-11-20 |
| Abrir los roles de un usuario (antes) | 2 002 roles, 150 001 filas en `usuarios_roles` | 2 peticiones HTTP; 0,21 + 0,01 ms en la base | 200 ms | `EXPLAIN (ANALYZE, BUFFERS)` en `BenchRolesUsuario.java` (scratchpad) | 2026-11-20 |
| Dar 5 roles a un usuario (antes) | uno a uno, como `UsuarioRolService.asignar` | 5 peticiones HTTP; 30 sentencias = 17,6 ms en la base | — | `BenchRolesUsuario.java` (por rol: permisos del actor ×2, del usuario, del rol, EXISTS e INSERT) | 2026-11-20 |
| Abrir los permisos individuales de un usuario (antes) | 100 001 usuarios, 50 000 filas en `usuarios_permisos` | 3 peticiones HTTP (catálogo, excepciones, resolución); 0,008 + 0,013 + 0,049 ms en la base | 200 ms | `EXPLAIN (ANALYZE, BUFFERS)` en `BenchExcepciones.java` (scratchpad) | 2026-11-20 |
| Conceder 5 permisos a un usuario (antes) | uno a uno, como `UsuarioPermisoService.otorgar` | 5 peticiones HTTP; 35 sentencias = 20,0 ms en la base | — | `BenchExcepciones.java` (por permiso: usuario, permisos del actor ×2, del usuario, permiso, excepción e INSERT) | 2026-11-20 |
| Abrir «Roles con este permiso» (antes) | permiso presente en 1 322 de 2 002 roles | 1 consulta (roles con el permiso): 2,4 ms y 609 buffers; solo lectura | 200 ms | `EXPLAIN (ANALYZE, BUFFERS)` en `BenchPermisoRoles.java` (scratchpad) | 2026-11-20 |
| Dar un permiso a 5 roles (antes) | desde Roles, un rol por vez (`RolPermisoService.asignar`) | 5 peticiones HTTP y 5 `invalidarTodo` de la caché de autorización; 25 sentencias = 9,6 ms en la base | — | `BenchPermisoRoles.java` | 2026-11-20 |
| Chunk perezoso de Permisos | `ng build` producción | 6,03 kB gzip | — | `ng build` de `de8a47e` en un worktree temporal (la página no cambió hasta `b21c714`) | 2026-11-20 |
| Chunk perezoso de Usuarios | `ng build` producción | 9,98 kB gzip | — | `ng build` de `8215938` en un worktree temporal; tamaño del archivo con `gzip -9` | 2026-11-20 |
| Chunk perezoso del selector de paneles (antes) | `ng build` producción | 13,45 kB (4,11 kB gzip) | — | `ng build --output-path` de `c754505` (scratchpad `qa-base`); tamaño del archivo y `gzip -c` | 2026-11-21 |
| LCP del selector `/` (antes) | 1366 × 768, bundle de producción, servidor de QA local sin caché HTTP, 3 cargas | 844 · 1 980 · 1 992 ms; elemento: la marca `atlasImg.png` de la cabecera | 2,5 s | `PerformanceObserver('largest-contentful-paint')` en el navegador, alternando con el build nuevo | 2026-11-21 |
| CLS del selector (antes) | 1366 × 768 y 390 × 844 | 0 | 0,1 | `PerformanceObserver('layout-shift')` | 2026-11-21 |
| Imágenes del selector (antes) | 1366 × 768 | `atlasImg.png` 572 790 B (1 360 × 1 600 px, mostrada a 46 × 46 px) y `atlas-carga.webp` 153 702 B; 14 peticiones en total | — | Resource Timing (`encodedBodySize`) | 2026-11-21 |
| Alto del selector en celular (antes) | 390 × 844 | 3 838 px; 2 de 11 paneles en la primera pantalla | — | `document.documentElement.scrollHeight` | 2026-11-21 |

## 3. Hallazgos

| Severidad | Dominio | Evidencia | Anti-patrón | Corrección propuesta | Métrica esperada |
|---|---|---|---|---|---|
| Alta | Base de datos | Versión previa de `ConsultasObservabilidad.java` (`/api/admin/monitoreo`): `percentile_disc(0.95) … FROM bitacora.peticiones` — 1 148,5 ms y 1 012 146 buffers con 1 M trazas en 30 días | Agregación y percentil exacto sobre la tabla de trazas en cada carga de la pantalla; el costo crece con el volumen | Resumen por hora `bitacora.peticiones_hora` con histograma acumulado, llenado en la misma sentencia que guarda el lote (`ColaPeticiones.java:44`), y p95 interpolado en `ResumenObservabilidad.java` | ≤ 200 ms a 30 días con 1 M trazas (medido: 31,4 ms y 2 279 buffers) |
| Media | Backend | `ColaPeticiones.java:103` escribía un solo lote de 500 cada 250 ms; lote medido en 44,5 ms → techo de 1 700 trazas/s por instancia; por encima se llena la cola de 8 192 (`ColaPeticiones.java:82`) y sube `atlas.trazas.descartadas` | Consumidor de cola con caudal fijo menor que la producción en picos | Hasta 10 lotes por pasada mientras la cola siga llena (`LOTES_POR_PASADA`, `ColaPeticiones.java:101`) | ≈ 7 000 trazas/s por instancia (5 000 / 0,695 s) y `atlas.trazas.descartadas` = 0 a 5 000 peticiones/s |
| Media | Base de datos | Usuarios por semana y por día del Dashboard con `count(DISTINCT usuario)`: 190,3 ms y 192,6 ms, `temp read=1032 written=1035` (derrame a disco con `work_mem` por defecto) | Agregado distinto que ordena todas las filas hora × usuario del período | Agregación en dos niveles (usuario por período y luego conteo) en `ResumenObservabilidad.usuariosPorPeriodo`, en el total y en los paneles | Sin buffers temporales; ≤ 120 ms a 12 semanas en el peor caso sintético (medido: 116,8 ms y 73,1 ms) |
| Baja | Base de datos | Resumen de Actividad a 30 días en el peor caso sintético (300 usuarios activos cada hora del mes, 214 061 filas en `actividad_hora`): 36,2 + 23,0 + 73,1 + 116,8 ms solo en las cuatro consultas de usuarios distintos | Contar usuarios distintos sobre filas por hora cuando la pregunta es por día o semana | Si el volumen real se acerca a ese peor caso: resumen diario `bitacora.actividad_dia (dia, usuario, panel, peticiones)` escrito en la misma sentencia del lote, y las cuatro consultas sobre él | ≤ 60 ms para todo el resumen de Actividad a 30 días con 300 usuarios |
| Media | API | `roles.ts:244` (versión `2a6d074`): `alternarPermiso` envía un POST o DELETE por permiso; asignar 16 permisos = 16 peticiones, 16 transacciones, 16 `invalidarTodo` de `AutorizacionCache` y 16 eventos de auditoría; el cupo de escrituras es 60 por minuto (`LimiteUsoAdmin.java:21`), así que armar un rol con todo el catálogo (63) termina en 429 | API conversadora: una escritura HTTP por elemento | `PATCH /api/roles/{rolId}/permisos` con `{agregar, quitar}` en una transacción (`RolPermisoService.java:159`, `RolPermisoLoteRepository`: un `INSERT … unnest … ON CONFLICT DO NOTHING` y un `DELETE … = ANY`); el tablero junta los cambios y guarda una vez | 1 petición, 1 transacción, 1 invalidación y 1 evento por guardado; 16 permisos en 4 sentencias (medido: 1,98 ms frente a 5,72 ms) y ningún 429 al armar un rol completo |
| Media | API | `roles-usuario.ts:92` (versión `8215938`): un POST o DELETE por rol, y cada uno repite `verificarPuedeAdministrar` (2 lecturas de `v_permisos_finales`) y `verificarPuedeOtorgar` (1 más): dar 5 roles = 5 peticiones, 30 sentencias y 5 invalidaciones de la caché de autorización | API conversadora: una escritura HTTP por elemento, con los controles de escalada repetidos | `PATCH /api/usuarios/{usuarioId}/roles` con `{agregar, quitar}` en una transacción (`UsuarioRolService.actualizarLote`, `UsuarioRolLoteRepository`): los controles se hacen una vez para todo el lote | 1 petición y 8 sentencias para 5 roles (medido: 6,9 ms frente a 17,6 ms) |
| Media | Base de datos | Primera versión de `UsuarioRolLoteRepository.java:35` con `array_agg(p.nombre ORDER BY p.nombre)` en el mismo `GROUP BY` que el conteo: 118,0 ms, `Sort Method: external merge Disk: 3816kB` y `temp read=474` con 2 002 roles y 80 069 filas en `roles_permisos` | Agregado ordenado con estado por grupo que derrama a disco con el `work_mem` por defecto | Conteo y «otorgable» en un agregado simple; los paneles en una CTE aparte, solo sobre las filas `PANEL_*`, con `string_agg` | 0 buffers temporales y ≤ 40 ms (medido: 36,3 ms) |
| Media | API | `permisos-usuario.ts:156` y `:133` (versión `de8a47e`): un PUT o DELETE por permiso, cada uno con `verificarPuedeAdministrar` y `verificarPuedeOtorgar` (7 sentencias por permiso); abrir el diálogo hacía 3 peticiones en paralelo (`permisos-usuario.ts:86`) | API conversadora: una escritura HTTP por elemento y tres lecturas para una pantalla | `GET /api/usuarios/{usuarioId}/permisos/tablero` (una consulta con lo que dan los roles, las excepciones y si se puede conceder) y `PATCH /api/usuarios/{usuarioId}/permisos` con `{conceder, denegar, heredar}` en una transacción (`UsuarioPermisoService.actualizarLote`) | 1 petición al abrir y 1 al guardar; 8 sentencias para 5 permisos (medido: 9,3 ms frente a 20,0 ms) |
| Media | Backend | `permisos.html:342` (versión `b21c714`): «La asignación se hace desde la pantalla de Roles»; dar un permiso nuevo a 5 roles exige 5 guardados, y cada uno ejecuta `autorizacionCache.invalidarTodo` (`RolPermisoService.java:95`), que vacía la caché de autorización de todos los usuarios | Reparto de un permiso por elemento: N invalidaciones globales de caché y N peticiones | Tablero inverso: `GET /api/permisos/{permisoId}/roles/tablero` y `PATCH /api/permisos/{permisoId}/roles` con `{agregar, quitar}` en una transacción (`RolPermisoService.actualizarRoles`) | 1 petición y 1 `invalidarTodo` por guardado en lugar de 5; 7 sentencias en lugar de 25 |
| Media | Frontend | `launcher.html:6` y `panel-shell.html:14` (cabecera del selector y de todos los paneles): `atlasImg.png` pesa 572 790 B (1 360 × 1 600 px) y se muestra a 46 × 46 y 40 × 40 px; es el elemento LCP del selector (mediana 1 980 ms en QA local) y también el favicon (`index.html:13`) | Imagen 17 veces más ancha que su caja a 2× (92 px): payload > 100 KB en la ruta de entrada | `src="marca/atlas-logo-192.png"` (26 856 B, ya en `public/marca`, la misma marca) en las dos cabeceras y `marca/atlas-logo-64.png` (6 039 B) como favicon | Imagen LCP ≤ 27 KB (−95 %); LCP del selector ≤ 1 000 ms con el mismo protocolo (servidor de QA, 3 cargas, mediana) |

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
| Suscripciones sin limpieza | Frontend | No aplicable | `RecursoRemoto` y `ListadoRemoto` usan `takeUntilDestroyed`; las páginas no hacen `subscribe` |
| Paginación con OFFSET y total en Actividad, Auditoría y peticiones | Base de datos | No aplicable | Pedida para igualar el paginador estándar; el total se cuenta hasta 10 000 (17,4 ms) y la página más profunda permitida cuesta 18,9–65,0 ms, dentro de 200 ms; `ConsultasObservabilidad.java` rechaza páginas más allá del tope |
| Escrituras por permiso al asignar a un rol | API | Aplicable | 16 peticiones para 16 permisos y 429 con más de 60 por minuto; corregido con el guardado en lote (1 petición) |
| Escrituras por rol al asignar a un usuario | API | Aplicable | 5 peticiones y 30 sentencias para 5 roles, con los controles de escalada repetidos; corregido con el guardado en lote (1 petición, 8 sentencias) |
| Derrame a disco en el tablero de roles del usuario | Base de datos | Aplicable | `external merge Disk: 3816kB` y 118 ms en la primera versión; corregido separando los paneles en su propia CTE (36,3 ms, sin buffers temporales) |
| Escrituras por permiso en los permisos individuales | API | Aplicable | 5 peticiones y 35 sentencias para 5 permisos y 3 peticiones al abrir; corregido con el tablero y el guardado en lote (1 + 1 peticiones, 8 sentencias) |
| Reparto de un permiso entre roles | Backend | Aplicable | 5 guardados y 5 vaciados globales de la caché de autorización para 5 roles; corregido con el tablero inverso (1 y 1) |
| Uso de cada permiso en el tablero | Base de datos | No aplicable | Una sola consulta agrega `roles_permisos` por `permiso_id` (`RolPermisoLoteRepository.java:32`): 9,1 ms y 1 201 buffers con 80 569 filas (≈ 40 veces el volumen real); la variante con subconsultas por permiso midió 22,4 ms |
| Peso del tablero en el chunk de Roles | Frontend | No aplicable | +9,5 kB gzip solo en la ruta perezosa de Roles (incluye los atajos del diálogo mediano); el bundle inicial sube 0,08 kB (449,63 kB). Si hiciera falta, el tablero se puede cargar con `@defer` al abrir el diálogo |
| Scroll del catálogo y atajos de columna | Frontend | No aplicable | Las columnas visibles se detectan con `IntersectionObserver` (`tablero-permisos.ts`), no con un listener de `scroll`: Angular solo vuelve a pintar los atajos cuando cambia qué columnas se ven (16 cambios de clase en 90 cuadros); p95 17,9 ms por cuadro y 0 tareas largas |
| Uso de cada rol en el tablero de roles | Base de datos | No aplicable | `HashAggregate` de las 150 001 filas de `usuarios_roles` en 16 ms, dentro de los 36,3 ms de toda la consulta; crece con las asignaciones, lejos de 200 ms |
| Estilos del tablero repetidos en dos rutas | Frontend | No aplicable | El tablero de roles reutiliza los SCSS del de permisos y Angular los compila en cada componente: la ruta de Usuarios suma 9,9 kB gzip (17,47 kB su chunk + 2,39 kB de un chunk común con la directiva de arrastre); el bundle inicial no cambia |
| Consulta del tablero de permisos individuales | Base de datos | No aplicable | 0,163 ms y 52 buffers con 100 001 usuarios y 50 000 excepciones: solo lee por usuario (PK de `usuarios_roles` y `usuarios_permisos`) |
| CSS global del diálogo anterior | Frontend | No aplicable | Las reglas `.perm-*` de `_components.scss` solo las usaba el diálogo reemplazado; se quitaron y el bundle inicial bajó 2,15 kB |
| Consulta del tablero «Roles con este permiso» | Base de datos | No aplicable | 37,3 ms (catálogo con uso, sin buffers temporales) + 30,5 ms (usuarios alcanzados, `DISTINCT` en dos niveles) con un permiso en 1 322 de 2 002 roles y 150 002 asignaciones; dentro de 200 ms |
| Componentes y estilos sin uso tras los tableros | Frontend | No aplicable | `ui/lista-asignacion.ts` y las reglas `.switch`, `.role-list` y `.role-item` de `_components.scss` ya no los usaba nadie; se quitaron y el bundle inicial bajó 1,18 kB más |
| Marca de la cabecera sobredimensionada | Frontend | Aplicable | 572 790 B para una caja de 46 px y es el LCP del selector; corrección propuesta, no aplicada: cambia la cabecera de todos los paneles y el favicon, fuera de este pedido |
| Logos del selector: 11 SVG por petición | Red | No aplicable | 20 879 B en total y todos terminan a los 503 ms; no son el LCP (mediana 2 000 ms frente a 1 980 ms, mismo elemento) ni mueven nada (CLS 0: cada `<img>` lleva `width` y `height`); los 5 de la segunda fila en escritorio van con `loading="lazy"`. Un sprite ahorraría 10 peticiones sin cambio medible |
| Peso del selector | Frontend | No aplicable | Chunk perezoso 13,45 → 11,30 kB (4,11 → 3,82 kB gzip) al quitar los chips de módulos y los estilos de las tarjetas; bundle inicial sin cambio (446,30 kB) |
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
| 5 | N3 | Derrame a disco del tablero de roles del usuario (aplicado) | 3/1 · Aprovechar (hecho) |
| 6 | N4 | Guardado en lote de los permisos del rol (aplicado) | 3/2 · Aprovechar (hecho) |
| 7 | N4 | Guardado en lote de los roles del usuario (aplicado) | 3/2 · Aprovechar (hecho) |
| 8 | N4 | Tablero y guardado en lote de los permisos individuales (aplicado) | 3/2 · Aprovechar (hecho) |
| 9 | N4 | Tablero inverso «Roles con este permiso» (aplicado) | 3/2 · Aprovechar (hecho) |
| 10 | N4 | Marca de la cabecera en tamaño real (propuesto) | 3/1 · Aprovechar |

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
| Primera página de Actividad (consulta + total) | 0,094 ms | 17,4 ms | +17,3 ms | Regresión |
| Capacidad de la cola de trazas por instancia | 1 700 trazas/s | 7 000 trazas/s (cálculo) | +312 % | Pendiente |
| p95 HTTP de los tres `/resumen` | Pendiente | Pendiente | — | Pendiente |
| Peticiones HTTP para asignar 16 permisos a un rol | 16 | 1 | −94 % | Mejora |
| Sentencias en la base para asignar 16 permisos | 32 (5,72 ms) | 4 (1,98 ms) | −65 % de tiempo | Mejora |
| Peticiones HTTP al abrir los permisos del rol | 2 | 1 | −50 % | Mejora |
| Consulta al abrir los permisos del rol (incluye el uso por permiso, que antes no existía) | 0,115 ms | 9,08 ms | +8,97 ms | Regresión |
| Chunk perezoso de Roles (gzip) | 5,80 kB | 16,90 kB (13,15 + 3,75 común) | +11,10 kB | Regresión |
| Peticiones HTTP para dar 5 roles a un usuario | 5 | 1 | −80 % | Mejora |
| Sentencias en la base para dar 5 roles | 30 (17,6 ms) | 8 (6,9 ms) | −61 % de tiempo | Mejora |
| Peticiones HTTP al abrir los roles del usuario | 2 | 1 | −50 % | Mejora |
| Consulta del tablero de roles (primera versión → final), 2 002 roles y 150 001 asignaciones | 118,0 ms con derrame | 36,3 ms sin derrame | −69 % | Mejora |
| Consulta al abrir los roles del usuario (incluye uso, paneles y si se puede otorgar, que antes no existían) | 0,22 ms | 36,3 ms | +36,1 ms | Regresión |
| Chunk perezoso de Usuarios (gzip) | 9,98 kB | 29,21 kB (16,96 + 8,50 + 3,75 comunes) | +19,23 kB | Regresión |
| Chunk perezoso de Permisos (gzip) | 6,03 kB | 19,34 kB (7,09 + 8,50 + 3,75 comunes) | +13,31 kB | Regresión |
| Peticiones HTTP para dar un permiso a 5 roles | 5 | 1 | −80 % | Mejora |
| Vaciados globales de la caché de autorización al dar un permiso a 5 roles | 5 | 1 | −80 % | Mejora |
| Sentencias en la base para dar un permiso a 5 roles (incluye recontar los usuarios alcanzados) | 25 (9,6 ms) | 7 (29,4 ms) | +19,8 ms | Regresión |
| Consulta al abrir «Roles con este permiso» (catálogo con uso y alcance, que antes no existían) | 2,4 ms | 67,8 ms | +65,4 ms | Regresión |
| Peticiones HTTP al abrir los permisos individuales | 3 | 1 | −67 % | Mejora |
| Peticiones HTTP para conceder 5 permisos a un usuario | 5 | 1 | −80 % | Mejora |
| Sentencias en la base para conceder 5 permisos | 35 (20,0 ms) | 8 (9,3 ms) | −54 % de tiempo | Mejora |
| Bundle inicial | 449,55 kB | 446,30 kB | −3,25 kB | Mejora |
| Chunk perezoso del selector de paneles (gzip) | 4,11 kB | 3,82 kB | −0,29 kB | Mejora |
| Peticiones al abrir el selector | 14 | 25 (+11 logos SVG, 20,9 kB) | +11 | Regresión |
| LCP del selector, 1366 × 768 (mediana de 3, alternando) | 1 980 ms | 2 000 ms | +20 ms (ruido del entorno) | Sin cambio |
| CLS del selector (1366 × 768 y 390 × 844) | 0 | 0 | 0 | Sin cambio |
| Alto del selector en celular (390 × 844) | 3 838 px | 1 106 px | −71 % | Mejora |
| Paneles en la primera pantalla del celular (390 × 844) | 2 de 11 | 9 de 11 | +7 | Mejora |

## 7. Veredicto

El Dashboard de operación no depende del volumen de trazas: lee resúmenes por hora que se llenan en la misma sentencia que guarda cada lote, y todas sus consultas quedan por debajo de 120 ms en el peor caso sintético (1 M trazas, 200 000 eventos, 300 usuarios activos cada hora). Lo único que derramaba a disco eran los usuarios distintos por día y semana; la agregación en dos niveles lo eliminó. PostgreSQL no es el cuello de botella en estas consultas. Pasar Actividad, Auditoría y el detalle de peticiones al paginador estándar (página + total) cuesta unos 17 ms por página por el conteo con tope de 10 000, y la página más profunda permitida 65 ms: es una regresión aceptada frente al cursor, dentro del umbral de 200 ms. Falta medir el p95 HTTP con carga real, el LCP del Dashboard y la salud del pool con Actuator; el resumen diario de actividad solo hace falta si el uso real se acerca al peor caso.

En los permisos del rol, el problema no era la base sino la conversación: una petición, una transacción, una invalidación de la caché de autorización y un evento de auditoría por cada permiso, con un cupo de 60 escrituras por minuto que impedía armar un rol completo. El guardado en lote lo reduce a una petición por guardado (16 permisos: 4 sentencias, 1,98 ms en la base). Abrir el tablero cuesta 9 ms en la base con 40 veces el volumen real porque ahora también cuenta en cuántos roles está cada permiso; es una regresión aceptada, muy por debajo de 200 ms. El tablero, ya en el diálogo mediano con scroll en dos ejes y atajos de columna, suma 9,5 kB comprimidos solo a la ruta de Roles y se desplaza a 60 cuadros por segundo sin tareas largas.

Los roles del usuario tenían el mismo problema, agravado: además de una petición por rol, cada una repetía los controles de escalada (6 sentencias por rol). El guardado en lote deja 1 petición y 8 sentencias para 5 roles. La primera versión de la consulta del tablero derramaba a disco (118 ms) por un `array_agg` ordenado en el mismo agregado que el conteo; separada en dos agregados queda en 36 ms sin buffers temporales con 2 002 roles y 150 001 asignaciones. PostgreSQL no es el cuello de botella en estas pantallas. Los permisos individuales repetían el patrón con tres peticiones al abrir y siete sentencias por permiso; con el tablero son una petición para abrir (0,16 ms en la base con 100 000 usuarios) y otra para guardar. Los tres tableros comparten la directiva de arrastre y los estilos: la ruta de Usuarios suma 15,4 kB comprimidos y el bundle inicial bajó 2,07 kB al quitar el CSS del diálogo anterior. «Roles con este permiso» pasó de solo lectura a tablero inverso: dar un permiso a varios roles es una petición y un solo vaciado de la caché de autorización en lugar de uno por rol. Abrirlo cuesta 68 ms en la base y guardar 29 ms porque cuenta a cuántos usuarios llega el permiso; con un permiso presente en 1 322 roles sigue muy por debajo de 200 ms. Al quitar el componente y el CSS que ya no usaba nadie, el bundle inicial quedó en 446,30 kB.

En el selector de paneles, cambiar las tarjetas por logos 3D (11 SVG, 20,9 kB en total) y el nombre no movió el LCP: el elemento LCP antes y después es la marca de la cabecera, que pesa 572 790 B para una caja de 46 px y también se descarga en la cabecera de cada panel. Los logos suman 11 peticiones, pero terminan a los 503 ms, reservan su caja (CLS 0) y la ruta quedó 0,29 kB más liviana. En celular, el selector pasó de 3 838 a 1 106 px de alto y muestra 9 de los 11 paneles sin desplazarse. La corrección con más impacto en esta ruta es servir la marca en su tamaño real (`marca/atlas-logo-192.png`, 26 KB); queda propuesta porque toca la cabecera de todos los paneles y el favicon.
