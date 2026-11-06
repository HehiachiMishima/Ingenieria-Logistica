# Atlas · Plataforma de Ingeniería Logística (Farmacias Peruanas)

**Atlas** (*Interfaz Atlas*) es la plataforma de control operativo de
Ingeniería Logística: gobierno de identidades, roles y permisos, y
registro/seguimiento de incidencias (Bitácora), proyección y
balanceo de rutas (Simulación), con acceso por paneles según los permisos de
cada usuario. Su nombre es un acróstico:
**A**nálisis · **T**razabilidad · **L**ogística · **A**utomatización ·
**S**incronización.

- **Frontend**: Angular 22 (standalone, zoneless, signals) + SCSS. Diseño
  "Atlas": cromo navy, rojo y oro de marca, paleta de señales, modo claro/oscuro.
- **Backend**: Spring Boot 4.1 (Java 25), API REST stateless con JWT (HS256),
  organizado **por paneles**.
- **Base de datos**: PostgreSQL 15+ (Render en producción), con un esquema por
  dominio y conexiones múltiples por variables de entorno.

---

## Paneles

| Panel | Ruta | Módulos | Permiso de entrada |
| --- | --- | --- | --- |
| Administración | `/admin` | Dashboard, Usuarios, Roles, Permisos | `PANEL_ADMIN` |
| Bitácora | `/bitacora` | Incidencias, Control (centros, sistemas, áreas, responsables), Dashboard | `PANEL_BITACORA` |
| Simulación | `/simulacion` | Registro (Excel), Configuración (clusters, chutes, balanceo), Dashboard | `PANEL_SIMULACION` |
| Malla Operativa | `/malla` | Malla (turnos, flujos de proceso, olas, zonas, capacidad) | `PANEL_MALLA` |
| Análisis | `/analisis` | Resumen | `PANEL_ANALISIS` |
| Trazabilidad | `/trazabilidad` | Resumen | `PANEL_TRAZABILIDAD` |
| Logística | `/logistica` | Resumen | `PANEL_LOGISTICA` |
| Automatización | `/automatizacion` | Resumen | `PANEL_AUTOMATIZACION` |
| Sincronización | `/sincronizacion` | Resumen | `PANEL_SINCRONIZACION` |

Los paneles Análisis, Trazabilidad, Logística, Automatización y Sincronización
exigen su permiso de entrada igual que el resto. Su módulo Resumen muestra un
estado vacío neutro mientras se implementa la lógica del panel.

El *launcher* (`/`) muestra los paneles y, en cada uno, los módulos habilitados
para el usuario. Cada ruta protegida valida su permiso con `permisoGuard`, y
cada endpoint con `@PreAuthorize`.

El panel **Malla Operativa** reutiliza el tablero de la malla ya construido,
pero no lo publica como archivo estático: el backend entrega el HTML en
`GET /api/malla/vista` sólo con `VER_MALLA`, y el frontend lo descarga con el
token y lo muestra en un iframe. `MODIFICAR_MALLA` habilita la edición y
`EXPORTAR_MALLA` la descarga en Excel; sin ellos la vista queda en solo lectura.
Los centros y su malla se crean desde el propio panel (formulario o importación
de Excel); las reglas de evaluación vienen precargadas en `backend/postgresql.sql`.

Los cinco paneles nuevos aún **no tienen lógica**: están marcados `publico: true`
en `core/config/paneles.ts` (visibles para cualquier usuario autenticado, vía
`panelGuard`) y su página `pages/resumen` muestra la vista común
`EnConstruccion`. Para darles lógica:

1. Crea el permiso en `backend/postgresql.sql` (sección 7) y quita `publico: true` del panel.
2. Añade sus módulos en `paneles.ts` (id, ruta, icono, permiso).
3. Registra cada módulo en `<panel>.routes.ts` con `permisoGuard` y reemplaza
   `pages/resumen` por las páginas reales.

La ruta raíz de cada panel redirige a su primer módulo accesible con
`aPrimerModulo(panel)` (`features/panels/panel-rutas.ts`).

---

## Estructura del proyecto

```
.
├── Dockerfile                  # Imagen única: frontend + backend + nginx
├── docker-compose.yml          # Stack local opcional (PostgreSQL + imagen)
├── render.yaml                 # Blueprint de Render (1 servicio web)
├── backend/
│   ├── .env.example            # Plantilla de variables (el .env real no se sube)
│   ├── .env.example            # Plantilla de variables (el .env real no se sube)
│   ├── postgresql.sql          # Instalación completa: esquemas, tablas, triggers, permisos y catálogos
│   ├── datos.sql               # Incidencias de ejemplo de Bitácora (opcional)
│   └── src/main/java/oracle/
│       ├── auth/               # Login y sesión vigente
│       ├── admin/              # Panel Administración (usuarios, roles, permisos, resolución)
│       ├── bitacora/           # Panel Bitácora (incidencias + catálogos de control)
│       ├── simulacion/         # Panel Simulación (Excel, configuración, balanceo, dashboard)
│       ├── malla/              # Panel Malla Operativa (entrega protegida del tablero)
│       └── common/             # Transversal: config, security, exception, support, web
└── frontend/src/app/
    ├── core/                   # config, guards, interceptors, models, services, utils
    ├── features/               # auth, launcher, panels/{admin,bitacora,simulacion,malla,…}
    └── shared/                 # directivas, pipes y UI (icon, modal, charts, …)
```

Cada panel del backend tiene sus propias capas:
`controller / service / repository / model / request / response`.

---

## Marca e interfaz

| Recurso | Uso |
| --- | --- |
| `public/marca/login-almacen{-800,,-2704}.webp` | Foto del almacén con robots (800 / 1352 / 2704 px, escalada 4× con Real-ESRGAN) — parte baja del hero del login, servida con `srcset`. |
| `public/atlasImg.png` | Logo Atlas — favicon, sidebar y launcher. |
| `public/marca/atlas-logo.webp` | Logo Atlas optimizado — pantalla de carga. |
| `public/marca/fp-apple-touch.png` | Ícono de iOS (corazón FP sobre blanco). |
| `public/marca/atlas-carga.webp` | Fondo de la pantalla de carga. |
| `public/marca/fp-nombre.webp` | Logotipo Farmacias Peruanas (HD, fondo transparente). |

Los originales (`atlasLogo.jpg`, `atlasCarga.jpg`, `FarmaciasPeruanasName.jpg`)
quedan en `public/` como fuente; la app usa las versiones `webp` optimizadas.

- **Login**: réplica del diseño de referencia. Hero con marca FP + IL, título
  ATLAS, acróstico en ciclo, mapa real del Perú con la red de distribución
  desde Lima y cuatro pines (Almacenes, Distribución, Farmacias, Datos y
  analítica), foto del almacén con destellos láser y barra roja de capas; a la
  derecha, la tarjeta de acceso. Datos de la escena en
  `features/auth/login/login-escena.ts`. En móvil el mapa y la barra se ocultan
  y la foto queda como franja bajo el texto. Respeta `prefers-reduced-motion`.
- **Pantalla de carga** (`ui-loader` + servicio `Carga`): "Ingresando a Atlas" /
  "Saliendo de Atlas", con un tiempo mínimo en pantalla para que no parpadee.
- **Modales** (`ui-modal`): sombra por capas, filete de marca, bloqueo del
  scroll del documento y, en móvil (< 640 px), hoja inferior con pie fijo.
- **Mi cuenta**: datos del usuario y cambio de contraseña, responsivo.
- Los listados en móvil se mantienen como **registros** (filas), no tarjetas.

---

## Base de datos

PostgreSQL 15 o superior. Hibernate no toca el esquema (`ddl-auto=none`): todo
se instala con `backend/postgresql.sql` y los `.sql` no entran en la imagen.

| Paso | Archivo | Notas |
| --- | --- | --- |
| 1 | `postgresql.sql` | Instalación completa en **una transacción**. ⚠️ Borra y recrea los esquemas `comun`, `seguridad`, `bitacora`, `simulacion` y `malla`. |
| 2 | **Arrancar el backend una vez** | Crea el usuario semilla `USER_ADMIN` con BCrypt y le asigna `USER_ROL`. |
| 3 | `datos.sql` *(opcional)* | 53 incidencias de ejemplo de Bitácora. Re-ejecutable (omite tickets existentes). |

```bash
psql "host=<host> port=5432 dbname=<base> user=<usuario> sslmode=require" -v ON_ERROR_STOP=1 -f backend/postgresql.sql
```

Organización y decisiones del esquema:

| Esquema | Contenido |
| --- | --- |
| `comun` | Colación `comun.ci`, trigger `fijar_actualizado_en` y `proteger()` (errores `AT403`). |
| `seguridad` | Usuarios, roles, permisos, overrides, `sesiones_refresh` y vistas `v_permisos_efectivos` / `v_permisos_finales` (deny-wins con `bool_and`). |
| `bitacora` | Catálogos (centro, sistema, área, responsable, tipo) e `incidencias`. |
| `simulacion` | Registros Excel, clusters, chutes, parámetros, rutas, lanzamientos e historial de balanceo. |
| `malla` | Centros, turnos, flujos, olas, zonas, capacidad, dotación, historial de cambios y reglas. |

- La conexión lleva `currentSchema=seguridad,bitacora,simulacion,malla,public`,
  así el backend consulta las tablas sin prefijo.
- Ids `GENERATED ALWAYS AS IDENTITY`; instantes en `TIMESTAMPTZ` (UTC) y
  `actualizado_en` mantenido por trigger en cada `UPDATE`.
- Nombres, logins, emails y códigos usan la colación ICU `comun.ci`: comparan
  e indexan **sin mayúsculas ni tildes** (`Lima` = `LIMA` = `Líma`), también en
  los `UNIQUE`. PostgreSQL no admite `LIKE` ni regex sobre esa colación, por eso
  las columnas que se buscan por texto (ticket, descripción, rutas) no la llevan
  y se indexan con trigram (`pg_trgm`) o `varchar_pattern_ops`.
- Valores cerrados con `CHECK` (estados, tipos B2C/B2B, procesos, severidades…),
  `rutas_proyeccion.movida` es columna generada `STORED` y
  `malla_regla.parametros` es `JSONB`.
- Protecciones (rol `ADMINISTRADOR`, permisos base, usuarios `@admin` y
  `@hehiachi`) en triggers PL/pgSQL; el backend responde 403 con su mensaje.

## Configuración del entorno

El backend lee sus variables de `backend/.env` (no se sube al repositorio) o
del entorno; Render las inyecta desde el dashboard.

```bash
cp backend/.env.example backend/.env
```

**Conexiones múltiples.** Declara uno o varios bloques `DB_<NOMBRE>_*` y elige
el activo con `DB_CONEXION` (lo resuelve `ConexionBaseDatos` al arrancar):

```dotenv
DB_CONEXION=NUBE
DB_NUBE_HOST=dpg-xxxx.oregon-postgres.render.com
DB_NUBE_PORT=5432
DB_NUBE_NAME=ingenieria_logistica
DB_NUBE_USER=...
DB_NUBE_PASSWORD=...
DB_NUBE_SSLMODE=require

#DB_LOCAL_HOST=localhost   (y DB_CONEXION=LOCAL para usarlo)
```

| Variable | Descripción |
| --- | --- |
| `PORT` | Puerto **público** del contenedor (nginx). Render lo inyecta (`10000`). |
| `BACKEND_PORT` | Puerto **interno** del backend Java. Debe ser **distinto** de `PORT`. |
| `DB_CONEXION` | Bloque activo (`NUBE`, `LOCAL`, …). Sin ella se usan `DB_HOST`, `DB_NAME`, … sin prefijo. |
| `DB_<N>_HOST`, `_PORT`, `_NAME`, `_USER`, `_PASSWORD` | Datos de la conexión `<N>` (puerto por defecto `5432`). |
| `DB_<N>_URL` | Alternativa: `postgresql://usuario:clave@host:puerto/base` (la URL que da Render). |
| `DB_<N>_SSLMODE` | `disable`, `prefer`, `require` (por defecto), `verify-ca` o `verify-full` (valida el certificado con las CA de Java; úsalo al conectar por red pública). |
| `DB_<N>_POOL` | Conexiones **fijas** por instancia (por defecto 20, máx. 50). Ver **Rendimiento y escalado**. |
| `DB_<N>_STATEMENT_TIMEOUT_MS` | Tiempo máximo por sentencia en el servidor (por defecto 15000). |
| `JWT_SECRET` | Firma JWT. **Mínimo 32 bytes** (`openssl rand -hex 32`). |
| `JWT_ISSUER` | Emisor del token (`iss`). |
| `JWT_EXPIRATION_MS` | Vida del access token en ms (opcional, por defecto `900000`). |
| `CORS_ALLOWED_ORIGINS` | Orígenes permitidos (solo si el SPA se sirve desde otro dominio). |
| `USER_ADMIN`, `USER_PASSWORD`, `USER_EMAIL` | **Obligatorios** para crear el administrador semilla. |
| `USER_NAME`, `USER_LASTNAME`, `USER_PHONE`, `USER_ACTIVO`, `USER_ROL` | Datos opcionales del administrador semilla. |
| `JAVA_OPTS` | (Solo Docker) flags de la JVM. |

> Los usuarios `@admin` y `@hehiachi` están protegidos en la BD
> (`seguridad.es_usuario_protegido`) **y** en el backend
> (`Protecciones.USUARIOS_PROTEGIDOS`): no se pueden editar, borrar ni cambiar
> sus roles/permisos. Si usas otro `USER_ADMIN`, ajusta ambos.

---

## Desarrollo local (sin Docker)

### Backend

```bash
cd backend
./mvnw spring-boot:run
```

API en `http://localhost:2003/api`.

### Frontend

```bash
cd frontend
npm install
npm start
```

SPA en `http://localhost:4200` (usa `environment.development.ts`, que apunta a
`http://localhost:2003/api`).

### Tests del backend

```bash
cd backend
./mvnw test -Dtest='!BackendApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false
```

(`BackendApplicationTests` levanta el contexto completo y necesita una BD.)

---

## Docker

La imagen es **100 % producción**: compila el SPA con `apiUrl: '/api'`, no
copia `backend/.env`, los `.sql` ni los certificados, y activa el perfil
`prod` (logs JSON). Todas las credenciales entran por variables de entorno.

### Imagen sola (igual que Render)

```bash
docker build -t farmacias-peruanas:local .
```

```bash
docker run --rm -p 10000:10000 --env-file backend/.env -e PORT=10000 -e BACKEND_PORT=2003 farmacias-peruanas:local
```

App en `http://localhost:10000`; health check en `/api/health` (204). La
imagen trae `HEALTHCHECK` propio.

> Si tu conexión activa apunta a un PostgreSQL en `127.0.0.1`, dentro del
> contenedor usa `-e DB_LOCAL_HOST=host.docker.internal`.

### Stack completo con PostgreSQL (`docker compose`)

```bash
docker compose up --build
```

- PostgreSQL 17 en `localhost:5433`; al crear el volumen aplica `postgresql.sql`.
- La app usa el bloque `LOCAL` hacia ese contenedor, crea `@admin` y queda en
  `http://localhost:10000`.
- Opcional, después del primer arranque, los ejemplos de Bitácora:

```bash
docker compose exec -T postgres psql -U atlas -d ingenieria_logistica -v ON_ERROR_STOP=1 < backend/datos.sql
```

Los secretos (`JWT_SECRET`, `USER_*`) se leen de `backend/.env` si existe.

---

## Despliegue en Render

`render.yaml` crea **un solo servicio web** `farmacias-peruanas`
(`runtime: docker`, `plan: free`, `region: oregon`, rama `farmacias_peruanas`,
`autoDeploy`).

1. Sube el repositorio a GitHub/GitLab.
2. Render: **New + → Blueprint** y selecciona el repositorio.
3. Completa los valores `sync: false`: `DB_NUBE_URL` (la *Internal Database
   URL* de tu PostgreSQL de Render), `USER_*`, `SFTP_PASSWORD` y (opcional)
   `CORS_ALLOWED_ORIGINS`.
4. Aplica `postgresql.sql` en la base antes del primer deploy (ver **Base de
   datos**); `datos.sql` después, si quieres los ejemplos.
5. Abre `https://farmacias-peruanas.onrender.com`.

Notas:

- `JWT_SECRET` se genera automáticamente (`generateValue: true`).
- nginx reenvía `X-Forwarded-Proto` original (Render termina TLS), así Spring
  reconoce el propio dominio y no aplica CORS a las peticiones del SPA.
- En `plan: free` el servicio se suspende tras ~15 min sin tráfico; el primer
  arranque en frío tarda ~1 min. Para miles de usuarios usa un plan con RAM
  ≥ 1 GB y sube `spring.datasource.hikari.maximum-pool-size` según el límite
  de conexiones de tu plan de PostgreSQL (o fija `DB_NUBE_POOL`).

### Si el deploy falla

| Síntoma | Causa probable |
| --- | --- |
| Error de YAML al sincronizar el Blueprint | Claves duplicadas o mal escritas en `render.yaml`. |
| Deploy en rojo, health check `502` | El backend no arranca: la base no es alcanzable o no se aplicó `postgresql.sql`. |
| `Falta configurar la conexion 'NUBE' (DB_NUBE_*)` | Falta una variable de conexión en el dashboard (el mensaje dice cuáles). |
| `JWT_SECRET ... al menos 32 bytes` | `JWT_SECRET` demasiado corto. |
| Build larga / sin memoria | La build compila Angular + Maven; en `free` conviene reintentar. |

---

## Endpoints principales

Base: `/api`

| Método | Ruta | Acceso |
| --- | --- | --- |
| `POST` | `/auth/login` | Público (rate limit por IP e IP+usuario) |
| `GET` | `/auth/sesion` | JWT — usuario y permisos vigentes |
| `GET` | `/health` | Público |
| `GET/POST/PUT/DELETE` | `/usuarios`, `/roles`, `/permisos` | JWT + permiso |
| `GET` | `/usuarios/resumen` | Roles y permisos de todos los usuarios (2 consultas) |
| `GET/POST/PUT/DELETE` | `/centros`, `/areas`, `/sistemas` | JWT + `*_CONTROL` |
| `GET/POST/PUT/DELETE` | `/incidencias` (+ `/exportar`, `/estadisticas`) | JWT + `*_INCIDENCIAS` / `VER_DASHBOARD` |
| `GET` | `/incidencias/analitica` | `VER_DASHBOARD` — KPIs, Pareto de sistemas, matriz centro × sistema, tipos, tendencia y horas |
| `GET` | `/admin/analitica` | `DASHBOARD_ADMIN` — cuentas, roles, permisos, cobertura por panel y altas |
| `GET` | `/malla/vista` | `VER_MALLA` — HTML autónomo de la malla (`text/html`, `no-store`) |

---

## Rendimiento y escalado

- **Listados del frontend** (`ListadoRemoto` / `RecursoRemoto`): un único flujo
  con `switchMap` cancela la petición anterior, los errores no rompen la
  búsqueda y la tabla conserva las filas mientras refresca (sin parpadeo).
- **Catálogos cacheados** en el navegador (`BitacoraCatalogos`) y en el
  servidor (`CatalogoBitacora`, invalidado en cada CRUD): resolver una página de incidencias no hace
  consultas por fila.
- **Permisos**: caché Caffeine por usuario (invalidada al cambiar roles,
  permisos o estado); el frontend resincroniza la sesión al volver a la pestaña.
- **Usuarios**: el listado obtiene roles/permisos de todos con
  `/usuarios/resumen` en lugar de 2 peticiones por usuario.
- **Dashboards**: cada endpoint de analítica lanza sus agregaciones SQL
  (`GROUP BY`, CTE + `ROW_NUMBER`) **en paralelo** con `Mono.zip` sobre un
  scheduler acotado (`EjecutorAnalitica`, `app.analitica.paralelismo`, 6 por
  defecto): la latencia es la de la consulta más lenta y un pico de dashboards
  nunca agota el pool de conexiones de los CRUD. El resultado se cachea por
  filtro (single-flight) y se invalida al escribir incidencias o catálogos.
  Usa `COUNT(*) FILTER`, CTE y funciones de ventana de PostgreSQL.
- **Gráficos**: componentes SVG/CSS propios (`OnPush`, sin librerías): KPI con
  variación vs periodo anterior, Pareto, mapa de calor, barras apiladas,
  líneas y columnas. Se adaptan al contenedor sin deformarse.
- **Responsive**: en móvil las tablas se muestran como tarjetas
  (`data-label` automático) y el panel de filtros es una hoja inferior.
- **Infra**: virtual threads, `open-in-view=false`, apagado ordenado, nginx
  con gzip, keep-alive al backend y cache inmutable de assets.

### Capacidad medida (prueba de carga local)

Instancia simulada como Render (1 CPU visible, heap 384 MB, pool 20), base con
**1 M de incidencias, 4 M de rutas, 2 M de movimientos y 500 mil sesiones**,
1000 usuarios activos a la vez (una petición cada ~350 ms):

| Métrica | Valor |
| --- | --- |
| Throughput | ~2 600–2 750 req/s |
| Latencia p50 / p95 / p99 | < 1 ms / 86–132 ms / 0,3–1 s |
| Errores / esperas de conexión | 0 |

Desde fuera de Render (Lima → Oregón) cada ida y vuelta a la base cuesta
~176 ms y abrir una conexión TLS ~1,4 s: **el servicio web y la base deben
estar en la misma región** (`oregon` en `render.yaml`).

### Pool de conexiones

- **Fijo** (`minimumIdle = maximumPoolSize`, `DB_<N>_POOL`, 20 por defecto):
  ninguna petición paga el ~1 s de abrir una conexión TLS en un pico.
- Lo usan los services (`Schedulers.boundedElastic`, 10 × CPU), la analítica
  (6) y el filtro JWT cuando un usuario no está en caché. Con 10 se agotaba
  en el arranque en frío de la caché; con 20, nunca.
- Sin conexión libre en 3 s → **503** (`Retry-After`), no una cola infinita;
  si el filtro JWT no puede consultar la base responde 503, **no 401**
  (no cierra la sesión del usuario).
- Límites en el servidor por conexión: `statement_timeout` 15 s
  (`DB_<N>_STATEMENT_TIMEOUT_MS`), `lock_timeout` 5 s e
  `idle_in_transaction_session_timeout` 30 s. Aviso en el log si una conexión
  se retiene más de 30 s.
- Tamaño en la base: `max_connections` es 100 en tu plan de Render (≈12 los usa
  Render). Con 20 por instancia caben 3–4 instancias.

### Datos masivos: decisiones medidas

| Problema (1 M / 4 M filas) | Causa | Corrección | Antes → después |
| --- | --- | --- | --- |
| Exportar incidencias | El cursor `a < x OR (a = x AND id < y)` no acota el índice: cada lote recorría lo ya leído | `a <= x AND (a < x OR id < y)` (`CursorRecientes`) + tope de 50 000 filas | 120 ms → 1,5 ms por lote; sin filtro: 128 s / 503 MB → 400 inmediato |
| Analítica de Bitácora | `GROUP BY` por nombres con colación ICU (`comun.ci`) fila a fila y `COUNT(DISTINCT)` | Agrupar por ids y unir los nombres después | 2,27 s → 0,47 s |
| Búsqueda de rutas por prefijo | Índice aparte con `varchar_pattern_ops` (165 MB) | `codigo_ruta COLLATE "C"`: el índice único sirve para `LIKE 'x%'` | Índices de rutas 630 → 341 MB; búsqueda 0,6 ms |
| Restablecer un cluster | Un `UPDATE` por ruta (~26 mil) | Un `UPDATE … FROM unnest(arrays)` por bloque | 1,6 s → 0,17 s |
| `/usuarios/resumen` | Usuarios × permisos en cada llamada (~39 mil filas) | Snapshot invalidado con cada cambio de RBAC | Sin carga repetida |
| Borrar un usuario | FK `historial_balanceo.creado_por` sin índice (recorría 2 M) | Índice | recorrido → búsqueda por índice |
| `sesiones_refresh` | Solo se purgaba al volver a entrar cada usuario | Purga global cada hora, en lotes (`PurgaSesiones`) | La tabla ya no crece sin límite |
| Filas muertas | Autovacuum al 20 % (800 mil en 4 M) | 2 % en rutas e historial, 5 % en incidencias y sesiones | Estadísticas y limpieza al día |

- **Rate limiting** por usuario y panel (lecturas, escrituras, analítica,
  exportación) con 429 + `Retry-After`. La poda de contadores corre como mucho
  cada 10 s; antes, con más de 10 000 claves (≈3 000 usuarios activos), cada
  petición recorría el mapa entero. Es memoria **por instancia**: con varias
  instancias cada una cuenta lo suyo (para límites globales, Redis).
- **Inyección SQL**: todo valor del usuario va como parámetro enlazado; los
  únicos fragmentos concatenados son nombres de tabla/columna constantes del
  código. No hay orden (`ORDER BY`) tomado de la petición. Los comodines de
  `LIKE` del usuario se escapan (`Filtros.escaparLike`).
- **Esquema validado al arrancar** (`ddl-auto=validate`, entidades con su
  esquema explícito): si la base no coincide con el código, la app no arranca.

---

## Seguridad

- `backend/.env` y `backend/certs/` están ignorados por Git (y nunca estuvieron
  en el historial); **no** los subas.
- **Conexión a la base**: `verify-full` valida el certificado con las CA de Java
  (úsalo desde fuera de Render). Recomendado: rol de ejecución `atlas_app` solo
  con DML (sección 8 de `postgresql.sql`); el dueño del esquema queda para
  instalar.
- **Contraseñas**: 10 a 72 bytes (BCrypt ignora lo que sigue), también para
  `USER_PASSWORD` del semilla. Cambiar la contraseña invalida al instante los
  access tokens emitidos antes (`credenciales_desde`) y cierra las demás
  sesiones. `@admin` y `@hehiachi` pueden cambiar su propia contraseña; nada más
  de su fila.
- **Fuerza bruta**: límites por IP+usuario (10/min), por IP (30/min) y por
  **cuenta** desde cualquier IP (20 / 15 min); el intento se cuenta antes de
  verificar la contraseña (sin carreras). Cambio de contraseña: 5 / 15 min.
- **Sin escalada de privilegios**: nadie otorga un permiso que no tiene ni
  administra (edita, cambia la clave, desactiva, borra, cambia roles o
  permisos) a un usuario con permisos que él no tiene (`ControlEscalamiento`).
- Errores de triggers (`SQLSTATE AT403`) llegan como 403 con su mensaje; un
  borrado bloqueado por FK llega como 409 claro.
- nginx: `Content-Security-Policy` estricta (sin scripts inline), HSTS,
  `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`,
  `Permissions-Policy`, COOP/CORP en **cada** ruta; la API sale con
  `Cache-Control: no-store`.
- Exportaciones CSV: los textos que empiezan con `= + - @` se neutralizan (no se
  ejecutan como fórmula en Excel).
- `X-Request-Id` entrante solo se acepta con forma segura; los logs de login no
  incluyen el usuario tecleado.
- Rota `JWT_SECRET` y las credenciales de la base periódicamente.
