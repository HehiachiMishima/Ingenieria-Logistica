package oracle.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Carga el archivo .env como pares CLAVE=VALOR crudos, sin el escapado ni el
 * recorte de comentarios del formato .properties (que corrompe contrasenas con
 * # $ o comillas). Los valores se agregan con la MENOR precedencia, asi que
 * argumentos de linea de comandos y variables de entorno siempre ganan.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DotenvEnvironmentPostProcessor.class);

    private static final String ARCHIVO = ".env";
    private static final String NOMBRE_FUENTE = "dotenv";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path ruta = Path.of(ARCHIVO);
        if (!Files.isRegularFile(ruta)) {
            return;
        }

        Map<String, Object> valores = new LinkedHashMap<>();
        try {
            for (String linea : Files.readAllLines(ruta, StandardCharsets.UTF_8)) {
                String limpia = linea.strip();
                if (limpia.isEmpty() || limpia.startsWith("#")) {
                    continue;
                }
                if (limpia.startsWith("export ")) {
                    limpia = limpia.substring("export ".length()).strip();
                }
                int separador = limpia.indexOf('=');
                if (separador <= 0) {
                    continue;
                }
                String clave = limpia.substring(0, separador).strip();
                String valor = sinComillas(limpia.substring(separador + 1).strip());
                valores.put(clave, valor);
            }
        } catch (IOException ex) {
            log.warn("No se pudo leer {}: {}", ARCHIVO, ex.getMessage());
            return;
        }

        if (!valores.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(NOMBRE_FUENTE, valores));
            log.debug("Se cargaron {} claves desde {}", valores.size(), ARCHIVO);
        }
    }

    private String sinComillas(String valor) {
        if (valor.length() >= 2) {
            char primero = valor.charAt(0);
            char ultimo = valor.charAt(valor.length() - 1);
            if ((primero == '"' && ultimo == '"') || (primero == '\'' && ultimo == '\'')) {
                return valor.substring(1, valor.length() - 1);
            }
        }
        return valor;
    }

    /** Antes que {@link ConexionBaseDatos}, que lee las claves de la conexion. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}
