package oracle.simulacion.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** El JSON que envía la pantalla se lee en los records (con y sin los campos nuevos). */
class PeticionesSinChoquesJsonTest {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void guardarConLoQueLaPantallaTeniaCargado() {
    ConfiguracionActualizarRequest req =
        json.readValue(
            """
            {"velocidadBase":100,"operariosPorChute":null,
             "clusters":[{"clusterId":1,"limiteHoras":5}],
             "anterior":{"velocidadBase":100,"operariosPorChute":null,"clusters":[{"clusterId":1,"limiteHoras":2}]}}
            """,
            ConfiguracionActualizarRequest.class);
    assertEquals(0, new BigDecimal("2").compareTo(req.anterior().clusters().getFirst().limiteHoras()));
    assertEquals(0, new BigDecimal("100").compareTo(req.anterior().velocidadBase()));

    ConfiguracionActualizarRequest viejo =
        json.readValue("{\"velocidadBase\":100,\"clusters\":[]}", ConfiguracionActualizarRequest.class);
    assertNull(viejo.anterior());
  }

  @Test
  void moverLocalesConSusCodigos() {
    DataRequest.MoverLocales req =
        json.readValue(
            "{\"origenId\":6,\"destinoId\":5,\"ordenes\":[2,3],\"cods\":[\"F112\",null]}", DataRequest.MoverLocales.class);
    assertEquals(List.of(2, 3), req.ordenes());
    assertEquals("F112", req.cods().get(0));
    assertNull(req.cods().get(1));

    assertNull(
        json.readValue("{\"origenId\":6,\"destinoId\":5,\"ordenes\":[2]}", DataRequest.MoverLocales.class).cods());
  }
}
