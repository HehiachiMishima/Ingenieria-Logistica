package oracle.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

/** Las excepciones de Spring con codigo propio no deben salir como 500. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/prueba");

    @Test
    void tiempoAgotadoEs503ConApiError() {
        ResponseEntity<ApiError> respuesta =
                handler.tiempoAgotado(new TransactionTimedOutException("Transaction timed out"), req);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(respuesta.getBody().status()).isEqualTo(503);
        assertThat(respuesta.getBody().error()).isEqualTo("Service Unavailable");
        assertThat(respuesta.getBody().ruta()).isEqualTo("/api/prueba");
    }

    @Test
    void loteAnaliticoVencidoEs503ConSuMensaje() {
        ResponseEntity<ApiError> respuesta =
                handler.tiempoAgotado(new TiempoAgotadoException("El analisis tardo demasiado."), req);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(respuesta.getBody().mensaje()).isEqualTo("El analisis tardo demasiado.");
    }

    @Test
    void poolAgotadoEs503ConRetryAfterYNo500() {
        ResponseEntity<ApiError> respuesta = handler.sinConexion(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"), req);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(respuesta.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(respuesta.getBody().status()).isEqualTo(503);
    }

    @Test
    void sinConexionGanaAlManejadorGenericoDeBaseDeDatos() {
        ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

        assertThat(resolver.resolveMethod(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"))
                .getName()).isEqualTo("sinConexion");
        assertThat(resolver.resolveMethod(new CannotCreateTransactionException("pool")).getName())
                .isEqualTo("sinConexion");
    }

    @Test
    void parteMultipartFaltanteEs400() {
        ResponseEntity<ApiError> respuesta =
                handler.parteFaltante(new MissingServletRequestPartException("archivo"), req);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(respuesta.getBody().mensaje()).isEqualTo("Falta la parte obligatoria 'archivo'.");
    }

    @Test
    void responseStatusExceptionConservaSuCodigo() {
        ResponseEntity<ApiError> respuesta =
                handler.inesperado(new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe."), req);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(respuesta.getBody().mensaje()).isEqualTo("No existe.");
    }

    @Test
    void excepcionDesconocidaSigueSiendo500() {
        ResponseEntity<ApiError> respuesta = handler.inesperado(new IllegalStateException("bug"), req);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(respuesta.getBody().mensaje()).isEqualTo("Error interno inesperado.");
    }

    private ResponseEntity<ApiError> errorBase(String sqlState, String mensaje) {
        return handler.baseDeDatos(
                new DataIntegrityViolationException("falla", new SQLException(mensaje, sqlState)), req);
    }

    @Test
    void triggerDeProteccionEs403ConSuMensajeLimpio() {
        ResponseEntity<ApiError> respuesta = errorBase("AT403",
                "ERROR: No se puede eliminar el rol ADMINISTRADOR.\n  Where: PL/pgSQL function comun.proteger(text)");

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(respuesta.getBody().mensaje()).isEqualTo("No se puede eliminar el rol ADMINISTRADOR.");
    }

    @Test
    void padreEnUsoYDuplicadoSon409() {
        assertThat(errorBase("23503", "ERROR: update or delete on table \"data_centro\" violates foreign key")
                .getBody().mensaje()).isEqualTo("El registro esta en uso por otros datos y no se puede eliminar.");
        assertThat(errorBase("23505", "ERROR: duplicate key value violates unique constraint")
                .getBody().mensaje()).isEqualTo("Ya existe un registro con esos datos.");
    }
}
