package oracle.common.config;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Arma el datasource de PostgreSQL a partir de variables de entorno, con
 * varias conexiones declaradas a la vez y una activa:
 *
 * <pre>
 * DB_CONEXION=NUBE                 # bloque activo
 * DB_NUBE_HOST=...  DB_NUBE_PORT=5432  DB_NUBE_NAME=...
 * DB_NUBE_USER=...  DB_NUBE_PASSWORD=...  DB_NUBE_SSLMODE=verify-full
 * DB_NUBE_POOL=10                  # opcional: conexiones fijas de esta instancia
 * DB_LOCAL_HOST=localhost ...      # otro bloque, listo para conmutar
 * </pre>
 *
 * Cada bloque acepta, en lugar de host/puerto/base/usuario/clave, una sola
 * {@code DB_<NOMBRE>_URL} con la cadena {@code postgresql://usuario:clave@host:puerto/base}
 * (la que entrega Render). Sin {@code DB_CONEXION} se leen las variables sin
 * prefijo ({@code DB_HOST}, {@code DB_URL}, ...).
 *
 * El search_path de la conexión lleva los esquemas de la aplicación, así las
 * consultas no necesitan prefijo. Corre después de {@link DotenvEnvironmentPostProcessor}
 * para ver también las claves del .env; una {@code spring.datasource.url}
 * explícita (argumento o variable SPRING_DATASOURCE_URL) sigue ganando.
 */
public class ConexionBaseDatos implements EnvironmentPostProcessor, Ordered {

    /** Esquemas de la aplicación, en el orden del search_path. */
    static final String ESQUEMAS = "seguridad,bitacora,simulacion,malla,public";

    private static final String FUENTE = "conexion-base-datos";
    private static final int PUERTO_POR_DEFECTO = 5432;
    private static final List<String> SSL_MODOS = List.of("disable", "prefer", "require", "verify-ca", "verify-full");

    /**
     * Conexiones por instancia. Las piden los services (un hilo virtual por peticion),
     * la analitica (hasta 6 consultas a la vez, ver EjecutorAnalitica) y el filtro JWT al
     * resolver los permisos de un usuario aun no cacheado. Con hilos virtuales este pool es
     * el limite real de concurrencia contra la base. En la prueba de carga (1 CPU, 1000 usuarios)
     * 10 se agotaba en el arranque en frio de la cache; 20 no. Deja margen en la base
     * (max_connections=100) para una segunda instancia.
     */
    static final int POOL_POR_DEFECTO = 20;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        Map<String, Object> propiedades = resolver(env::getProperty);
        env.getPropertySources().addLast(new MapPropertySource(FUENTE, propiedades));
    }

    /** Lector de variables: el Environment en producción, un mapa en las pruebas. */
    interface Variables {
        String get(String clave);
    }

    static Map<String, Object> resolver(Variables variables) {
        String conexion = texto(variables.get("DB_CONEXION"));
        String prefijo = conexion == null ? "DB_" : "DB_" + conexion.toUpperCase(Locale.ROOT) + "_";
        String origen = conexion == null ? "las variables DB_*" : "la conexion '" + conexion + "' (" + prefijo + "*)";

        Datos datos = datos(variables, prefijo, origen);
        String sslmode = valor(variables, prefijo + "SSLMODE", "require");
        if (!SSL_MODOS.contains(sslmode)) {
            throw new IllegalStateException(prefijo + "SSLMODE debe ser uno de " + SSL_MODOS + ".");
        }
        int pool = entero(variables, prefijo + "POOL", POOL_POR_DEFECTO, 1, 50);

        String url = "jdbc:postgresql://" + datos.host() + ":" + datos.puerto() + "/" + datos.base()
                + "?sslmode=" + sslmode
                // verify-ca / verify-full validan el certificado con el almacen de confianza de
                // Java (las CA publicas), sin depender de ~/.postgresql/root.crt.
                + (sslmode.startsWith("verify") ? "&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory" : "")
                + "&currentSchema=" + ESQUEMAS
                + "&reWriteBatchedInserts=true"
                + "&ApplicationName=atlas-backend"
                + "&connectTimeout=5&socketTimeout=60&tcpKeepAlive=true"
                // Limites del lado del servidor: ninguna sentencia, espera de bloqueo o
                // transaccion abandonada retiene una conexion del pool indefinidamente.
                + "&options=" + opciones(
                        "statement_timeout=" + entero(variables, prefijo + "STATEMENT_TIMEOUT_MS", 15_000, 1_000, 300_000),
                        "lock_timeout=5000",
                        "idle_in_transaction_session_timeout=30000");

        Map<String, Object> propiedades = new LinkedHashMap<>();
        propiedades.put("spring.datasource.url", url);
        propiedades.put("spring.datasource.username", datos.usuario());
        propiedades.put("spring.datasource.password", datos.clave());
        // Pool FIJO (minimo = maximo): abrir una conexion TLS cuesta mas de un segundo y no
        // debe ocurrir en medio de un pico. Ver el dimensionamiento en el README.
        propiedades.put("spring.datasource.hikari.maximum-pool-size", pool);
        propiedades.put("spring.datasource.hikari.minimum-idle", pool);
        propiedades.put("app.base-datos.conexion", conexion == null ? "PREDETERMINADA" : conexion.toUpperCase(Locale.ROOT));
        return propiedades;
    }

    private record Datos(String host, int puerto, String base, String usuario, String clave) {
    }

    private static Datos datos(Variables variables, String prefijo, String origen) {
        String cadena = texto(variables.get(prefijo + "URL"));
        if (cadena != null) {
            return desdeCadena(cadena, prefijo + "URL");
        }
        List<String> faltan = new ArrayList<>();
        String host = requerido(variables, prefijo + "HOST", faltan);
        String base = requerido(variables, prefijo + "NAME", faltan);
        String usuario = requerido(variables, prefijo + "USER", faltan);
        String clave = variables.get(prefijo + "PASSWORD");
        if (clave == null) {
            faltan.add(prefijo + "PASSWORD");
        }
        if (!faltan.isEmpty()) {
            throw new IllegalStateException("Falta configurar " + origen + ": " + String.join(", ", faltan)
                    + " (o una sola " + prefijo + "URL).");
        }
        return new Datos(host, puerto(valor(variables, prefijo + "PORT", null), prefijo + "PORT"), base, usuario, clave);
    }

    /** postgresql://usuario:clave@host:puerto/base (también postgres://). */
    private static Datos desdeCadena(String cadena, String clave) {
        URI uri;
        try {
            uri = URI.create(cadena);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(clave + " no es una cadena de conexion valida.", ex);
        }
        String esquema = uri.getScheme();
        String info = uri.getRawUserInfo();
        String ruta = uri.getPath();
        if (!("postgresql".equals(esquema) || "postgres".equals(esquema)) || uri.getHost() == null
                || info == null || ruta == null || ruta.length() < 2) {
            throw new IllegalStateException(clave + " debe tener la forma postgresql://usuario:clave@host:puerto/base.");
        }
        int dosPuntos = info.indexOf(':');
        String usuario = decodificar(dosPuntos < 0 ? info : info.substring(0, dosPuntos));
        String contrasena = dosPuntos < 0 ? "" : decodificar(info.substring(dosPuntos + 1));
        int puerto = uri.getPort() < 0 ? PUERTO_POR_DEFECTO : uri.getPort();
        return new Datos(uri.getHost(), puerto, ruta.substring(1), usuario, contrasena);
    }

    /** Parametros de sesion de PostgreSQL ({@code -c clave=valor}) codificados para la URL. */
    private static String opciones(String... parametros) {
        StringBuilder texto = new StringBuilder();
        for (String parametro : parametros) {
            texto.append(texto.isEmpty() ? "" : " ").append("-c ").append(parametro);
        }
        return URLEncoder.encode(texto.toString(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static int entero(Variables variables, String clave, int porDefecto, int minimo, int maximo) {
        String valor = texto(variables.get(clave));
        if (valor == null) {
            return porDefecto;
        }
        try {
            int numero = Integer.parseInt(valor);
            if (numero < minimo || numero > maximo) {
                throw new IllegalStateException(clave + " debe estar entre " + minimo + " y " + maximo + ".");
            }
            return numero;
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(clave + " debe ser un numero entero.", ex);
        }
    }

    private static int puerto(String valor, String clave) {
        if (valor == null) {
            return PUERTO_POR_DEFECTO;
        }
        try {
            return Integer.parseInt(valor);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(clave + " debe ser un numero de puerto.", ex);
        }
    }

    private static String requerido(Variables variables, String clave, List<String> faltan) {
        String valor = texto(variables.get(clave));
        if (valor == null) {
            faltan.add(clave);
        }
        return valor;
    }

    private static String valor(Variables variables, String clave, String porDefecto) {
        String valor = texto(variables.get(clave));
        return valor == null ? porDefecto : valor;
    }

    private static String texto(String valor) {
        return StringUtils.hasText(valor) ? valor.strip() : null;
    }

    /** Decodifica %XX; un '+' en la cadena es literal (no un espacio). */
    private static String decodificar(String valor) {
        return URLDecoder.decode(valor.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
