package oracle.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ConexionBaseDatosTest {

    private static Map<String, Object> resolver(Map<String, String> variables) {
        return ConexionBaseDatos.resolver(variables::get);
    }

    @Test
    void usaElBloqueElegidoConDbConexion() {
        Map<String, Object> props = resolver(Map.of(
                "DB_CONEXION", "nube",
                "DB_NUBE_HOST", "db.render.com",
                "DB_NUBE_NAME", "atlas",
                "DB_NUBE_USER", "app",
                "DB_NUBE_PASSWORD", "s3cr#t",
                "DB_LOCAL_HOST", "localhost"));

        assertThat((String) props.get("spring.datasource.url"))
                .startsWith("jdbc:postgresql://db.render.com:5432/atlas?sslmode=require&currentSchema="
                        + ConexionBaseDatos.ESQUEMAS)
                .contains("&reWriteBatchedInserts=true", "&connectTimeout=5",
                        "&options=-c%20statement_timeout%3D15000%20-c%20lock_timeout%3D5000"
                                + "%20-c%20idle_in_transaction_session_timeout%3D30000")
                .doesNotContain("sslfactory");
        assertThat(props.get("spring.datasource.hikari.maximum-pool-size")).isEqualTo(ConexionBaseDatos.POOL_POR_DEFECTO);
        assertThat(props.get("spring.datasource.hikari.minimum-idle")).isEqualTo(ConexionBaseDatos.POOL_POR_DEFECTO);
        assertThat(props.get("spring.datasource.username")).isEqualTo("app");
        assertThat(props.get("spring.datasource.password")).isEqualTo("s3cr#t");
        assertThat(props.get("app.base-datos.conexion")).isEqualTo("NUBE");
    }

    @Test
    void aceptaLaCadenaDeConexionDeRender() {
        Map<String, Object> props = resolver(Map.of(
                "DB_CONEXION", "LOCAL",
                "DB_LOCAL_URL", "postgresql://app:p%40ss+1@localhost:6543/atlas",
                "DB_LOCAL_SSLMODE", "disable"));

        assertThat((String) props.get("spring.datasource.url"))
                .startsWith("jdbc:postgresql://localhost:6543/atlas?sslmode=disable&");
        assertThat(props.get("spring.datasource.username")).isEqualTo("app");
        assertThat(props.get("spring.datasource.password")).isEqualTo("p@ss+1");
    }

    @Test
    void sinDbConexionLeeLasVariablesSinPrefijo() {
        Map<String, Object> props = resolver(Map.of(
                "DB_HOST", "h", "DB_PORT", "5433", "DB_NAME", "b", "DB_USER", "u", "DB_PASSWORD", "p"));

        assertThat((String) props.get("spring.datasource.url")).startsWith("jdbc:postgresql://h:5433/b?");
        assertThat(props.get("app.base-datos.conexion")).isEqualTo("PREDETERMINADA");
    }

    @Test
    void nombraLasVariablesQueFaltan() {
        assertThatThrownBy(() -> resolver(Map.of("DB_CONEXION", "NUBE", "DB_NUBE_HOST", "h")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_NUBE_NAME")
                .hasMessageContaining("DB_NUBE_USER")
                .hasMessageContaining("DB_NUBE_PASSWORD");
    }

    @Test
    void verifyFullValidaElCertificadoConLasCaDeJavaYElPoolEsFijo() {
        Map<String, Object> props = resolver(Map.of(
                "DB_CONEXION", "NUBE", "DB_NUBE_URL", "postgresql://u:p@h/b",
                "DB_NUBE_SSLMODE", "verify-full", "DB_NUBE_POOL", "6", "DB_NUBE_STATEMENT_TIMEOUT_MS", "8000"));

        assertThat((String) props.get("spring.datasource.url"))
                .contains("sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory")
                .contains("statement_timeout%3D8000");
        assertThat(props.get("spring.datasource.hikari.maximum-pool-size")).isEqualTo(6);
        assertThat(props.get("spring.datasource.hikari.minimum-idle")).isEqualTo(6);
    }

    @Test
    void rechazaValoresFueraDeRango() {
        Map<String, String> base = Map.of("DB_URL", "postgresql://u:p@h/b");
        assertThatThrownBy(() -> resolver(conMas(base, "DB_SSLMODE", "allow")))
                .hasMessageContaining("DB_SSLMODE");
        assertThatThrownBy(() -> resolver(conMas(base, "DB_POOL", "500")))
                .hasMessageContaining("DB_POOL debe estar entre 1 y 50");
    }

    private static Map<String, String> conMas(Map<String, String> base, String clave, String valor) {
        Map<String, String> copia = new java.util.HashMap<>(base);
        copia.put(clave, valor);
        return copia;
    }
}
