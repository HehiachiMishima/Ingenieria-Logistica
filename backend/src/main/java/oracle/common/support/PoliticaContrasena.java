package oracle.common.support;

import java.nio.charset.StandardCharsets;
import oracle.common.exception.SolicitudInvalidaException;

/**
 * Reglas de una contrasena nueva. El minimo frena las claves triviales; el
 * maximo es el de BCrypt, que ignora todo despues de 72 bytes (y Spring
 * rechaza las mas largas con un error 500 si no se valida antes).
 */
public final class PoliticaContrasena {

    public static final int MINIMO = 10;
    public static final int MAXIMO_BYTES = 72;
    public static final String MENSAJE =
            "La contrasena debe tener al menos " + MINIMO + " caracteres y como maximo " + MAXIMO_BYTES + " bytes.";

    /** True si la contrasena cumple la politica. */
    public static boolean cumple(String contrasena) {
        return contrasena != null
                && contrasena.codePointCount(0, contrasena.length()) >= MINIMO
                && contrasena.getBytes(StandardCharsets.UTF_8).length <= MAXIMO_BYTES
                && !contrasena.isBlank();
    }

    /** 400 con un mensaje claro si la contrasena no cumple la politica. */
    public static void validar(String contrasena) {
        if (!cumple(contrasena)) {
            throw new SolicitudInvalidaException(MENSAJE);
        }
    }

    private PoliticaContrasena() {
    }
}
