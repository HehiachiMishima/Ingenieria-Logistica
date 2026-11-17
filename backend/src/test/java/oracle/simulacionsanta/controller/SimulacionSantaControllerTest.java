package oracle.simulacionsanta.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import oracle.common.exception.ApiError;
import oracle.common.response.ApiResponse;
import oracle.simulacionsanta.response.RegistroResponse;
import oracle.simulacionsanta.service.ExcelSantaAnita;
import oracle.simulacionsanta.service.ExcelSantaAnita.ArchivoInvalidoException;
import oracle.simulacionsanta.service.SimulacionSantaConfiguracionService;
import oracle.simulacionsanta.service.SimulacionSantaRegistroService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

/** Contrato HTTP de la carga: 200 con ApiResponse, o el 4xx del archivo con el cuerpo ApiError. */
class SimulacionSantaControllerTest {

  private final SimulacionSantaRegistroService registro = mock(SimulacionSantaRegistroService.class);
  private final SimulacionSantaController controller =
      new SimulacionSantaController(registro, mock(SimulacionSantaConfiguracionService.class));
  private final MockHttpServletRequest peticion =
      new MockHttpServletRequest("POST", "/api/simulacion-santa/registros");
  private final MockMultipartFile archivo =
      new MockMultipartFile("archivo", "d.csv", "text/csv", "x".getBytes(StandardCharsets.UTF_8));

  @Test
  void exitoDevuelveElApiResponseCon200() {
    ApiResponse<RegistroResponse> cuerpo = ApiResponse.ok("Registro creado.", null);
    when(registro.subir(any(), any())).thenReturn(cuerpo);

    ResponseEntity<?> respuesta = controller.subir(archivo, "Despacho", peticion);

    assertEquals(HttpStatus.OK, respuesta.getStatusCode());
    assertSame(cuerpo, respuesta.getBody());
  }

  @Test
  void archivoInvalidoUsaSuEstado() {
    when(registro.subir(any(), any()))
        .thenThrow(ArchivoInvalidoException.noSoportado("Formato no soportado."));

    ResponseEntity<?> respuesta = controller.subir(archivo, "Despacho", peticion);

    assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, respuesta.getStatusCode());
    ApiError error = assertInstanceOf(ApiError.class, respuesta.getBody());
    assertEquals(415, error.status());
    assertEquals("Formato no soportado.", error.mensaje());
    assertEquals("/api/simulacion-santa/registros", error.ruta());
    assertNull(error.campos());
  }

  @Test
  void sinFilasValidasResponde422ConLosErroresPorFila() {
    MockMultipartFile malo =
        new MockMultipartFile(
            "archivo",
            "d.csv",
            "text/csv",
            "RUTA,LOCAL,CLUSTER,ENVIO1,ENVIO2,ENVIO3\nA,L,7,1,0,0\n".getBytes(StandardCharsets.UTF_8));
    when(registro.validar(any())).thenAnswer((i) -> ExcelSantaAnita.leer(malo));

    ResponseEntity<?> respuesta = controller.validar(malo, peticion);

    assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, respuesta.getStatusCode());
    ApiError error = assertInstanceOf(ApiError.class, respuesta.getBody());
    assertEquals(422, error.status());
    assertEquals(1, error.campos().size());
    assertEquals(
        "El CLUSTER «7» no es válido: usa 1, 2 o 3 (también C1, CL1 o CLUSTER 1).",
        error.campos().get("fila 2"));
  }
}
