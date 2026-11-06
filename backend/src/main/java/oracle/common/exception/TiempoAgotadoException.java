package oracle.common.exception;

/** Una operacion supero su tope de tiempo (p. ej. un lote de consultas analiticas) -> 503. */
public class TiempoAgotadoException extends RuntimeException {

    public TiempoAgotadoException(String mensaje) {
        super(mensaje);
    }
}
