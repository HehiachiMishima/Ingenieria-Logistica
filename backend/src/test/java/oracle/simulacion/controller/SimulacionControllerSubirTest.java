package oracle.simulacion.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import oracle.common.exception.ApiError;
import oracle.common.exception.LimiteExcedidoException;
import oracle.common.response.ApiResponse;
import oracle.simulacion.response.ArchivoSimulacionResponse;
import oracle.simulacion.service.SimulacionConfiguracionService;
import oracle.simulacion.service.SimulacionExcel.ArchivoInvalidoException;
import oracle.simulacion.service.SimulacionRegistroService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.request.async.DeferredResult;
import reactor.core.publisher.Mono;

/** Contrato HTTP del endpoint de carga: mismo cuerpo, 4xx correcto para archivos inválidos. */
class SimulacionControllerSubirTest {

  private final SimulacionRegistroService registro = mock(SimulacionRegistroService.class);
  private final SimulacionController controller =
      new SimulacionController(registro, mock(SimulacionConfiguracionService.class));
  private final MockHttpServletRequest peticion =
      new MockHttpServletRequest("POST", "/api/simulacion/registros");
  private final MockMultipartFile archivo =
      new MockMultipartFile("archivo", "p.csv", "text/csv", "x".getBytes());

  @Test
  void exitoDevuelveElMismoApiResponseCon200() {
    ApiResponse<ArchivoSimulacionResponse> cuerpo = ApiResponse.ok("Registro creado.", null);
    when(registro.subir(any(), any(), any(), any())).thenReturn(Mono.just(cuerpo));

    ResponseEntity<?> respuesta = resultado(controller.subir(archivo, null, null, null, peticion));

    assertEquals(HttpStatus.OK, respuesta.getStatusCode());
    assertSame(cuerpo, respuesta.getBody());
  }

  @Test
  void archivoInvalidoUsaSuEstadoYElCuerpoApiError() {
    when(registro.subir(any(), any(), any(), any()))
        .thenReturn(Mono.error(ArchivoInvalidoException.demasiadoGrande("El archivo supera el máximo de 100000 filas.")));

    ResponseEntity<?> respuesta = resultado(controller.subir(archivo, null, null, null, peticion));

    assertEquals(HttpStatus.CONTENT_TOO_LARGE, respuesta.getStatusCode());
    ApiError error = assertInstanceOf(ApiError.class, respuesta.getBody());
    assertEquals(413, error.status());
    assertEquals("El archivo supera el máximo de 100000 filas.", error.mensaje());
    assertEquals("/api/simulacion/registros", error.ruta());
  }

  @Test
  void otrosErroresSiguenAlManejadorGlobal() {
    LimiteExcedidoException ocupado = new LimiteExcedidoException("Hay varias cargas en proceso.");
    when(registro.subir(any(), any(), any(), any())).thenReturn(Mono.error(ocupado));

    DeferredResult<ResponseEntity<?>> diferido = controller.subir(archivo, null, null, null, peticion);

    assertSame(ocupado, diferido.getResult());
  }

  private static ResponseEntity<?> resultado(DeferredResult<ResponseEntity<?>> diferido) {
    return assertInstanceOf(ResponseEntity.class, diferido.getResult());
  }
}
