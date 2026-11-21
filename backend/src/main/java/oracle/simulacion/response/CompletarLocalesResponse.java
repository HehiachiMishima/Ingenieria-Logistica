package oracle.simulacion.response;

/**
 * Resultado de completar los locales de un registro con su Excel.
 *
 * @param completadas rutas del registro que recibieron sus locales.
 * @param distintas rutas con el mismo código y tipo pero otros totales: no se tocaron.
 * @param sinExcel rutas del registro que no están en el Excel: siguen sin detalle.
 * @param soloEnExcel rutas del Excel que no existen en el registro: se ignoraron.
 */
public record CompletarLocalesResponse(int completadas, int distintas, int sinExcel, int soloEnExcel) {}
