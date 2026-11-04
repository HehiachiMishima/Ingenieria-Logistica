package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import oracle.common.config.CacheRespuestas;
import oracle.common.config.Transacciones;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.PaginaResponse;
import oracle.simulacion.model.ArchivoExcel;
import oracle.simulacion.model.Chute;
import oracle.simulacion.model.Cluster;
import oracle.simulacion.model.HistorialBalanceo;
import oracle.simulacion.model.LanzamientoBalanceo;
import oracle.simulacion.model.OperarioOla;
import oracle.simulacion.model.OperarioOlaId;
import oracle.simulacion.model.ParametroChute;
import oracle.simulacion.model.ParametroChuteId;
import oracle.simulacion.model.ParametroCluster;
import oracle.simulacion.model.ParametroClusterId;
import oracle.simulacion.model.RutaProyeccion;
import oracle.simulacion.model.SimulacionConfig;
import oracle.simulacion.model.TipoRuta;
import oracle.simulacion.repository.ArchivoExcelRepository;
import oracle.simulacion.repository.CargaRutas;
import oracle.simulacion.repository.ChuteRepository;
import oracle.simulacion.repository.ClusterRepository;
import oracle.simulacion.repository.HistorialBalanceoRepository;
import oracle.simulacion.repository.LanzamientoBalanceoRepository;
import oracle.simulacion.repository.MovimientoRutas;
import oracle.simulacion.repository.OperarioOlaRepository;
import oracle.simulacion.repository.ParametroChuteRepository;
import oracle.simulacion.repository.ParametroClusterRepository;
import oracle.simulacion.repository.RutaProyeccionRepository;
import oracle.simulacion.repository.RutasMovidasCluster;
import oracle.simulacion.repository.SimulacionConfigRepository;
import oracle.simulacion.repository.SimulacionLotesRepository;
import oracle.simulacion.request.ChuteOperariosRequest;
import oracle.simulacion.request.ClusterLimiteRequest;
import oracle.simulacion.request.ConfiguracionActualizarRequest;
import oracle.simulacion.request.FaseBalanceo;
import oracle.simulacion.request.LadoChute;
import oracle.simulacion.request.MoverRutaRequest;
import oracle.simulacion.response.BalanceoResponse;
import oracle.simulacion.response.ChuteComparativoResponse;
import oracle.simulacion.response.DashboardSimulacionResponse;
import oracle.simulacion.response.FlujoOperariosResponse;
import oracle.simulacion.response.HistorialRegistroResponse;
import oracle.simulacion.response.HistorialSimulacionResponse;
import oracle.simulacion.response.OlaSimulacionResponse;
import oracle.simulacion.response.RutasDashboardResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class SimulacionBalanceoTest {

  private static final Long CL1 = 1L;
  private static final Long CL2 = 2L;
  private static final Long CL3 = 3L;
  private static final Long CL4 = 4L;

  @Mock private ArchivoExcelRepository archivoRepo;
  @Mock private SimulacionConfigRepository configRepo;
  @Mock private ClusterRepository clusterRepo;
  @Mock private ChuteRepository chuteRepo;
  @Mock private ParametroClusterRepository parametroClusterRepo;
  @Mock private ParametroChuteRepository parametroChuteRepo;
  @Mock private RutaProyeccionRepository rutaRepo;
  @Mock private HistorialBalanceoRepository historialRepo;
  @Mock private OperarioOlaRepository operarioOlaRepo;
  @Mock private LanzamientoBalanceoRepository lanzamientoRepo;
  @Mock private SimulacionLotesRepository lotes;
  @Mock private Transacciones transacciones;

  @Captor private ArgumentCaptor<List<OperarioOla>> olasGuardadas;
  @Captor private ArgumentCaptor<List<OperarioOla>> olasBorradas;
  @Captor private ArgumentCaptor<List<HistorialBalanceo>> logs;
  @Captor private ArgumentCaptor<LanzamientoBalanceo> lanzamiento;

  private SimulacionConfiguracionService servicio;

  @BeforeEach
  void preparar() {
    servicio =
        new SimulacionConfiguracionService(
            archivoRepo,
            configRepo,
            clusterRepo,
            chuteRepo,
            parametroClusterRepo,
            parametroChuteRepo,
            rutaRepo,
            historialRepo,
            operarioOlaRepo,
            lanzamientoRepo,
            lotes,
            transacciones,
            new CacheRespuestas(false, 1, 1));
    lenient()
        .when(transacciones.ejecutar(any()))
        .thenAnswer(invocacion -> ((Supplier<?>) invocacion.getArgument(0)).get());
    lenient()
        .when(transacciones.consultar(any()))
        .thenAnswer(invocacion -> ((Supplier<?>) invocacion.getArgument(0)).get());
    lenient()
        .when(lanzamientoRepo.save(any()))
        .thenAnswer(
            invocacion -> {
              LanzamientoBalanceo guardado = invocacion.getArgument(0);
              guardado.setId(77L);
              return guardado;
            });
  }

  // ---------------- Paso 1: rutas de un cluster ----------------

  @Test
  void rutasSeparaMoviendoAlClusterConMenosRutasSiAunNoEmpezo() {
    ArchivoExcel archivo = archivo();
    ParametroCluster cl1 = limite(CL1, "100");
    ParametroCluster cl2 = limite(CL2, "100");
    prepararRegistro(
        archivo,
        configuracion("100", null),
        List.of(cl1, cl2),
        List.of(operarios(1L, 0), operarios(2L, 0)),
        List.of(),
        List.of(cluster(CL1, "CL1"), cluster(CL2, "CL2")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    RutaProyeccion cl1A = ruta(1L, CL1, 1L, "3030", "100");
    RutaProyeccion cl1B = ruta(2L, CL1, 1L, "3031", "100");
    RutaProyeccion cl2Ruta = ruta(3L, CL2, 1L, "3032", "50");
    when(rutaRepo.findByArchivoIdOrderByIdAsc(10L)).thenReturn(List.of(cl1A, cl1B, cl2Ruta));

    BalanceoResponse resultado = lanzar(CL1, FaseBalanceo.RUTAS);

    // CL2 tiene menos rutas en CHUTE 1 y aún no empezó: sale él, no las 2 de CL1.
    assertEquals(2L, cl2Ruta.getChuteId());
    assertEquals(1L, cl1A.getChuteId());
    assertEquals(1L, cl1B.getChuteId());
    assertTrue(resultado.mensaje().contains("1 para separar CL1 y CL2"), resultado.mensaje());
    assertEquals(ParametroCluster.ESTADO_RUTAS, cl1.getEstado());
    assertEquals(ParametroCluster.ESTADO_PENDIENTE, cl2.getEstado());
    assertEquals(ArchivoExcel.ESTADO_EN_PROCESO, archivo.getEstado());
    // El lanzamiento queda registrado y el historial apunta a él.
    verify(lanzamientoRepo).save(lanzamiento.capture());
    assertEquals(CL1, lanzamiento.getValue().getClusterId());
    assertEquals("RUTAS", lanzamiento.getValue().getFase());
    verify(lotes).insertarHistorial(logs.capture());
    assertEquals(77L, logs.getValue().get(0).getLanzamientoId());
    assertEquals(HistorialBalanceo.MOTIVO_SEPARACION, logs.getValue().get(0).getMotivo());
    assertEquals(2L, logs.getValue().get(0).getChuteDestinoId());
    // El lanzamiento guarda sus totales y la ruta movida se guarda en lote.
    assertEquals(1, lanzamiento.getValue().getRutas());
    assertEquals(1, lanzamiento.getValue().getSeparaciones());
    verify(lotes).moverRutas(Map.of(3L, 2L));
    // El paso devuelve la configuración ya actualizada.
    assertEquals(2, resultado.configuracion().clusters().size());
  }

  @Test
  void unClusterGrandeLlegaALaMetaSinCortarse() {
    // 3000 rutas de CL3: 2900 en CHUTE 1 y 100 en CHUTE 2. Emparejarlas exige unas 1400
    // movidas, más que el tope fijo de antes (1000): ahora llega a ≤ 10 %.
    ParametroCluster cl3 = limite(CL3, "100");
    prepararRegistro(
        archivo(),
        configuracion("100", null),
        List.of(cl3),
        List.of(operarios(1L, 0), operarios(2L, 0)),
        List.of(),
        List.of(cluster(CL3, "CL3")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    List<RutaProyeccion> rutas = new ArrayList<>();
    for (long id = 1; id <= 3000; id++) {
      rutas.add(ruta(id, CL3, id <= 2900 ? 1L : 2L, "R" + id, String.valueOf(5 + id % 7)));
    }
    when(rutaRepo.findByArchivoIdOrderByIdAsc(10L)).thenReturn(rutas);

    BalanceoResponse resultado = lanzar(CL3, FaseBalanceo.RUTAS);

    Map<Long, Integer> bultos = new HashMap<>();
    rutas.forEach(
        (r) -> bultos.merge(r.getChuteId(), r.getTotalBultos().intValue(), Integer::sum));
    int diferencia = Math.abs(bultos.get(1L) - bultos.get(2L));
    double promedio = (bultos.get(1L) + bultos.get(2L)) / 2.0;
    assertTrue(diferencia <= promedio * 0.10, bultos + " · " + resultado.mensaje());
    assertFalse(resultado.mensaje().contains("máximo de movimientos"), resultado.mensaje());
    verify(lotes).moverRutas(anyMap());
  }

  @Test
  void rutasNoMuevenAUnClusterCerrado() {
    // CL1 ya cerró y comparte CHUTE 1 con CL2: aunque CL1 tenga menos rutas allí, sale CL2.
    prepararRegistro(
        archivo(),
        configuracion("100", null),
        List.of(limite(CL1, "100", ParametroCluster.ESTADO_CERRADO), limite(CL2, "100")),
        List.of(operarios(1L, 0), operarios(2L, 0)),
        List.of(),
        List.of(cluster(CL1, "CL1"), cluster(CL2, "CL2")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    RutaProyeccion cl1 = ruta(1L, CL1, 1L, "3030", "100");
    RutaProyeccion cl2A = ruta(2L, CL2, 1L, "3031", "50");
    RutaProyeccion cl2B = ruta(3L, CL2, 1L, "3032", "50");
    when(rutaRepo.findByArchivoIdOrderByIdAsc(10L)).thenReturn(List.of(cl1, cl2A, cl2B));

    lanzar(CL2, FaseBalanceo.RUTAS);

    assertEquals(1L, cl1.getChuteId());
    assertEquals(2L, cl2A.getChuteId());
    assertEquals(2L, cl2B.getChuteId());
  }

  @Test
  void rutasAvisaCuandoNoHayChuteParaSepararCl1YCl2() {
    prepararRegistro(
        archivo(),
        configuracion("100", null),
        List.of(limite(CL1, "100"), limite(CL2, "100")),
        List.of(operarios(1L, 0)),
        List.of(),
        List.of(cluster(CL1, "CL1"), cluster(CL2, "CL2")),
        List.of(chute(1L, "CHUTE 1")));
    RutaProyeccion cl1Ruta = ruta(1L, CL1, 1L, "3030", "100");
    when(rutaRepo.findByArchivoIdOrderByIdAsc(10L))
        .thenReturn(List.of(cl1Ruta, ruta(2L, CL2, 1L, "3031", "50")));

    BalanceoResponse resultado = lanzar(CL1, FaseBalanceo.RUTAS);

    assertEquals(0, resultado.rutasMovidas());
    assertEquals(1L, cl1Ruta.getChuteId());
    assertTrue(resultado.mensaje().contains("No se pudo separar CL1 y CL2 en CHUTE 1"));
  }

  @Test
  void rutasIgualaLaCargaDeLosChutesDeSuOla() {
    // CL3: CHUTE 1 con 400 + 300 + 200 + 100 = 1.000 bultos y CHUTE 2 con 100. Llevar la
    // de 400 deja 600 y 500: la diferencia (100) ya no se reduce con ninguna otra ruta.
    prepararRegistro(
        archivo(),
        configuracion("100", null),
        List.of(limite(CL3, "100")),
        List.of(operarios(1L, 0), operarios(2L, 0)),
        List.of(),
        List.of(cluster(CL3, "CL3")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    RutaProyeccion r400 = ruta(1L, CL3, 1L, "3030", "400");
    RutaProyeccion r300 = ruta(2L, CL3, 1L, "3031", "300");
    when(rutaRepo.findByArchivoIdOrderByIdAsc(10L))
        .thenReturn(
            List.of(
                r400,
                r300,
                ruta(3L, CL3, 1L, "3032", "200"),
                ruta(4L, CL3, 1L, "3033", "100"),
                ruta(5L, CL3, 2L, "3034", "100")));

    BalanceoResponse resultado = lanzar(CL3, FaseBalanceo.RUTAS);

    assertEquals(2L, r400.getChuteId());
    assertEquals(1L, r300.getChuteId());
    assertEquals(1, resultado.rutasMovidas());
    assertTrue(
        resultado.mensaje().contains("Diferencia de carga entre sus chutes: 164 % → 18 %"),
        resultado.mensaje());
  }

  @Test
  void rutasNoTocanLaOlaDeOtroCluster() {
    // CL3 y CL4 están desparejos; al lanzar CL3 solo se reparte su ola.
    prepararRegistro(
        archivo(),
        configuracion("100", null),
        List.of(limite(CL3, "100"), limite(CL4, "100")),
        List.of(operarios(1L, 0), operarios(2L, 0)),
        List.of(),
        List.of(cluster(CL3, "CL3"), cluster(CL4, "CL4")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    RutaProyeccion cl3 = ruta(2L, CL3, 1L, "3031", "200");
    RutaProyeccion cl4A = ruta(4L, CL4, 1L, "4040", "400");
    RutaProyeccion cl4B = ruta(5L, CL4, 1L, "4041", "200");
    when(rutaRepo.findByArchivoIdOrderByIdAsc(10L))
        .thenReturn(
            List.of(
                ruta(1L, CL3, 1L, "3030", "400"),
                cl3,
                ruta(3L, CL3, 2L, "3032", "50"),
                cl4A,
                cl4B,
                ruta(6L, CL4, 2L, "4042", "50")));

    BalanceoResponse resultado = lanzar(CL3, FaseBalanceo.RUTAS);

    assertEquals(1, resultado.rutasMovidas());
    assertEquals(2L, cl3.getChuteId());
    assertEquals(1L, cl4A.getChuteId());
    assertEquals(1L, cl4B.getChuteId());
  }

  @Test
  void rutasSoloSiElClusterEstaPendiente() {
    prepararRegistro(
        archivo(),
        configuracion("100", null),
        List.of(limite(CL1, "2", ParametroCluster.ESTADO_RUTAS)),
        List.of(operarios(1L, 0)),
        List.of(),
        List.of(cluster(CL1, "CL1")),
        List.of(chute(1L, "CHUTE 1")));

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> lanzar(CL1, FaseBalanceo.RUTAS));
    assertTrue(error.getMessage().contains("restablece ese cluster"), error.getMessage());
  }

  @Test
  void lanzarRechazaUnClusterQueNoEsDelRegistro() {
    prepararRegistro(
        archivo(),
        configuracion("100", null),
        List.of(limite(CL1, "2")),
        List.of(operarios(1L, 0)),
        List.of(),
        List.of(cluster(CL1, "CL1")),
        List.of(chute(1L, "CHUTE 1")));

    assertThrows(SolicitudInvalidaException.class, () -> lanzar(99L, FaseBalanceo.RUTAS));
  }

  @Test
  void unClusterCerradoNoAdmiteCambios() {
    prepararRegistro(
        archivo(),
        configuracion("100", null),
        List.of(
            limite(CL1, "2", ParametroCluster.ESTADO_CERRADO),
            limite(CL3, "2", ParametroCluster.ESTADO_RUTAS)),
        List.of(operarios(1L, 0)),
        List.of(),
        List.of(cluster(CL1, "CL1"), cluster(CL3, "CL3")),
        List.of(chute(1L, "CHUTE 1")));

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> lanzar(CL1, FaseBalanceo.OPERARIOS));
    assertTrue(error.getMessage().contains("CL1 está cerrado: reábrelo"), error.getMessage());
  }

  @Test
  void elSiguienteClusterEsperaQueSeCierreElAnterior() {
    prepararRegistro(
        archivo(),
        configuracion("100", null),
        List.of(
            limite(CL1, "2", ParametroCluster.ESTADO_LISTO),
            limite(CL3, "2", ParametroCluster.ESTADO_PENDIENTE)),
        List.of(operarios(1L, 0)),
        List.of(),
        List.of(cluster(CL1, "CL1"), cluster(CL3, "CL3")),
        List.of(chute(1L, "CHUTE 1")));

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> lanzar(CL3, FaseBalanceo.RUTAS));
    assertTrue(
        error.getMessage().contains("CL3 se habilita cuando cierres CL1"), error.getMessage());
  }

  // ---------------- Paso 2: operarios de una ola ----------------

  @Test
  void operariosExigeElPaso1DelCluster() {
    prepararRegistro(
        archivo(),
        configuracion("100", null),
        List.of(limite(CL1, "2")),
        List.of(operarios(1L, 0)),
        List.of(),
        List.of(cluster(CL1, "CL1")),
        List.of(chute(1L, "CHUTE 1")));

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> lanzar(CL1, FaseBalanceo.OPERARIOS));
    assertTrue(error.getMessage().contains("rutas de CL1 (paso 1)"), error.getMessage());
  }

  @Test
  void operariosExigeElLimiteDelCluster() {
    ParametroCluster cl1 = limite(CL1, "0", ParametroCluster.ESTADO_RUTAS);
    prepararRegistro(
        archivo(),
        configuracion("100", null),
        List.of(cl1),
        List.of(operarios(1L, 0)),
        List.of(),
        List.of(cluster(CL1, "CL1")),
        List.of(chute(1L, "CHUTE 1")));

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> lanzar(CL1, FaseBalanceo.OPERARIOS));
    assertTrue(error.getMessage().contains("límite de horas de CL1"), error.getMessage());
    assertEquals(ParametroCluster.ESTADO_RUTAS, cl1.getEstado());
  }

  @Test
  void operariosUsaTodoElEquipoParaBajarElCierre() {
    // CL1 (límite 2 h, velocidad 100): con 3 y 2 ya cumpliría, pero trabajan los 10 del equipo.
    // 6 y 3 cierran en 1 h; el que sobra va al chute con más bultos.
    ParametroCluster cl1 = limite(CL1, "2", ParametroCluster.ESTADO_RUTAS);
    ArchivoExcel archivo = archivo();
    archivo.setEstado(ArchivoExcel.ESTADO_EN_PROCESO);
    // La base (1 por chute) no cuenta: tras el paso 1 la ola parte de 0 operarios.
    prepararRegistro(
        archivo,
        configuracion("100", 10),
        List.of(cl1),
        List.of(operarios(1L, 1), operarios(2L, 1)),
        List.of(),
        List.of(cluster(CL1, "CL1")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    when(rutaRepo.cargaPorArchivo(10L))
        .thenReturn(List.of(carga(CL1, 1L, 1, "600"), carga(CL1, 2L, 1, "300")));

    BalanceoResponse resultado = lanzar(CL1, FaseBalanceo.OPERARIOS);

    assertEquals(Map.of(1L, 7, 2L, 3), olaGuardada());
    verify(lotes).insertarHistorial(logs.capture());
    assertTrue(logs.getValue().stream().allMatch((log) -> log.getOperariosOrigen() == 0));
    assertEquals(10, resultado.operariosAsignados());
    assertTrue(
        resultado.mensaje().contains("Cumple su límite: 1.00 h de 2.00 h"), resultado.mensaje());
    assertFalse(resultado.mensaje().contains("sin chute"), resultado.mensaje());
    assertEquals(ParametroCluster.ESTADO_LISTO, cl1.getEstado());
    assertEquals("LISTO", resultado.estado());
  }

  @Test
  void operariosRespetaElMaximoPorOlaYDiceCuantosFaltan() {
    prepararRegistro(
        archivo(),
        configuracion("100", 3),
        List.of(limite(CL1, "2", ParametroCluster.ESTADO_RUTAS)),
        List.of(operarios(1L, 0), operarios(2L, 0)),
        List.of(),
        List.of(cluster(CL1, "CL1")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    when(rutaRepo.cargaPorArchivo(10L))
        .thenReturn(List.of(carga(CL1, 1L, 1, "600"), carga(CL1, 2L, 1, "300")));

    BalanceoResponse resultado = lanzar(CL1, FaseBalanceo.OPERARIOS);

    assertEquals(Map.of(1L, 2, 2L, 1), olaGuardada());
    assertTrue(
        resultado.mensaje().contains("3.00 h de 2.00 h; necesita 5 operario(s)"),
        resultado.mensaje());
  }

  @Test
  void operariosRespetaElMaximoPorChute() {
    SimulacionConfig config = configuracion("100", 10);
    config.setOperariosPorChute(2);
    prepararRegistro(
        archivo(),
        config,
        List.of(limite(CL1, "2", ParametroCluster.ESTADO_RUTAS)),
        List.of(operarios(1L, 0)),
        List.of(),
        List.of(cluster(CL1, "CL1")),
        List.of(chute(1L, "CHUTE 1")));
    when(rutaRepo.cargaPorArchivo(10L)).thenReturn(List.of(carga(CL1, 1L, 1, "600")));

    BalanceoResponse resultado = lanzar(CL1, FaseBalanceo.OPERARIOS);

    assertEquals(Map.of(1L, 2), olaGuardada());
    assertTrue(resultado.mensaje().contains("algún chute más de 2"), resultado.mensaje());
    assertTrue(
        resultado.mensaje().contains("8 sin chute: cada chute con carga ya tiene el máximo de 2"),
        resultado.mensaje());
  }

  @Test
  void operariosDeLaOlaAnteriorSiguenEnSuChute() {
    // CL1 cerró con 4 en CHUTE 1, que también atiende CL3: esa gente se queda ahí y llegan
    // los otros 6 del equipo. La ola de CL1 no cambia.
    OperarioOla olaCl1 = ola(CL1, 1L, 4);
    prepararRegistro(
        archivo(),
        configuracion("100", 10),
        List.of(
            limite(CL1, "2", ParametroCluster.ESTADO_CERRADO),
            limite(CL3, "2", ParametroCluster.ESTADO_RUTAS)),
        List.of(operarios(1L, 0)),
        List.of(olaCl1),
        List.of(cluster(CL1, "CL1"), cluster(CL3, "CL3")),
        List.of(chute(1L, "CHUTE 1")));
    when(rutaRepo.cargaPorArchivo(10L))
        .thenReturn(List.of(carga(CL1, 1L, 1, "800"), carga(CL3, 1L, 1, "200")));

    BalanceoResponse resultado = lanzar(CL3, FaseBalanceo.OPERARIOS);

    assertEquals(Map.of(1L, 10), olaGuardada());
    assertEquals(4, olaCl1.getOperarios());
    assertTrue(
        resultado.mensaje().contains("Desde CL1, 4 siguen en su chute"), resultado.mensaje());
  }

  @Test
  void operariosPartenDeLaOlaAnteriorYSumanLosLibres() {
    // CL1 cerró con 3 en CHUTE 2 y 2 en CHUTE 3 (equipo de 5). En CL2, CHUTE 2 conserva sus
    // 3; CHUTE 3 ya no trabaja y su gente pasa a CHUTE 1.
    prepararRegistro(
        archivo(),
        configuracion("100", 5),
        List.of(
            limite(CL1, "1", ParametroCluster.ESTADO_CERRADO),
            limite(CL2, "1", ParametroCluster.ESTADO_RUTAS)),
        List.of(operarios(1L, 0), operarios(2L, 0), operarios(3L, 0)),
        List.of(ola(CL1, 2L, 3), ola(CL1, 3L, 2)),
        List.of(cluster(CL1, "CL1"), cluster(CL2, "CL2")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2"), chute(3L, "CHUTE 3")));
    when(rutaRepo.cargaPorArchivo(10L))
        .thenReturn(
            List.of(
                carga(CL1, 2L, 1, "300"),
                carga(CL1, 3L, 1, "200"),
                carga(CL2, 1L, 1, "300"),
                carga(CL2, 2L, 1, "300")));

    BalanceoResponse resultado = lanzar(CL2, FaseBalanceo.OPERARIOS);

    assertEquals(Map.of(1L, 2, 2L, 3), olaGuardada());
    assertTrue(
        resultado.mensaje().contains("Desde CL1, 3 siguen en su chute"), resultado.mensaje());
    // La primera asignación compara con lo que cada chute traía de CL1.
    verify(lotes).insertarHistorial(logs.capture());
    assertEquals(1, logs.getValue().size());
    assertEquals(0, logs.getValue().get(0).getOperariosOrigen());
    assertEquals(2, logs.getValue().get(0).getOperariosDestino());
  }

  @Test
  void conElEquipoCompletoLaGenteVaDondeMasBajaElCierre() {
    // CL1 cerró con los 4 del equipo en CHUTE 1. En CL2, CHUTE 1 tiene 100 bultos y CHUTE 2,
    // 300: 1 y 3 cierran en 1 h (2 y 2 tardarían 1,5 h), así que 3 pasan a CHUTE 2.
    SimulacionConfig config = configuracion("100", 4);
    config.setOperariosPorChute(4);
    prepararRegistro(
        archivo(),
        config,
        List.of(
            limite(CL1, "2", ParametroCluster.ESTADO_CERRADO),
            limite(CL2, "2", ParametroCluster.ESTADO_RUTAS)),
        List.of(operarios(1L, 0), operarios(2L, 0)),
        List.of(ola(CL1, 1L, 4)),
        List.of(cluster(CL1, "CL1"), cluster(CL2, "CL2")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    when(rutaRepo.cargaPorArchivo(10L))
        .thenReturn(
            List.of(
                carga(CL1, 1L, 1, "800"), carga(CL2, 1L, 1, "100"), carga(CL2, 2L, 1, "300")));

    BalanceoResponse resultado = lanzar(CL2, FaseBalanceo.OPERARIOS);

    assertEquals(Map.of(1L, 1, 2L, 3), olaGuardada());
    assertEquals(4, resultado.operariosAsignados());
    assertTrue(
        resultado.mensaje().contains("Desde CL1, 1 siguen en su chute"), resultado.mensaje());
    assertTrue(
        resultado.mensaje().contains("Cumple su límite: 1.00 h de 2.00 h"), resultado.mensaje());
  }

  @Test
  void aIgualCierreSeMueveLaMenorCantidadDeGente() {
    // CL1 cerró con 3 en CHUTE 1 y 3 en CHUTE 2. En CL2 (100, 100 y 200 bultos) ningún
    // reparto de 6 baja de 1 h: bastan 1, 1 y 2, y los 2 que sobran se quedan en su chute
    // en vez de ir a CHUTE 3.
    prepararRegistro(
        archivo(),
        configuracion("100", 6),
        List.of(
            limite(CL1, "2", ParametroCluster.ESTADO_CERRADO),
            limite(CL2, "2", ParametroCluster.ESTADO_RUTAS)),
        List.of(operarios(1L, 0), operarios(2L, 0), operarios(3L, 0)),
        List.of(ola(CL1, 1L, 3), ola(CL1, 2L, 3)),
        List.of(cluster(CL1, "CL1"), cluster(CL2, "CL2")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2"), chute(3L, "CHUTE 3")));
    when(rutaRepo.cargaPorArchivo(10L))
        .thenReturn(
            List.of(
                carga(CL1, 1L, 1, "300"),
                carga(CL1, 2L, 1, "300"),
                carga(CL2, 1L, 1, "100"),
                carga(CL2, 2L, 1, "100"),
                carga(CL2, 3L, 1, "200")));

    BalanceoResponse resultado = lanzar(CL2, FaseBalanceo.OPERARIOS);

    Map<Long, Integer> ola = olaGuardada();
    assertEquals(2, ola.get(3L));
    assertEquals(6, resultado.operariosAsignados());
    assertTrue(
        resultado.mensaje().contains("Desde CL1, 4 siguen en su chute"), resultado.mensaje());
    assertTrue(
        resultado.mensaje().contains("Cumple su límite: 1.00 h de 2.00 h"), resultado.mensaje());
  }

  @Test
  void operariosSeRecalculanDesdeCero() {
    // La ola tenía 5 en CHUTE 1; ahora el máximo por chute es 3.
    OperarioOla olaCl1 = ola(CL1, 1L, 5);
    ParametroChute chute1 = operarios(1L, 1);
    SimulacionConfig config = configuracion("100", 10);
    config.setOperariosPorChute(3);
    prepararRegistro(
        archivo(),
        config,
        List.of(limite(CL1, "2", ParametroCluster.ESTADO_LISTO)),
        List.of(chute1),
        List.of(olaCl1),
        List.of(cluster(CL1, "CL1")),
        List.of(chute(1L, "CHUTE 1")));
    when(rutaRepo.cargaPorArchivo(10L)).thenReturn(List.of(carga(CL1, 1L, 1, "600")));

    lanzar(CL1, FaseBalanceo.OPERARIOS);

    assertEquals(3, olaCl1.getOperarios());
    // La base del usuario no cambia.
    assertEquals(1, chute1.getOperarios());
    verify(lotes).insertarHistorial(logs.capture());
    assertEquals(5, logs.getValue().get(0).getOperariosOrigen());
    assertEquals(3, logs.getValue().get(0).getOperariosDestino());
  }

  // ---------------- Restablecer, cerrar, reabrir y cambios manuales ----------------

  @Test
  void reconoceSoloLosNombresDeClusterNumerados() {
    assertEquals(1, SimulacionConfiguracionService.numeroCluster("CL1"));
    assertEquals(1, SimulacionConfiguracionService.numeroCluster("cl 01"));
    assertEquals(2, SimulacionConfiguracionService.numeroCluster("C2"));
    assertNull(SimulacionConfiguracionService.numeroCluster("CP1"));
    assertNull(SimulacionConfiguracionService.numeroCluster("CB2"));
  }

  @Test
  void restablecerTodoVuelveALaProyeccion() {
    ArchivoExcel archivo = archivo();
    archivo.setEstado(ArchivoExcel.ESTADO_EN_PROCESO);
    ParametroCluster cl1 = limite(CL1, "2", ParametroCluster.ESTADO_LISTO);
    OperarioOla olaCl1 = ola(CL1, 1L, 3);
    prepararRegistro(
        archivo,
        configuracion("100", null),
        List.of(cl1),
        List.of(operarios(1L, 1)),
        List.of(olaCl1),
        List.of(cluster(CL1, "CL1")),
        List.of(chute(1L, "CHUTE 1")));

    servicio.restablecer(10L).block();

    verify(rutaRepo).restaurarChutes(10L);
    verify(historialRepo).borrarPorArchivo(10L);
    verify(lanzamientoRepo).borrarPorArchivo(10L);
    verify(operarioOlaRepo).deleteAll(olasBorradas.capture());
    assertEquals(List.of(olaCl1), olasBorradas.getValue());
    assertEquals(ParametroCluster.ESTADO_PENDIENTE, cl1.getEstado());
    assertEquals(ArchivoExcel.ESTADO_PROYECCION, archivo.getEstado());
  }

  @Test
  void restablecerElClusterEnCursoNoTocaLosCerrados() {
    ArchivoExcel archivo = archivo();
    archivo.setEstado(ArchivoExcel.ESTADO_EN_PROCESO);
    ParametroCluster cl1 = limite(CL1, "2", ParametroCluster.ESTADO_CERRADO);
    ParametroCluster cl3 = limite(CL3, "2", ParametroCluster.ESTADO_LISTO);
    OperarioOla olaCl3 = ola(CL3, 2L, 2);
    prepararRegistro(
        archivo,
        configuracion("100", null),
        List.of(cl1, cl3),
        List.of(operarios(1L, 0), operarios(2L, 0)),
        List.of(ola(CL1, 1L, 3), olaCl3),
        List.of(cluster(CL1, "CL1"), cluster(CL3, "CL3")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    lenient().when(rutaRepo.cargaPorArchivo(10L)).thenReturn(List.of());
    // Su balanceo llevó la ruta de CHUTE 2 a CHUTE 1 y luego a CHUTE 3: restablecer la
    // devuelve a CHUTE 2 (el origen de su movimiento más antiguo).
    HistorialBalanceo segunda = new HistorialBalanceo();
    segunda.setRutaId(5L);
    segunda.setChuteOrigenId(1L);
    segunda.setChuteDestinoId(3L);
    HistorialBalanceo movida = new HistorialBalanceo();
    movida.setRutaId(5L);
    movida.setChuteOrigenId(2L);
    movida.setChuteDestinoId(1L);
    when(historialRepo.movimientosParaDeshacer(10L, CL3)).thenReturn(List.of(segunda, movida));

    servicio.restablecerCluster(10L, CL3).block();

    verify(lotes).moverRutas(Map.of(5L, 2L));
    verify(rutaRepo, never()).restaurarChutes(10L);
    verify(lanzamientoRepo).borrarPorCluster(10L, CL3);
    verify(historialRepo).borrarSinLanzamientoDeCluster(10L, CL3);
    verify(operarioOlaRepo).deleteAll(olasBorradas.capture());
    assertEquals(List.of(olaCl3), olasBorradas.getValue());
    assertEquals(ParametroCluster.ESTADO_CERRADO, cl1.getEstado());
    assertEquals(ParametroCluster.ESTADO_PENDIENTE, cl3.getEstado());
    assertEquals(ArchivoExcel.ESTADO_EN_PROCESO, archivo.getEstado());
  }

  @Test
  void cerrarExigeLosOperariosDelCluster() {
    ParametroCluster cl1 = limite(CL1, "2", ParametroCluster.ESTADO_RUTAS);
    prepararRegistro(
        archivo(),
        configuracion("100", null),
        List.of(cl1),
        List.of(operarios(1L, 0)),
        List.of(),
        List.of(cluster(CL1, "CL1")),
        List.of(chute(1L, "CHUTE 1")));

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> servicio.cerrar(10L, CL1).block());
    assertTrue(error.getMessage().contains("asigna los operarios de CL1"), error.getMessage());
    assertEquals(ParametroCluster.ESTADO_RUTAS, cl1.getEstado());
  }

  @Test
  void cerrarRechazaCl1YCl2Juntos() {
    prepararRegistro(
        archivo(),
        configuracion("100", null),
        List.of(
            limite(CL1, "2", ParametroCluster.ESTADO_LISTO),
            limite(CL2, "2", ParametroCluster.ESTADO_PENDIENTE)),
        List.of(operarios(1L, 0)),
        List.of(),
        List.of(cluster(CL1, "CL1"), cluster(CL2, "CL2")),
        List.of(chute(1L, "CHUTE 1")));
    when(rutaRepo.cargaPorArchivo(10L))
        .thenReturn(List.of(carga(CL1, 1L, 1, "100"), carga(CL2, 1L, 1, "100")));

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> servicio.cerrar(10L, CL1).block());
    assertTrue(error.getMessage().contains("CL1 sigue compartiendo CHUTE 1"), error.getMessage());
  }

  @Test
  void cerrarElUltimoTerminaYReabrirDevuelveLosSiguientesALaProyeccion() {
    ArchivoExcel archivo = archivo();
    archivo.setEstado(ArchivoExcel.ESTADO_EN_PROCESO);
    ParametroCluster cl1 = limite(CL1, "2", ParametroCluster.ESTADO_CERRADO);
    ParametroCluster cl3 = limite(CL3, "2", ParametroCluster.ESTADO_LISTO);
    OperarioOla olaCl3 = ola(CL3, 2L, 2);
    prepararRegistro(
        archivo,
        configuracion("100", null),
        List.of(cl1, cl3),
        List.of(operarios(1L, 0), operarios(2L, 0)),
        List.of(ola(CL1, 1L, 2), olaCl3),
        List.of(cluster(CL1, "CL1"), cluster(CL3, "CL3")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    when(rutaRepo.cargaPorArchivo(10L))
        .thenReturn(List.of(carga(CL1, 1L, 1, "100"), carga(CL3, 2L, 1, "100")));

    // Cerrar el último cluster termina la simulación.
    servicio.cerrar(10L, CL3).block();
    assertEquals(ParametroCluster.ESTADO_CERRADO, cl3.getEstado());
    assertEquals(ArchivoExcel.ESTADO_FINALIZADO, archivo.getEstado());

    // Con clusters cerrados el equipo no cambia sin reabrirlos.
    ConflictoException error =
        assertThrows(
            ConflictoException.class,
            () ->
                servicio
                    .guardar(
                        10L,
                        new ConfiguracionActualizarRequest(
                            new BigDecimal("120"), null, null, List.of(), List.of()))
                    .block());
    assertTrue(error.getMessage().contains("reabre el primero"), error.getMessage());

    // Reabrir CL1 devuelve CL3 (que partía de su dotación) a la proyección.
    servicio.reabrir(10L, CL1).block();
    assertEquals(ParametroCluster.ESTADO_LISTO, cl1.getEstado());
    assertEquals(ParametroCluster.ESTADO_PENDIENTE, cl3.getEstado());
    verify(historialRepo).movimientosParaDeshacer(10L, CL3);
    verify(lanzamientoRepo).borrarPorCluster(10L, CL3);
    verify(operarioOlaRepo).deleteAll(olasBorradas.capture());
    assertEquals(List.of(olaCl3), olasBorradas.getValue());
    assertEquals(ArchivoExcel.ESTADO_EN_PROCESO, archivo.getEstado());
  }

  @Test
  void guardarSoloInvalidaLasOlasAfectadas() {
    ArchivoExcel archivo = archivo();
    archivo.setEstado(ArchivoExcel.ESTADO_EN_PROCESO);
    ParametroCluster cl1 = limite(CL1, "2", ParametroCluster.ESTADO_LISTO);
    ParametroCluster cl3 = limite(CL3, "2", ParametroCluster.ESTADO_LISTO);
    OperarioOla olaCl3 = ola(CL3, 2L, 2);
    ParametroChute chute2 = operarios(2L, 1);
    prepararRegistro(
        archivo,
        configuracion("100", 10),
        List.of(cl1, cl3),
        List.of(operarios(1L, 1), chute2),
        List.of(ola(CL1, 1L, 3), olaCl3),
        List.of(cluster(CL1, "CL1"), cluster(CL3, "CL3")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    lenient().when(rutaRepo.cargaPorArchivo(10L)).thenReturn(List.of());

    // Cambia el límite de CL3 y la base de CHUTE 2: solo CL3 vuelve al paso 2.
    servicio
        .guardar(
            10L,
            new ConfiguracionActualizarRequest(
                new BigDecimal("100"),
                10,
                null,
                List.of(
                    new ClusterLimiteRequest(CL1, new BigDecimal("2")),
                    new ClusterLimiteRequest(CL3, new BigDecimal("3"))),
                List.of(new ChuteOperariosRequest(2L, 4))))
        .block();

    assertEquals(4, chute2.getOperarios());
    assertEquals(ParametroCluster.ESTADO_LISTO, cl1.getEstado());
    assertEquals(ParametroCluster.ESTADO_RUTAS, cl3.getEstado());
    verify(operarioOlaRepo).deleteAll(olasBorradas.capture());
    assertEquals(List.of(olaCl3), olasBorradas.getValue());

    // Con máximo 2 por chute, la base 4 de CHUTE 2 ya no cabe.
    ConflictoException error =
        assertThrows(
            ConflictoException.class,
            () ->
                servicio
                    .guardar(
                        10L,
                        new ConfiguracionActualizarRequest(
                            new BigDecimal("100"), 10, 2, List.of(), List.of()))
                    .block());
    assertTrue(error.getMessage().contains("CHUTE 2 tiene 4"), error.getMessage());
  }

  @Test
  void cambiarLaVelocidadInvalidaTodasLasOlas() {
    ArchivoExcel archivo = archivo();
    archivo.setEstado(ArchivoExcel.ESTADO_EN_PROCESO);
    ParametroCluster cl1 = limite(CL1, "2", ParametroCluster.ESTADO_LISTO);
    ParametroCluster cl3 = limite(CL3, "2", ParametroCluster.ESTADO_LISTO);
    prepararRegistro(
        archivo,
        configuracion("100", 10),
        List.of(cl1, cl3),
        List.of(operarios(1L, 1)),
        List.of(),
        List.of(cluster(CL1, "CL1"), cluster(CL3, "CL3")),
        List.of(chute(1L, "CHUTE 1")));
    lenient().when(rutaRepo.cargaPorArchivo(10L)).thenReturn(List.of());

    servicio
        .guardar(
            10L,
            new ConfiguracionActualizarRequest(
                new BigDecimal("120"), 10, null, List.of(), List.of()))
        .block();

    assertEquals(ParametroCluster.ESTADO_RUTAS, cl1.getEstado());
    assertEquals(ParametroCluster.ESTADO_RUTAS, cl3.getEstado());
  }

  @Test
  void moverUnaRutaAManoSoloDejaPendienteSuCluster() {
    ArchivoExcel archivo = archivo();
    archivo.setEstado(ArchivoExcel.ESTADO_EN_PROCESO);
    ParametroCluster cl1 = limite(CL1, "2", ParametroCluster.ESTADO_LISTO);
    ParametroCluster cl3 = limite(CL3, "2", ParametroCluster.ESTADO_LISTO);
    prepararRegistro(
        archivo,
        configuracion("100", null),
        List.of(cl1, cl3),
        List.of(operarios(1L, 1), operarios(2L, 1)),
        List.of(ola(CL1, 2L, 2), ola(CL3, 1L, 1)),
        List.of(cluster(CL1, "CL1"), cluster(CL3, "CL3")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    RutaProyeccion ruta = ruta(1L, CL1, 1L, "3030", "10");
    ruta.setChuteId(2L);
    when(rutaRepo.findByIdAndArchivoId(1L, 10L)).thenReturn(Optional.of(ruta));
    when(rutaRepo.clustersEnChute(10L, 1L)).thenReturn(List.of());
    when(rutaRepo.cargaPorArchivo(10L)).thenReturn(List.of());

    servicio.mover(10L, 1L, new MoverRutaRequest(1L)).block();

    assertEquals(1L, ruta.getChuteId());
    assertEquals(ParametroCluster.ESTADO_RUTAS, cl1.getEstado());
    assertEquals(ParametroCluster.ESTADO_LISTO, cl3.getEstado());
  }

  @Test
  void noSeMuevenRutasDeUnClusterCerrado() {
    prepararRegistro(
        archivo(),
        configuracion("100", null),
        List.of(limite(CL1, "2", ParametroCluster.ESTADO_CERRADO)),
        List.of(operarios(1L, 0), operarios(2L, 0)),
        List.of(),
        List.of(cluster(CL1, "CL1")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    RutaProyeccion ruta = ruta(1L, CL1, 1L, "3030", "10");
    when(rutaRepo.findByIdAndArchivoId(1L, 10L)).thenReturn(Optional.of(ruta));

    ConflictoException error =
        assertThrows(
            ConflictoException.class,
            () -> servicio.mover(10L, 1L, new MoverRutaRequest(2L)).block());
    assertTrue(error.getMessage().contains("CL1 está cerrado"), error.getMessage());
    assertEquals(1L, ruta.getChuteId());
  }

  // ---------------- Dashboard ----------------

  @Test
  @SuppressWarnings("unchecked")
  void rutasDelDashboardFiltranPorLadoDelChutePrefijoEscapadoYTipo() {
    prepararLectura(
        archivo(),
        List.of(limite(CL1, "2")),
        List.of(operarios(1L, 1)),
        List.of(),
        List.of(cluster(CL1, "CL1")),
        List.of(chute(1L, "CHUTE 1")));
    RutaProyeccion ruta = ruta(1L, CL1, 1L, "30_1", "10");
    ruta.setTipo(TipoRuta.B2B);
    when(rutaRepo.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(ruta), PageRequest.of(0, 25), 1));

    RutasDashboardResponse resultado =
        servicio
            .rutasDashboard(
                10L, 1, 25, "30_", TipoRuta.B2B, List.of(1L, 2L), LadoChute.ANTES, null, false)
            .block()
            .datos();

    // "_" es comodín de LIKE: se escapa para buscar el texto literal.
    verify(rutaRepo).resumenFiltrado(10L, "30\\_%", "B2B", List.of(1L, 2L), true, null, false);
    assertEquals(0, resultado.resumen().rutas());
    assertEquals(1, resultado.rutas().contenido().size());
    assertEquals("B2B", resultado.rutas().contenido().get(0).tipo());
    assertEquals("CHUTE 1", resultado.rutas().contenido().get(0).chuteOriginal());
  }

  @Test
  void dashboardSeparaLosClustersDeCadaChuteAntesYDespues() {
    ArchivoExcel archivo = archivo();
    archivo.setEstado(ArchivoExcel.ESTADO_EN_PROCESO);
    prepararLectura(
        archivo,
        List.of(
            limite(CL1, "10", ParametroCluster.ESTADO_RUTAS),
            limite(CL2, "10", ParametroCluster.ESTADO_RUTAS)),
        List.of(operarios(1L, 1), operarios(2L, 1)),
        List.of(),
        List.of(cluster(CL1, "CL1"), cluster(CL2, "CL2")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    // Al cargar, CHUTE 1 juntaba CL1 y CL2; el balanceo llevó la ruta de CL2 a CHUTE 2.
    when(rutaRepo.cargaOriginalPorArchivo(10L))
        .thenReturn(List.of(carga(CL1, 1L, 3, "300"), carga(CL2, 1L, 1, "50")));
    when(rutaRepo.cargaPorArchivo(10L))
        .thenReturn(List.of(carga(CL1, 1L, 3, "300"), carga(CL2, 2L, 1, "50")));
    when(rutaRepo.movimientosPorArchivo(10L, null)).thenReturn(List.of(movimiento(1L, 2L, 1)));

    DashboardSimulacionResponse dashboard = servicio.dashboard(10L, null).block().datos();

    ChuteComparativoResponse chute1 = dashboard.chutes().get(0);
    assertEquals(List.of("CL1", "CL2"), chute1.clustersOriginal());
    assertEquals(List.of("CL1"), chute1.clustersBalanceado());
    assertTrue(chute1.mezclaOriginal());
    assertFalse(chute1.mezclaBalanceada());
    assertEquals(1, chute1.rutasSalieron());
    // Con solo las rutas balanceadas no hay operarios ni tiempos; el resumen no tiene «antes».
    assertEquals(0, chute1.operariosOriginal());
    assertEquals(0, chute1.operariosBalanceado());
    assertNull(dashboard.kpis().tiempoBalanceado());
    assertNull(dashboard.kpis().tiempoOriginal());
    assertFalse(dashboard.chutes().get(1).mezclaBalanceada());
    assertTrue(dashboard.mezclas().isEmpty());
    assertEquals(1, dashboard.movimientos().size());
    assertEquals(2L, dashboard.movimientos().get(0).destinoId());
    assertEquals(List.of("CL1", "CL2"), dashboard.olas().stream().map((o) -> o.cluster()).toList());
  }

  @Test
  void dashboardDelPrimerClusterSoloCuentaSuOla() {
    ArchivoExcel archivo = archivo();
    archivo.setEstado(ArchivoExcel.ESTADO_EN_PROCESO);
    // La ruta de CL1 que estaba en CHUTE 2 se movió: CHUTE 2 ya no trabaja en la ola de CL1.
    prepararLectura(
        archivo,
        List.of(
            limite(CL1, "10", ParametroCluster.ESTADO_CERRADO),
            limite(CL3, "10", ParametroCluster.ESTADO_LISTO)),
        List.of(operarios(1L, 1), operarios(2L, 1)),
        List.of(ola(CL1, 1L, 4), ola(CL3, 1L, 2)),
        List.of(cluster(CL1, "CL1"), cluster(CL3, "CL3")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    when(rutaRepo.cargaOriginalPorArchivo(10L))
        .thenReturn(
            List.of(
                carga(CL1, 1L, 1, "300"), carga(CL1, 2L, 1, "100"), carga(CL3, 1L, 1, "100")));
    when(rutaRepo.cargaPorArchivo(10L))
        .thenReturn(List.of(carga(CL1, 1L, 2, "400"), carga(CL3, 1L, 1, "100")));
    when(rutaRepo.movimientosPorArchivo(10L, CL1)).thenReturn(List.of());

    DashboardSimulacionResponse dashboard = servicio.dashboard(10L, CL1).block().datos();

    ChuteComparativoResponse chute1 = dashboard.chutes().get(0);
    assertEquals(new BigDecimal("400"), chute1.bultosBalanceado());
    assertEquals(4, chute1.operariosBalanceado());
    // CL1 es la primera ola: no recibe operarios de nadie, así que no hay «antes».
    assertEquals(0, chute1.operariosOriginal());
    assertNull(dashboard.kpis().tiempoOriginal());
    assertEquals(0, dashboard.chutes().get(1).operariosBalanceado());
    // 400 bultos / (100 × 4) = 1 h en la ola de CL1.
    assertEquals(0, new BigDecimal("1").compareTo(dashboard.kpis().tiempoBalanceado()));
    assertEquals(1, dashboard.clusters().size());
    assertTrue(dashboard.flujoOperarios().isEmpty());
  }

  @Test
  void dashboardDeUnClusterComparaConLoQueRecibeDeLaOlaAnterior() {
    ArchivoExcel archivo = archivo();
    archivo.setEstado(ArchivoExcel.ESTADO_EN_PROCESO);
    // CL1 cerró con 4 en CHUTE 1; CL3 quedó con 2 ahí (los otros 2 se liberan).
    prepararLectura(
        archivo,
        List.of(
            limite(CL1, "10", ParametroCluster.ESTADO_CERRADO),
            limite(CL3, "10", ParametroCluster.ESTADO_LISTO)),
        List.of(operarios(1L, 0)),
        List.of(ola(CL1, 1L, 4), ola(CL3, 1L, 2)),
        List.of(cluster(CL1, "CL1"), cluster(CL3, "CL3")),
        List.of(chute(1L, "CHUTE 1")));
    List<CargaRutas> carga = List.of(carga(CL1, 1L, 2, "400"), carga(CL3, 1L, 1, "100"));
    when(rutaRepo.cargaOriginalPorArchivo(10L)).thenReturn(carga);
    when(rutaRepo.cargaPorArchivo(10L)).thenReturn(carga);
    when(rutaRepo.movimientosPorArchivo(10L, CL3)).thenReturn(List.of());

    DashboardSimulacionResponse dashboard = servicio.dashboard(10L, CL3).block().datos();

    ChuteComparativoResponse chute1 = dashboard.chutes().get(0);
    assertEquals(4, chute1.operariosOriginal());
    assertEquals(2, chute1.operariosBalanceado());
    // Si nadie se moviera: 100 / (100 × 4) = 0,25 h; con lo asignado: 100 / (100 × 2) = 0,5 h.
    assertEquals(0, new BigDecimal("0.25").compareTo(dashboard.kpis().tiempoOriginal()));
    assertEquals(0, new BigDecimal("0.5").compareTo(dashboard.kpis().tiempoBalanceado()));
    assertEquals(1, dashboard.flujoOperarios().size());
    FlujoOperariosResponse paso = dashboard.flujoOperarios().get(0);
    assertEquals("CL1", paso.origen());
    assertEquals(2, paso.quedan());
    assertEquals(2, paso.salen());
  }

  @Test
  void resumenMuestraElFlujoDeOperariosEntreOlas() {
    ArchivoExcel archivo = archivo();
    archivo.setEstado(ArchivoExcel.ESTADO_FINALIZADO);
    // CL1: CHUTE 1 con 4 y CHUTE 2 con 4. CL3: CHUTE 1 con 2, CHUTE 3 con 4 y CHUTE 4 con 2.
    prepararLectura(
        archivo,
        List.of(
            limite(CL1, "10", ParametroCluster.ESTADO_CERRADO),
            limite(CL3, "10", ParametroCluster.ESTADO_CERRADO)),
        List.of(operarios(1L, 0), operarios(2L, 0), operarios(3L, 0), operarios(4L, 0)),
        List.of(
            ola(CL1, 1L, 4), ola(CL1, 2L, 4), ola(CL3, 1L, 2), ola(CL3, 3L, 4), ola(CL3, 4L, 2)),
        List.of(cluster(CL1, "CL1"), cluster(CL3, "CL3")),
        List.of(
            chute(1L, "CHUTE 1"),
            chute(2L, "CHUTE 2"),
            chute(3L, "CHUTE 3"),
            chute(4L, "CHUTE 4")));
    List<CargaRutas> carga =
        List.of(
            carga(CL1, 1L, 1, "400"),
            carga(CL1, 2L, 1, "400"),
            carga(CL3, 1L, 1, "200"),
            carga(CL3, 3L, 1, "400"),
            carga(CL3, 4L, 1, "200"));
    when(rutaRepo.cargaOriginalPorArchivo(10L)).thenReturn(carga);
    when(rutaRepo.cargaPorArchivo(10L)).thenReturn(carga);
    when(rutaRepo.movimientosPorArchivo(10L, null)).thenReturn(List.of());

    DashboardSimulacionResponse dashboard = servicio.dashboard(10L, null).block().datos();

    assertEquals(1, dashboard.flujoOperarios().size());
    FlujoOperariosResponse flujo = dashboard.flujoOperarios().get(0);
    assertEquals("CL1", flujo.origen());
    assertEquals("CL3", flujo.destino());
    // CHUTE 1 conserva 2 y libera 2; CHUTE 2 libera 4; CHUTE 3 recibe 4 y CHUTE 4, 2.
    assertEquals(2, flujo.quedan());
    assertEquals(6, flujo.salen());
    assertEquals(6, flujo.llegan());
    assertEquals(
        List.of("CHUTE 1", "CHUTE 2", "CHUTE 3", "CHUTE 4"),
        flujo.chutes().stream().map((c) -> c.chute()).toList());
    // En el resumen, el chute muestra el máximo entre sus olas.
    assertEquals(4, dashboard.chutes().get(0).operariosBalanceado());
    // Dotación: cada chute con sus operarios en CL1 y CL3; resultado de cada ola.
    assertEquals(
        List.of(List.of(4, 2), List.of(4, 0), List.of(0, 4), List.of(0, 2)),
        dashboard.dotacion().stream().map((fila) -> fila.operarios()).toList());
    // CL1: 400 / (100 × 4) = 1 h; CL3: su chute más lento, 400 / (100 × 4) = 1 h.
    assertEquals(0, BigDecimal.ONE.compareTo(dashboard.olas().get(0).tiempoCierre()));
    assertEquals(0, BigDecimal.ONE.compareTo(dashboard.olas().get(1).tiempoCierre()));
  }

  @Test
  void resumenDaElCuadroDeCadaCluster() {
    ArchivoExcel archivo = archivo();
    archivo.setEstado(ArchivoExcel.ESTADO_FINALIZADO);
    // CL1 con límite 2 h: 400 y 400 bultos con 4 y 4 operarios → cierra en 1 h.
    prepararLectura(
        archivo,
        List.of(limite(CL1, "2", ParametroCluster.ESTADO_CERRADO)),
        List.of(operarios(1L, 4), operarios(2L, 4)),
        List.of(ola(CL1, 1L, 4), ola(CL1, 2L, 4)),
        List.of(cluster(CL1, "CL1")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    when(rutaRepo.cargaOriginalPorArchivo(10L))
        .thenReturn(List.of(carga(CL1, 1L, 3, "600"), carga(CL1, 2L, 1, "200")));
    when(rutaRepo.cargaPorArchivo(10L))
        .thenReturn(List.of(carga(CL1, 1L, 2, "400"), carga(CL1, 2L, 2, "400")));
    when(rutaRepo.movimientosPorArchivo(10L, null)).thenReturn(List.of(movimiento(1L, 2L, 1)));
    when(rutaRepo.rutasMovidasPorCluster(10L)).thenReturn(List.of(movidas(CL1, 1)));

    DashboardSimulacionResponse dashboard = servicio.dashboard(10L, null).block().datos();

    OlaSimulacionResponse ola = dashboard.olas().get(0);
    assertEquals(0, new BigDecimal("2").compareTo(ola.limiteHoras()));
    assertEquals(0, BigDecimal.ONE.compareTo(ola.tiempoCierre()));
    assertEquals(0, new BigDecimal("800").compareTo(ola.bultos()));
    assertEquals(1, ola.rutasMovidas());
  }

  @Test
  void elHistorialListaLanzamientosYPaginaSusCambios() {
    prepararLectura(
        archivo(),
        List.of(limite(CL1, "2", ParametroCluster.ESTADO_RUTAS)),
        List.of(operarios(1L, 0), operarios(2L, 0)),
        List.of(),
        List.of(cluster(CL1, "CL1")),
        List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2")));
    LanzamientoBalanceo lanzado = new LanzamientoBalanceo();
    lanzado.setId(77L);
    lanzado.setClusterId(CL1);
    lanzado.setFase("RUTAS");
    lanzado.setMensaje("CL1: 1200 ruta(s) movida(s).");
    lanzado.setRutas(1200);
    when(lanzamientoRepo.findTop200ByArchivoIdOrderByIdDesc(10L)).thenReturn(List.of(lanzado));
    when(historialRepo.countByArchivoIdAndLanzamientoIdIsNull(10L)).thenReturn(3L);
    HistorialBalanceo log = new HistorialBalanceo();
    log.setId(900L);
    log.setLanzamientoId(77L);
    log.setTipoAccion(HistorialBalanceo.ACCION_MOVER_RUTA);
    log.setRutaId(5L);
    log.setChuteOrigenId(1L);
    log.setChuteDestinoId(2L);
    when(historialRepo.findByArchivoIdAndLanzamientoId(eq(10L), eq(77L), any()))
        .thenReturn(new PageImpl<>(List.of(log), PageRequest.of(1, 25), 1200));
    when(rutaRepo.findAllById(any())).thenReturn(List.of(ruta(5L, CL1, 2L, "3050", "40")));

    HistorialRegistroResponse historial = servicio.historial(10L).block().datos();
    PaginaResponse<HistorialSimulacionResponse> cambios =
        servicio.historialCambios(10L, 77L, 2, 25).block().datos();

    assertEquals(1200, historial.lanzamientos().get(0).rutas());
    assertEquals(3L, historial.sinLanzamiento());
    assertEquals(1200, cambios.total());
    assertEquals(2, cambios.pagina());
    assertEquals("3050", cambios.contenido().get(0).ruta());
    assertEquals("CHUTE 2", cambios.contenido().get(0).chuteDestino());
  }

  // ---------------- Apoyo ----------------

  private BalanceoResponse lanzar(Long clusterId, FaseBalanceo fase) {
    return servicio.balancear(10L, clusterId, fase).block().datos();
  }

  /** Operarios que el paso 2 guardó en la ola, por chute. */
  private Map<Long, Integer> olaGuardada() {
    verify(operarioOlaRepo).saveAll(olasGuardadas.capture());
    Map<Long, Integer> resultado = new HashMap<>();
    olasGuardadas
        .getValue()
        .forEach((ola) -> resultado.put(ola.getId().getChuteId(), ola.getOperarios()));
    return resultado;
  }

  private static CargaRutas carga(Long clusterId, Long chuteId, long rutas, String bultos) {
    return new CargaRutas() {
      public Long getClusterId() {
        return clusterId;
      }

      public Long getChuteId() {
        return chuteId;
      }

      public Long getRutas() {
        return rutas;
      }

      public BigDecimal getBultos() {
        return new BigDecimal(bultos);
      }

      public BigDecimal getUnidades() {
        return BigDecimal.ZERO;
      }

      public BigDecimal getLineas() {
        return BigDecimal.ZERO;
      }

      public BigDecimal getCod() {
        return BigDecimal.ZERO;
      }
    };
  }

  private static RutasMovidasCluster movidas(Long clusterId, long rutas) {
    return new RutasMovidasCluster() {
      public Long getClusterId() {
        return clusterId;
      }

      public Long getRutas() {
        return rutas;
      }
    };
  }

  private static MovimientoRutas movimiento(Long origen, Long destino, long rutas) {
    return new MovimientoRutas() {
      public Long getOrigen() {
        return origen;
      }

      public Long getDestino() {
        return destino;
      }

      public Long getRutas() {
        return rutas;
      }
    };
  }

  /** Registro bloqueado para escribir (lanzar, guardar, mover, restablecer, finalizar). */
  private void prepararRegistro(
      ArchivoExcel archivo,
      SimulacionConfig config,
      List<ParametroCluster> limites,
      List<ParametroChute> operarios,
      List<OperarioOla> olas,
      List<Cluster> clusters,
      List<Chute> chutes) {
    when(archivoRepo.bloquear(10L)).thenReturn(Optional.of(archivo));
    when(configRepo.findById(10L)).thenReturn(Optional.of(config));
    prepararParametros(limites, operarios, olas, clusters, chutes);
    // Las operaciones rechazadas no llegan a guardar.
    lenient().when(archivoRepo.save(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
  }

  /** Registro para lecturas (dashboard, rutas). */
  private void prepararLectura(
      ArchivoExcel archivo,
      List<ParametroCluster> limites,
      List<ParametroChute> operarios,
      List<OperarioOla> olas,
      List<Cluster> clusters,
      List<Chute> chutes) {
    when(archivoRepo.findById(10L)).thenReturn(Optional.of(archivo));
    when(configRepo.findById(10L)).thenReturn(Optional.of(configuracion("100", null)));
    prepararParametros(limites, operarios, olas, clusters, chutes);
  }

  private void prepararParametros(
      List<ParametroCluster> limites,
      List<ParametroChute> operarios,
      List<OperarioOla> olas,
      List<Cluster> clusters,
      List<Chute> chutes) {
    when(parametroClusterRepo.findByIdArchivoId(10L)).thenReturn(limites);
    when(parametroChuteRepo.findByIdArchivoId(10L)).thenReturn(operarios);
    when(operarioOlaRepo.findByIdArchivoId(10L)).thenReturn(olas);
    when(clusterRepo.findAllById(any())).thenReturn(clusters);
    when(chuteRepo.findAllById(any())).thenReturn(chutes);
  }

  private static ArchivoExcel archivo() {
    ArchivoExcel archivo = new ArchivoExcel();
    archivo.setId(10L);
    archivo.setEstado(ArchivoExcel.ESTADO_PROYECCION);
    return archivo;
  }

  private static SimulacionConfig configuracion(String velocidad, Integer maxOperarios) {
    SimulacionConfig config = new SimulacionConfig();
    config.setArchivoId(10L);
    config.setVelocidadBase(new BigDecimal(velocidad));
    config.setOperariosMaximos(maxOperarios);
    return config;
  }

  private static ParametroCluster limite(Long clusterId, String horas) {
    return limite(clusterId, horas, ParametroCluster.ESTADO_PENDIENTE);
  }

  private static ParametroCluster limite(Long clusterId, String horas, String estado) {
    ParametroCluster parametro = new ParametroCluster();
    parametro.setId(new ParametroClusterId(10L, clusterId));
    parametro.setLimiteHoras(new BigDecimal(horas));
    parametro.setEstado(estado);
    return parametro;
  }

  private static ParametroChute operarios(Long chuteId, int base) {
    ParametroChute parametro = new ParametroChute();
    parametro.setId(new ParametroChuteId(10L, chuteId));
    parametro.setOperarios(base);
    return parametro;
  }

  private static OperarioOla ola(Long clusterId, Long chuteId, int operarios) {
    OperarioOla ola = new OperarioOla();
    ola.setId(new OperarioOlaId(10L, clusterId, chuteId));
    ola.setOperarios(operarios);
    return ola;
  }

  private static Cluster cluster(Long id, String nombre) {
    Cluster cluster = new Cluster();
    cluster.setId(id);
    cluster.setNombre(nombre);
    return cluster;
  }

  private static Chute chute(Long id, String nombre) {
    Chute chute = new Chute();
    chute.setId(id);
    chute.setNombre(nombre);
    return chute;
  }

  private static RutaProyeccion ruta(
      Long id, Long clusterId, Long chuteId, String codigo, String bultos) {
    RutaProyeccion ruta = new RutaProyeccion();
    ruta.setId(id);
    ruta.setArchivoId(10L);
    ruta.setClusterId(clusterId);
    ruta.setChuteId(chuteId);
    ruta.setChuteOriginalId(chuteId);
    ruta.setCodigoRuta(codigo);
    ruta.setTotalBultos(new BigDecimal(bultos));
    ruta.setTotalUnidades(new BigDecimal(bultos));
    ruta.setTotalLineas(new BigDecimal("1"));
    ruta.setConteoCod(1);
    return ruta;
  }
}
