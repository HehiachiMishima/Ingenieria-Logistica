package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import oracle.common.config.Transacciones;
import oracle.common.exception.ConflictoException;
import oracle.common.response.ApiResponse;
import oracle.simulacion.model.ArchivoExcel;
import oracle.simulacion.repository.ArchivoExcelRepository;
import oracle.simulacion.repository.SimulacionLotesRepository;
import oracle.simulacion.response.ArchivoSimulacionResponse;
import oracle.simulacion.service.SimulacionExcel.ArchivoInvalidoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

/** Idempotencia de la carga (huella SHA-256 en memoria) y topes de concurrencia, sin BD. */
class SimulacionRegistroServiceTest {

  private static final String CSV = "RUTA,CLUSTER,CHUTE,TIPO,BULTOS\n3030,CL1,CHUTE 1,B2C,5\n";

  private ArchivoExcelRepository archivoRepo;
  private SimulacionLotesRepository lotes;
  private RelojMovil reloj;
  private SimulacionRegistroService servicio;
  private final Map<Long, ArchivoExcel> guardados = new HashMap<>();
  private final AtomicLong secuencia = new AtomicLong(100);

  @BeforeEach
  @SuppressWarnings("unchecked")
  void preparar() {
    archivoRepo = mock(ArchivoExcelRepository.class);
    lotes = mock(SimulacionLotesRepository.class);
    Transacciones transacciones = mock(Transacciones.class);
    when(transacciones.ejecutar(any())).thenAnswer((i) -> ((Supplier<Object>) i.getArgument(0)).get());
    when(transacciones.consultar(any())).thenAnswer((i) -> ((Supplier<Object>) i.getArgument(0)).get());
    when(archivoRepo.saveAndFlush(any(ArchivoExcel.class)))
        .thenAnswer(
            (i) -> {
              ArchivoExcel entidad = i.getArgument(0);
              entidad.setId(secuencia.incrementAndGet());
              guardados.put(entidad.getId(), entidad);
              return entidad;
            });
    when(archivoRepo.findById(anyLong()))
        .thenAnswer((i) -> Optional.ofNullable(guardados.get(i.<Long>getArgument(0))));
    when(lotes.asegurarClusters(anyList())).thenReturn(Map.of("CL1", 1L));
    when(lotes.asegurarChutes(anyList())).thenReturn(Map.of("CHUTE 1", 2L));
    reloj = new RelojMovil();
    servicio = new SimulacionRegistroService(archivoRepo, lotes, transacciones, reloj);
  }

  @Test
  void reintentoConElMismoArchivoDevuelveElRegistroExistente() {
    ApiResponse<ArchivoSimulacionResponse> primero = subir(csv(CSV), "Semana 23");
    ApiResponse<ArchivoSimulacionResponse> segundo = subir(csv(CSV), "Semana 23");

    assertEquals(SimulacionRegistroService.MENSAJE_CREADO, primero.mensaje());
    assertEquals(SimulacionRegistroService.MENSAJE_REPETIDO, segundo.mensaje());
    assertEquals(primero.datos().id(), segundo.datos().id());
    verify(archivoRepo, times(1)).saveAndFlush(any());
    verify(lotes, times(1)).insertarRutas(anyLong(), anyList(), anyMap(), anyMap());
  }

  @Test
  void otroContenidoOtroNombreUOtroUsuarioNoSonDuplicados() {
    Long a = subir(csv(CSV), "Semana 23").datos().id();
    Long b = subir(csv(CSV.replace(",5\n", ",6\n")), "Semana 23").datos().id();
    Long c = subir(csv(CSV), "Semana 24").datos().id();

    assertNotEquals(a, b);
    assertNotEquals(a, c);
    assertNotEquals(
        SimulacionRegistroService.huella(csv(CSV), 1L, "X", null, "#1d63ed"),
        SimulacionRegistroService.huella(csv(CSV), 2L, "X", null, "#1d63ed"));
    assertEquals(
        SimulacionRegistroService.huella(csv(CSV), 1L, " X ", null, "#1d63ed"),
        SimulacionRegistroService.huella(csv(CSV), 1L, "X", "", "#1d63ed"));
  }

  @Test
  void pasadaLaVentanaSeCreaUnRegistroNuevo() {
    Long primero = subir(csv(CSV), "Semana 23").datos().id();
    reloj.avanzar(SimulacionRegistroService.VENTANA_DUPLICADOS.plusSeconds(1));

    ApiResponse<ArchivoSimulacionResponse> segundo = subir(csv(CSV), "Semana 23");

    assertEquals(SimulacionRegistroService.MENSAJE_CREADO, segundo.mensaje());
    assertNotEquals(primero, segundo.datos().id());
  }

  @Test
  void siElRegistroSeBorroSeVuelveAProcesar() {
    Long primero = subir(csv(CSV), "Semana 23").datos().id();
    guardados.remove(primero);

    ApiResponse<ArchivoSimulacionResponse> segundo = subir(csv(CSV), "Semana 23");

    assertEquals(SimulacionRegistroService.MENSAJE_CREADO, segundo.mensaje());
    assertNotEquals(primero, segundo.datos().id());
  }

  @Test
  void unaCargaFallidaNoBloqueaElReintento() {
    MockMultipartFile invalido = csv("RUTA,CLUSTER,CHUTE\n3030,CL1,CHUTE 1\n");

    ArchivoInvalidoException primero =
        assertThrows(ArchivoInvalidoException.class, () -> subir(invalido, null));
    ArchivoInvalidoException segundo =
        assertThrows(ArchivoInvalidoException.class, () -> subir(invalido, null));

    assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, primero.estado());
    assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, segundo.estado());
  }

  @Test
  void laMismaCargaEnCursoResponde409() throws Exception {
    CountDownLatch dentro = new CountDownLatch(1);
    CountDownLatch soltar = new CountDownLatch(1);
    doAnswer(
            (i) -> {
              dentro.countDown();
              soltar.await(10, TimeUnit.SECONDS);
              return null;
            })
        .when(lotes)
        .insertarRutas(anyLong(), anyList(), anyMap(), anyMap());

    CompletableFuture<ApiResponse<ArchivoSimulacionResponse>> primera =
        CompletableFuture.supplyAsync(() -> subir(csv(CSV), "Semana 23"));
    assertTrue(dentro.await(10, TimeUnit.SECONDS));

    ConflictoException error = assertThrows(ConflictoException.class, () -> subir(csv(CSV), "Semana 23"));
    assertFalse(error instanceof ArchivoInvalidoException);
    assertTrue(error.getMessage().contains("ya se está registrando"), error.getMessage());

    soltar.countDown();
    assertEquals(SimulacionRegistroService.MENSAJE_CREADO, primera.get(10, TimeUnit.SECONDS).mensaje());
    assertEquals(SimulacionRegistroService.MENSAJE_REPETIDO, subir(csv(CSV), "Semana 23").mensaje());
  }

  @Test
  void archivoVacioOSinNombreEs400() {
    ArchivoInvalidoException vacio =
        assertThrows(
            ArchivoInvalidoException.class,
            () -> subir(new MockMultipartFile("archivo", "p.csv", "text/csv", new byte[0]), null));
    ArchivoInvalidoException sinNombre =
        assertThrows(
            ArchivoInvalidoException.class,
            () -> subir(new MockMultipartFile("archivo", "", "text/csv", CSV.getBytes()), null));

    assertEquals(HttpStatus.BAD_REQUEST, vacio.estado());
    assertEquals(HttpStatus.BAD_REQUEST, sinNombre.estado());
  }

  @Test
  void costoDeMemoriaSegunFormatoYTamano() {
    assertEquals(12, SimulacionRegistroService.costoMemoriaMb(archivo("a.csv", 1)));
    assertEquals(28, SimulacionRegistroService.costoMemoriaMb(archivo("a.xlsx", 1)));
    assertEquals(64, SimulacionRegistroService.costoMemoriaMb(archivo("a.xlsx", 4)));
    assertEquals(
        SimulacionRegistroService.PRESUPUESTO_MEMORIA_MB,
        SimulacionRegistroService.costoMemoriaMb(archivo("a.xls", 20)));
  }

  @Test
  void elRegistroRecuerdaCargasAcotadas() {
    SimulacionRegistroService.CargasRecientes recientes =
        new SimulacionRegistroService.CargasRecientes(reloj, Duration.ofMinutes(10));
    for (int i = 0; i < 1_500; i++) {
      recientes.reservar("h" + i);
      recientes.completar("h" + i, (long) i);
    }
    assertTrue(recientes.tamano() <= 1_000, "tamaño " + recientes.tamano());
  }

  // ------------------------------------------------------------------

  private ApiResponse<ArchivoSimulacionResponse> subir(MockMultipartFile archivo, String nombre) {
    return servicio.subir(archivo, nombre, null, null).block(Duration.ofSeconds(20));
  }

  private static MockMultipartFile csv(String contenido) {
    return new MockMultipartFile(
        "archivo", "proyeccion.csv", "text/csv", contenido.getBytes(StandardCharsets.UTF_8));
  }

  private static MockMultipartFile archivo(String nombre, int megas) {
    MockMultipartFile archivo = mock(MockMultipartFile.class);
    when(archivo.getOriginalFilename()).thenReturn(nombre);
    when(archivo.getSize()).thenReturn((long) megas * 1024 * 1024);
    return archivo;
  }

  /** Reloj controlable para probar la ventana de duplicados. */
  private static final class RelojMovil extends Clock {
    private Instant ahora = Instant.parse("2026-10-29T12:00:00Z");

    void avanzar(Duration duracion) {
      ahora = ahora.plus(duracion);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zona) {
      return this;
    }

    @Override
    public Instant instant() {
      return ahora;
    }
  }
}
