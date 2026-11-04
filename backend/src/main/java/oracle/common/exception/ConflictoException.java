package oracle.common.exception;

/** 409: violacion de unicidad o recurso duplicado. */
public class ConflictoException extends RuntimeException {

    public ConflictoException(String mensaje) {
        super(mensaje);
    }
}
