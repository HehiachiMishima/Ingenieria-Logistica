package oracle.common.exception;

/** Credenciales invalidas o cuenta desactivada. Mapea a 401. */
public class NoAutorizadoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NoAutorizadoException(String mensaje) {
        super(mensaje);
    }
}
