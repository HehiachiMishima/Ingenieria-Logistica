package oracle.common.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import oracle.common.response.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * El export en flujo debe producir byte a byte el mismo JSON que el envoltorio
 * ApiResponse normal, o el frontend rompería al descargar.
 */
class ExportacionJsonTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("el export en flujo serializa el mismo envoltorio que ApiResponse<List<T>>")
    void igualQueElConversorNormal() {
        List<Ejemplo> filas = List.of(new Ejemplo(1L, "Centro 1"), new Ejemplo(2L, "Centro 2"));
        String real = exportar("OK", filas);
        String esperado = json.writeValueAsString(ApiResponse.ok("OK", filas));

        assertThat(sinTimestamp(real)).isEqualTo(sinTimestamp(esperado));
        assertThat(real).contains("\"exito\":true").contains("\"datos\":").contains("\"timestamp\":");
    }

    @Test
    @DisplayName("con cero filas el JSON sigue siendo un ApiResponse con datos vacíos")
    void listaVaciaIgualQueElConversorNormal() {
        String real = exportar("Incidencias exportadas.", List.of());
        String esperado = json.writeValueAsString(ApiResponse.ok("Incidencias exportadas.", List.of()));

        assertThat(sinTimestamp(real)).isEqualTo(sinTimestamp(esperado));
        assertThat(real).contains("\"datos\":[]");
    }

    @Test
    @DisplayName("el export por lotes pide cada lote desde la ultima fila (cursor) y escribe todas")
    void enLotesRecorrePorCursor() throws Exception {
        int total = ExportacionJson.TAMANO_LOTE * 2 + 5;
        List<Ejemplo> filas = new ArrayList<>();
        for (long i = 1; i <= total; i++) {
            filas.add(new Ejemplo(i, "Fila " + i));
        }
        List<Long> cursores = new ArrayList<>();
        ExportacionJson.LectorLotes<Ejemplo> lector = (ultima, tamano) -> {
            cursores.add(ultima == null ? null : ultima.id());
            int desde = ultima == null ? 0 : ultima.id().intValue();
            return filas.subList(desde, Math.min(desde + tamano, filas.size()));
        };

        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ExportacionJson.enLotes(json, "OK", lector, () -> fila -> fila,
                LoggerFactory.getLogger(ExportacionJsonTest.class), "prueba").writeTo(salida);
        String real = salida.toString(StandardCharsets.UTF_8);
        String esperado = json.writeValueAsString(ApiResponse.ok("OK", filas));

        assertThat(sinTimestamp(real)).isEqualTo(sinTimestamp(esperado));
        assertThat(cursores).containsExactly(null, (long) ExportacionJson.TAMANO_LOTE,
                (long) ExportacionJson.TAMANO_LOTE * 2);
    }

    private String exportar(String mensaje, List<Ejemplo> filas) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ExportacionJson.escribir(json, salida, mensaje, gen -> {
            for (Ejemplo fila : filas) {
                json.writeValue(gen, fila);
            }
        });
        return salida.toString(StandardCharsets.UTF_8);
    }

    /** El timestamp se genera en el momento; se neutraliza para comparar el resto. */
    private static String sinTimestamp(String json) {
        return json.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"X\"");
    }

    private record Ejemplo(Long id, String nombre) {
    }
}
