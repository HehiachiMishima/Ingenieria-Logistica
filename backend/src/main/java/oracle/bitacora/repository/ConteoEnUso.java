package oracle.bitacora.repository;

/**
 * Conteo acotado de incidencias que usan un catalogo (antes de eliminarlo):
 * la consulta se detiene en {@link #TOPE} filas en vez de contar todas.
 */
public final class ConteoEnUso {

    /** Filas maximas que se cuentan; desde ahi el mensaje dice «N o más». */
    public static final int TOPE = 1000;

    /** «12», o «1000 o más» si se llego al tope. */
    public static String texto(long conteo) {
        return conteo >= TOPE ? TOPE + " o más" : String.valueOf(conteo);
    }

    private ConteoEnUso() {
    }
}
