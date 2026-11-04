package oracle.common.exception;

/**
 * 403: protecciones del esquema replicadas en la capa de servicio
 * (rol ADMINISTRADOR, permisos BASE, usuario protegido).
 */
public class OperacionNoPermitidaException extends RuntimeException {

    public OperacionNoPermitidaException(String mensaje) {
        super(mensaje);
    }
}
