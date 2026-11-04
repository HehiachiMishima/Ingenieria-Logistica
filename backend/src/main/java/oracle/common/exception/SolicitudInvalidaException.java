package oracle.common.exception;

/** 400: la peticion es valida en forma pero hace referencia a datos que no corresponden. */
public class SolicitudInvalidaException extends RuntimeException {

    public SolicitudInvalidaException(String mensaje) {
        super(mensaje);
    }
}
