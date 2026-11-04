# syntax=docker/dockerfile:1
# ============================================================
# Imagen UNICA: Angular (frontend) + Spring Boot (backend) + nginx.
#
#   - nginx sirve el SPA en el puerto PUBLICO (PORT) y reenvia /api
#     al backend, que corre en un puerto INTERNO (BACKEND_PORT).
#   - supervisord arranca ambos procesos dentro del mismo contenedor.
#   - Un solo contenedor => un solo servicio en Render.
#
# PUERTOS:
#   PORT          -> puerto publico (nginx). Es el UNICO que se publica
#                    con -p. Render lo inyecta (10000). Por defecto 10000.
#   BACKEND_PORT  -> puerto interno del backend Java (por defecto 2003).
#                    NO se publica nunca; solo nginx lo usa por dentro.
#
# El archivo backend/.env NO se copia: las credenciales entran como
# variables de entorno (Render las inyecta, docker run -e / --env-file).
# ============================================================

# ---------- Etapa 1: build del frontend ----------
FROM node:22-alpine AS frontend
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build -- --configuration production

# ---------- Etapa 2: build del backend ----------
FROM maven:3.9-eclipse-temurin-25 AS backend
WORKDIR /app
COPY backend/pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline
COPY backend/src ./src
RUN mvn -B -q -DskipTests clean package

# ---------- Etapa 3: runtime ----------
FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache nginx supervisor gettext \
    && mkdir -p /run/nginx

# Backend (jar ejecutable) y frontend (SPA compilado).
COPY --from=backend /app/target/*.jar /app/app.jar
COPY --from=frontend /app/dist/frontend/browser /usr/share/nginx/html

# --- Cabeceras de seguridad (se incluyen en CADA location: en nginx un
#     add_header dentro de un location anula los heredados del server) ---
#   CSP: solo scripts propios (el build no deja scripts inline), estilos
#   propios + Google Fonts (Angular inyecta <style>, por eso 'unsafe-inline'
#   en estilos, nunca en scripts), API en el mismo origen, sin iframes.
RUN cat > /etc/nginx/seguridad.conf <<'NGINX'
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com data:; img-src 'self' data: blob:; connect-src 'self'; frame-src 'none'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'; upgrade-insecure-requests" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=(), usb=()" always;
add_header Cross-Origin-Opener-Policy "same-origin" always;
add_header Cross-Origin-Resource-Policy "same-origin" always;
NGINX

# --- Config de nginx: sirve el SPA y proxifica /api al backend ---
RUN cat > /etc/nginx/http.d/default.conf.template <<'NGINX'
# Protocolo original del cliente: Render termina TLS antes de nginx y envia
# X-Forwarded-Proto=https. Se reenvia tal cual al backend; sin esto Spring
# veria "http" y trataria las peticiones del propio dominio como CORS.
map $http_x_forwarded_proto $proto_cliente {
    default $http_x_forwarded_proto;
    ''      $scheme;
}

# Solo se pide "upgrade" cuando el cliente lo solicita (WebSocket); el
# resto de peticiones reutiliza conexiones keep-alive hacia el backend.
map $http_upgrade $conexion_backend {
    default upgrade;
    ''      '';
}

upstream backend {
    server 127.0.0.1:${BACKEND_PORT};
    keepalive 64;
}

server {
    listen ${PORT};
    server_name _;
    server_tokens off;

    root /usr/share/nginx/html;
    index index.html;
    client_max_body_size 20m;

    gzip on;
    gzip_comp_level 5;
    gzip_min_length 1024;
    gzip_proxied any;
    gzip_vary on;
    gzip_types text/plain text/csv text/css text/javascript application/javascript application/json application/xml image/svg+xml font/woff2;

    include /etc/nginx/seguridad.conf;

    # Respuestas de la API: nunca en caches intermedias ni del navegador.
    location /api/ {
        include /etc/nginx/seguridad.conf;
        add_header Cache-Control "no-store" always;
        # Las mismas cabeceras que pone Spring Security: una sola copia (la de nginx).
        proxy_hide_header X-Content-Type-Options;
        proxy_hide_header X-Frame-Options;
        proxy_hide_header Strict-Transport-Security;
        proxy_hide_header Cache-Control;
        proxy_hide_header Pragma;
        proxy_hide_header Expires;
        proxy_pass http://backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $proto_cliente;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $conexion_backend;
        proxy_connect_timeout 15s;
        proxy_read_timeout 60s;
        proxy_send_timeout 60s;
        # Exportaciones en flujo (chunked): entregar al cliente sin acumular.
        proxy_buffering off;
    }

    # Assets con hash en el nombre (Angular outputHashing=all): cache larga.
    location ~* \.(?:js|css|woff2?|ttf|eot|png|jpe?g|gif|svg|webp|ico)$ {
        include /etc/nginx/seguridad.conf;
        add_header Cache-Control "public, max-age=31536000, immutable";
        try_files $uri =404;
    }

    # index.html nunca se cachea: asi cada deploy se ve al instante.
    location = /index.html {
        include /etc/nginx/seguridad.conf;
        add_header Cache-Control "no-cache, no-store, must-revalidate";
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
NGINX

# --- Wrapper para lanzar nginx en primer plano (evita problemas de quoting) ---
RUN printf '#!/bin/sh\nexec nginx -g "daemon off;"\n' > /usr/local/bin/run-nginx \
    && chmod +x /usr/local/bin/run-nginx

# --- Wrapper del backend: JAVA_OPTS permite ajustar la JVM sin rebuild ---
RUN printf '#!/bin/sh\nexec java $JAVA_OPTS -jar /app/app.jar --server.port="$BACKEND_PORT"\n' > /usr/local/bin/run-backend \
    && chmod +x /usr/local/bin/run-backend

# --- Supervisord: arranca backend y nginx en el mismo contenedor ---
RUN cat > /etc/supervisord.conf <<'SUPERVISORD'
[supervisord]
nodaemon=true
logfile=/dev/null
logfile_maxbytes=0
pidfile=/run/supervisord.pid

[program:backend]
command=/usr/local/bin/run-backend
directory=/app
autorestart=true
startsecs=5
stopwaitsecs=35
stdout_logfile=/dev/stdout
stdout_logfile_maxbytes=0
stderr_logfile=/dev/stderr
stderr_logfile_maxbytes=0

[program:nginx]
command=/usr/local/bin/run-nginx
autorestart=true
priority=20
stdout_logfile=/dev/stdout
stdout_logfile_maxbytes=0
stderr_logfile=/dev/stderr
stderr_logfile_maxbytes=0
SUPERVISORD

# --- Entrypoint: resuelve los puertos, genera la config de nginx y arranca ---
RUN cat > /entrypoint.sh <<'SH'
#!/bin/sh
set -e

PORT="${PORT:-10000}"
BACKEND_PORT="${BACKEND_PORT:-2003}"
export PORT BACKEND_PORT

if [ "$PORT" = "$BACKEND_PORT" ]; then
  echo "ERROR: PORT y BACKEND_PORT no pueden coincidir (=$PORT):" >&2
  echo "       nginx (publico) y el backend (interno) chocarian en el mismo puerto." >&2
  exit 1
fi

envsubst '${PORT} ${BACKEND_PORT}' \
  < /etc/nginx/http.d/default.conf.template \
  > /etc/nginx/http.d/default.conf

exec /usr/bin/supervisord -c /etc/supervisord.conf
SH
RUN chmod +x /entrypoint.sh

# PORT = publico (Render lo inyecta; por defecto 10000).
# BACKEND_PORT = interno del backend (por defecto 2003). Deben ser distintos.
# SPRING_PROFILES_ACTIVE=prod activa application-prod.properties (logs JSON).
ENV PORT=10000 \
    BACKEND_PORT=2003 \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
EXPOSE 10000

# Sano = nginx responde y el backend contesta /api/health (204).
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -q --spider "http://127.0.0.1:${PORT}/api/health" || exit 1

ENTRYPOINT ["/entrypoint.sh"]
