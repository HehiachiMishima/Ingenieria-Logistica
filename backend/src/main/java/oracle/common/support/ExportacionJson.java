package oracle.common.support;

import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;

/**
 * Escribe un export en flujo JSON con exactamente el mismo envoltorio que
 * {@code ApiResponse&lt;List&lt;T&gt;&gt;} (exito, mensaje, datos, timestamp),
 * de modo que el contrato con el frontend no cambia.
 *
 * La lista de datos nunca se materializa: los elementos se escriben uno a uno
 * dentro del array {@code datos}, por lo que el pico de memoria de la JVM
 * depende del tamaño del lote, no del total de filas.
 */
public final class ExportacionJson {

    /** Filas por consulta al exportar en flujo: acota la memoria de la JVM. */
    public static final int TAMANO_LOTE = 1_000;

    @FunctionalInterface
    public interface Elementos {
        /** Escribe los elementos de {@code datos}; cada llamada emite un objeto del array. */
        void escribir(JsonGenerator gen);
    }

    public static void escribir(ObjectMapper json, OutputStream salida, String mensaje, Elementos datos) {
        try (JsonGenerator gen = json.createGenerator(salida)) {
            gen.writeStartObject();
            gen.writeBooleanProperty("exito", true);
            gen.writeStringProperty("mensaje", mensaje);
            gen.writeArrayPropertyStart("datos");
            datos.escribir(gen);
            gen.writeEndArray();
            gen.writeName("timestamp");
            // Mismo serializador que usa el conversor HTTP para no romper el formato.
            gen.writeRawValue(json.writeValueAsString(Instant.now()));
            gen.writeEndObject();
        }
    }

    /**
     * Lector de lotes por cursor (keyset): devuelve hasta {@code tamano} filas en
     * un orden estable, todas posteriores a {@code ultima} en ese orden
     * ({@code null} = primer lote).
     */
    @FunctionalInterface
    public interface LectorLotes<E> {
        List<E> leer(E ultima, int tamano);
    }

    /**
     * Export completo de un filtro como cuerpo en flujo: lee lotes de
     * {@value #TAMANO_LOTE} filas con {@code lector} y escribe cada fila ya
     * convertida, sin cargar el total en memoria.
     *
     * Los lotes se piden por cursor (la ultima fila escrita) y no por OFFSET:
     * cada lote cuesta lo mismo aunque el export tenga cientos de miles de
     * filas, no hay COUNT por lote y una insercion concurrente no duplica ni
     * salta filas ya exportadas.
     *
     * El {@code conversor} se obtiene al empezar a escribir (p. ej. con los
     * nombres de catalogo vigentes en ese momento). Si el export falla a mitad
     * (cliente que corta, BD) la respuesta ya esta en curso y no puede
     * convertirse en ApiError, asi que solo se registra en {@code log}.
     */
    public static <E> StreamingResponseBody enLotes(ObjectMapper json, String mensaje,
            LectorLotes<E> lector, Supplier<Function<E, ?>> conversor, Logger log, String descripcion) {
        return salida -> {
            try {
                escribir(json, salida, mensaje, gen -> {
                    Function<E, ?> convertir = conversor.get();
                    E ultima = null;
                    while (true) {
                        List<E> lote = lector.leer(ultima, TAMANO_LOTE);
                        for (E fila : lote) {
                            json.writeValue(gen, convertir.apply(fila));
                        }
                        if (lote.size() < TAMANO_LOTE) {
                            break;
                        }
                        ultima = lote.get(lote.size() - 1);
                    }
                });
            } catch (Exception e) {
                log.error("Exportación de {} interrumpida: {}", descripcion, e.getMessage(), e);
            }
        };
    }

    private ExportacionJson() {
    }
}
