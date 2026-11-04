package oracle.malla.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catálogo técnico de las reglas de la malla: qué parámetros acepta cada
 * código, con qué etiqueta se muestran y qué valor usan si la base no los
 * trae. Los textos largos (nombre/descripción) viven en {@code malla_regla}
 * para que se puedan corregir desde Configuración sin recompilar.
 */
public final class ReglasMallaCatalogo {

    /** Un parámetro de regla; min y max acotan los ENTERO (null en los demás tipos). */
    public record ParametroDef(String clave, String etiqueta, String tipo, String porDefecto,
            Integer min, Integer max) {

        static ParametroDef entero(String clave, String etiqueta, String porDefecto, int min, int max) {
            return new ParametroDef(clave, etiqueta, "ENTERO", porDefecto, min, max);
        }

        static ParametroDef de(String clave, String etiqueta, String tipo, String porDefecto) {
            return new ParametroDef(clave, etiqueta, tipo, porDefecto, null, null);
        }
    }

    public static final String OLA_LANZAMIENTO = "OLA_LANZAMIENTO";
    public static final String OLA_SECUENCIA = "OLA_SECUENCIA";
    public static final String OLA_MAX_PICKING = "OLA_MAX_PICKING";
    public static final String OLA_CONFIRMADA = "OLA_CONFIRMADA";
    public static final String TURNO_COBERTURA = "TURNO_COBERTURA";
    public static final String TURNO_REFRIGERIO = "TURNO_REFRIGERIO";
    public static final String FLUJO_VENTANA_TURNO = "FLUJO_VENTANA_TURNO";
    public static final String FLUJO_DURACION = "FLUJO_DURACION";
    public static final String FLUJO_CONFIRMADO = "FLUJO_CONFIRMADO";
    public static final String CAPACIDAD_DECLARADA = "CAPACIDAD_DECLARADA";
    public static final String DOTACION_PLANIFICADA = "DOTACION_PLANIFICADA";
    public static final String ZONA_LPH_DISENO = "ZONA_LPH_DISENO";

    private ReglasMallaCatalogo() {
    }

    /** Parámetros que entiende el evaluador para un código. */
    public static List<ParametroDef> parametros(String codigo) {
        return switch (codigo) {
            case OLA_LANZAMIENTO -> List.of(
                    ParametroDef.de("limites", "Hora límite por ola", "LISTA_HORAS", "07:00,15:00"),
                    ParametroDef.entero("toleranciaMin", "Tolerancia (min)", "0", 0, 240));
            case OLA_MAX_PICKING -> List.of(
                    ParametroDef.entero("horasMax", "Máximo de picking (h)", "8", 1, 24));
            case TURNO_COBERTURA -> List.of(
                    ParametroDef.entero("minutosGapMax", "Hueco permitido (min)", "0", 0, 720));
            case TURNO_REFRIGERIO -> List.of(
                    ParametroDef.de("exigirRefrigerio", "Exigir refrigerio", "BOOLEANO", "true"));
            case FLUJO_VENTANA_TURNO -> List.of(
                    ParametroDef.de("incluirIL", "Evaluar también pasos de IL", "BOOLEANO", "false"));
            case FLUJO_DURACION -> List.of(
                    ParametroDef.entero("horasMax", "Duración máxima (h)", "12", 1, 24));
            default -> List.of();
        };
    }

    /** Valores por defecto, en el mismo orden en que se declaran. */
    public static Map<String, String> porDefecto(String codigo) {
        Map<String, String> valores = new LinkedHashMap<>();
        for (ParametroDef def : parametros(codigo)) {
            valores.put(def.clave(), def.porDefecto());
        }
        return valores;
    }
}
