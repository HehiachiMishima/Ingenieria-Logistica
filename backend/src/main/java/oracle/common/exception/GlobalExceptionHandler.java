
package oracle.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Punto unico donde las excepciones del backend se convierten en respuestas HTTP.
 * Toda excepcion que lancen los services cae aqui y sale como ApiError.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<ApiError> noEncontrado(RecursoNoEncontradoException ex, HttpServletRequest req) {
        return cuerpo(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    @ExceptionHandler(NoAutorizadoException.class)
    public ResponseEntity<ApiError> noAutorizado(NoAutorizadoException ex, HttpServletRequest req) {
        return cuerpo(HttpStatus.UNAUTHORIZED, ex.getMessage(), req);
    }

    @ExceptionHandler(LimiteExcedidoException.class)
    public ResponseEntity<ApiError> limiteExcedido(LimiteExcedidoException ex, HttpServletRequest req) {
        return cuerpo(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), req);
    }

    @ExceptionHandler(OperacionNoPermitidaException.class)
    public ResponseEntity<ApiError> noPermitido(OperacionNoPermitidaException ex, HttpServletRequest req) {
        return cuerpo(HttpStatus.FORBIDDEN, ex.getMessage(), req);
    }

    /**
     * Denegacion de @PreAuthorize (AccessDeniedException / AuthorizationDeniedException).
     * Sin este handler la capturaria el catch-all y saldria como 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> accesoDenegado(AccessDeniedException ex, HttpServletRequest req) {
        return cuerpo(HttpStatus.FORBIDDEN, "No tiene el permiso requerido para esta operacion.", req);
    }

    @ExceptionHandler(ConflictoException.class)
    public ResponseEntity<ApiError> conflicto(ConflictoException ex, HttpServletRequest req) {
        return cuerpo(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    @ExceptionHandler(SolicitudInvalidaException.class)
    public ResponseEntity<ApiError> solicitudInvalida(SolicitudInvalidaException ex, HttpServletRequest req) {
        return cuerpo(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    /** Validaciones jakarta (@Size, @Min...) sobre parametros de consulta o de ruta. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> parametrosInvalidos(
            HandlerMethodValidationException ex, HttpServletRequest req) {
        String detalle = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> r.getMethodParameter().getParameterName() + ": " + e.getDefaultMessage()))
                .collect(Collectors.joining("; "));
        return cuerpo(HttpStatus.BAD_REQUEST,
                detalle.isBlank() ? "Hay parametros invalidos en la peticion." : "Parametros invalidos: " + detalle + ".",
                req);
    }

    /** Validaciones jakarta (@NotBlank, @Size...) declaradas en los Request. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validacion(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> campos = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(f -> campos.put(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.conCampos(400, "Validacion fallida",
                        "Hay campos invalidos en la peticion.", req.getRequestURI(), campos));
    }

    // ------------------------------------------------------------------
    // Excepciones de infraestructura de Spring MVC. Sin estos handlers las
    // capturaria el catch-all y devolverian 500 en vez del codigo correcto.
    // ---------------------------------------------------------------------

    /** Cuerpo JSON ausente o mal formado. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> cuerpoIlegible(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return cuerpo(HttpStatus.BAD_REQUEST,
                "El cuerpo de la peticion es obligatorio y debe ser un JSON valido.", req);
    }

    /** Path variable / query param con tipo incorrecto (p. ej. id no numerico). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> tipoInvalido(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        return cuerpo(HttpStatus.BAD_REQUEST,
                "El parametro '" + ex.getName() + "' tiene un formato invalido.", req);
    }

    /** Falta un parametro obligatorio de la peticion. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> parametroFaltante(
            MissingServletRequestParameterException ex, HttpServletRequest req) {
        return cuerpo(HttpStatus.BAD_REQUEST,
                "Falta el parametro obligatorio '" + ex.getParameterName() + "'.", req);
    }

    /** Falta una parte obligatoria de una peticion multipart (p. ej. el archivo). */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> parteFaltante(
            MissingServletRequestPartException ex, HttpServletRequest req) {
        return cuerpo(HttpStatus.BAD_REQUEST,
                "Falta la parte obligatoria '" + ex.getRequestPartName() + "'.", req);
    }

    /** Metodo HTTP no soportado por el recurso. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> metodoNoSoportado(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return cuerpo(HttpStatus.METHOD_NOT_ALLOWED,
                "El metodo " + ex.getMethod() + " no esta permitido en esta ruta.", req);
    }

    /** Content-Type no soportado. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> tipoContenidoNoSoportado(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        return cuerpo(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "El Content-Type de la peticion no esta soportado. Use application/json.", req);
    }

    /** Archivo mayor que spring.servlet.multipart.max-file-size: 413 con mensaje claro. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> archivoDemasiadoGrande(
            MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return cuerpo(HttpStatus.CONTENT_TOO_LARGE,
                "El archivo supera el tamano maximo permitido (20 MB).", req);
    }

    /**
     * La operacion supero su tope de tiempo: el de un lote analitico
     * (TiempoAgotadoException) o el total de una transaccion de lectura
     * (TransactionTimedOutException, ver Transacciones.consultar con tope) -> 503.
     */
    @ExceptionHandler({TiempoAgotadoException.class, TransactionTimedOutException.class})
    public ResponseEntity<ApiError> tiempoAgotado(RuntimeException ex, HttpServletRequest req) {
        String mensaje = ex instanceof TiempoAgotadoException
                ? ex.getMessage()
                : "La consulta tardo demasiado en responder. Intente de nuevo.";
        return cuerpo(HttpStatus.SERVICE_UNAVAILABLE, mensaje, req);
    }

    /** Ruta inexistente: ninguna coincidencia de handler ni recurso estatico. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> rutaInexistente(NoResourceFoundException ex, HttpServletRequest req) {
        return cuerpo(HttpStatus.NOT_FOUND, "El recurso solicitado no existe.", req);
    }

    /**
     * Errores de PostgreSQL, por SQLSTATE:
     *  - AT403: trigger de proteccion (comun.proteger) -> 403 con su mensaje.
     *  - 23503: FK; al borrar o cambiar un padre en uso -> 409 claro.
     *  - 23505: UNIQUE (uq_*) -> 409 de duplicado.
     *  - resto de violaciones de integridad (CHECK, NOT NULL) -> 409 generico.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> baseDeDatos(DataAccessException ex, HttpServletRequest req) {
        SQLException sql = causaSql(ex);
        String estado = sql == null ? null : sql.getSQLState();
        if (ESTADO_PROTECCION.equals(estado)) {
            return cuerpo(HttpStatus.FORBIDDEN, mensajeServidor(sql), req);
        }
        // lock_timeout (55P03) o deadlock (40P01): otra persona está modificando lo mismo.
        if (ex instanceof PessimisticLockingFailureException
                || ESTADO_SIN_BLOQUEO.equals(estado) || ESTADO_INTERBLOQUEO.equals(estado)) {
            return cuerpo(HttpStatus.CONFLICT,
                    "Otra persona esta modificando este registro en este momento. Intente de nuevo en unos segundos.", req);
        }
        if (ESTADO_FK.equals(estado) && sql.getMessage() != null
                && sql.getMessage().contains("update or delete on table")) {
            return cuerpo(HttpStatus.CONFLICT,
                    "El registro esta en uso por otros datos y no se puede eliminar.", req);
        }
        if (ESTADO_UNICO.equals(estado)) {
            return cuerpo(HttpStatus.CONFLICT, "Ya existe un registro con esos datos.", req);
        }
        if (ex instanceof DataIntegrityViolationException) {
            log.warn("Violacion de integridad: {}", ex.getMessage());
            return cuerpo(HttpStatus.CONFLICT,
                    "La operacion viola una restriccion de integridad del esquema.", req);
        }
        if (ex instanceof QueryTimeoutException) {
            return cuerpo(HttpStatus.SERVICE_UNAVAILABLE,
                    "La base de datos tardo demasiado en responder. Intente de nuevo.", req);
        }
        log.error("Error de acceso a datos", ex);
        return cuerpo(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno inesperado.", req);
    }

    /**
     * Sin conexion a la base: el pool Hikari se agoto (connection-timeout de 3 s)
     * o la base no responde. Al abrir la transaccion llega como
     * CannotCreateTransactionException; en JDBC sin transaccion (analitica), como
     * CannotGetJdbcConnectionException. Es saturacion pasajera, no un error del
     * codigo: 503 con Retry-After para que el cliente reintente, y una linea de
     * aviso (sin stack) para no inundar el log justo cuando hay mas carga.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, CannotGetJdbcConnectionException.class})
    public ResponseEntity<ApiError> sinConexion(RuntimeException ex, HttpServletRequest req) {
        log.warn("Sin conexion a la base para {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(REINTENTAR_EN_S))
                .body(ApiError.de(HttpStatus.SERVICE_UNAVAILABLE.value(),
                        HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                        "El sistema esta atendiendo muchas solicitudes. Intente de nuevo en unos segundos.",
                        req.getRequestURI()));
    }

    /** Segundos sugeridos al cliente para reintentar cuando no hay conexion libre. */
    static final int REINTENTAR_EN_S = 2;

    /** SQLSTATE con que fallan los triggers de proteccion (comun.proteger en postgresql.sql). */
    static final String ESTADO_PROTECCION = "AT403";
    private static final String ESTADO_FK = "23503";
    private static final String ESTADO_UNICO = "23505";
    /** lock_timeout vencido esperando el bloqueo de otra transacción. */
    private static final String ESTADO_SIN_BLOQUEO = "55P03";
    private static final String ESTADO_INTERBLOQUEO = "40P01";

    /**
     * Mensaje del trigger sin el envoltorio del driver: PostgreSQL lo entrega
     * como "ERROR: <mensaje>" seguido del contexto (Where: funcion PL/pgSQL...).
     */
    static String mensajeServidor(SQLException sql) {
        String mensaje = sql.getMessage() == null ? "" : sql.getMessage();
        int salto = mensaje.indexOf('\n');
        String linea = (salto < 0 ? mensaje : mensaje.substring(0, salto)).strip();
        return linea.startsWith("ERROR:") ? linea.substring("ERROR:".length()).strip() : linea;
    }

    private static SQLException causaSql(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    /**
     * Catch-all. Las excepciones propias de Spring que ya declaran su codigo
     * (ResponseStatusException y demas ErrorResponse) conservan ese codigo en
     * vez de convertirse en 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> inesperado(Exception ex, HttpServletRequest req) {
        if (ex instanceof ErrorResponse respuesta && !respuesta.getStatusCode().is5xxServerError()) {
            String detalle = respuesta.getBody().getDetail();
            HttpStatusCode status = respuesta.getStatusCode();
            log.debug("Error {} de Spring: {}", status.value(), ex.getMessage());
            return cuerpo(status, detalle != null ? detalle : razon(status), req);
        }
        if (ex instanceof ErrorResponse respuesta) {
            log.error("Error {} de Spring", respuesta.getStatusCode().value(), ex);
            return cuerpo(respuesta.getStatusCode(), "Error interno inesperado.", req);
        }
        log.error("Error inesperado", ex);
        return cuerpo(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno inesperado.", req);
    }

    private ResponseEntity<ApiError> cuerpo(HttpStatusCode status, String mensaje, HttpServletRequest req) {
        return ResponseEntity.status(status)
                .body(ApiError.de(status.value(), razon(status), mensaje, req.getRequestURI()));
    }

    private static String razon(HttpStatusCode status) {
        HttpStatus conocido = HttpStatus.resolve(status.value());
        return conocido != null ? conocido.getReasonPhrase() : String.valueOf(status.value());
    }
}
