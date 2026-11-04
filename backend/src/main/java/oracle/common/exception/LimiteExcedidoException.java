package oracle.common.exception;

/** Demasiadas peticiones en poco tiempo (fuerza bruta). Mapea a 429. */
public class LimiteExcedidoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LimiteExcedidoException(String mensaje) {
        super(mensaje);
    }
}
