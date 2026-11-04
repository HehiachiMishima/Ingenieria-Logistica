package oracle.malla.response;

/** Parámetro tipado de una regla, listo para pintarse en el módulo Configuración. */
public record ParametroMallaResponse(
        String clave,
        String etiqueta,
        String tipo,
        String valor,
        /** Rango permitido de los ENTERO; null en los demás tipos. */
        Integer min,
        Integer max) {
}
