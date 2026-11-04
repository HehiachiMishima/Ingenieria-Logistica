package oracle.simulacion.request;

/** Topes de los parámetros de simulación (validación de requests y balanceo). */
public final class Limites {

  /** Operarios por chute que se pueden declarar (tope técnico de validación). */
  public static final int MAX_OPERARIOS = 50;
  /** Operarios totales que se pueden declarar disponibles en un registro. */
  public static final int MAX_OPERARIOS_TOTAL = 10_000;
  /** Límite de horas por cluster: un mes de operación continua. */
  public static final String MAX_HORAS = "744.00";
  /** Bultos por hora por operario. */
  public static final String MAX_VELOCIDAD = "100000.00";
  /** Clusters o chutes por petición. */
  public static final int MAX_PARAMETROS = 2_000;
  /** Chutes que se pueden consultar a la vez en el detalle de rutas del dashboard. */
  public static final int MAX_CHUTES_DETALLE = 50;

  private Limites() {}
}
