package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import oracle.common.config.CacheRespuestas;
import oracle.common.config.Transacciones;
import oracle.common.exception.ConflictoException;
import oracle.simulacion.model.ArchivoExcel;
import oracle.simulacion.model.Balanceo;
import oracle.simulacion.model.Chute;
import oracle.simulacion.model.Cluster;
import oracle.simulacion.model.LanzamientoBalanceo;
import oracle.simulacion.model.OperarioOla;
import oracle.simulacion.model.OperarioOlaId;
import oracle.simulacion.model.ParametroChute;
import oracle.simulacion.model.ParametroChuteId;
import oracle.simulacion.model.ParametroCluster;
import oracle.simulacion.model.ParametroClusterId;
import oracle.simulacion.model.RutaProyeccion;
import oracle.simulacion.model.SimulacionConfig;
import oracle.simulacion.repository.ArchivoExcelRepository;
import oracle.simulacion.repository.ChuteRepository;
import oracle.simulacion.repository.ClusterRepository;
import oracle.simulacion.repository.HistorialBalanceoRepository;
import oracle.simulacion.repository.LanzamientoBalanceoRepository;
import oracle.simulacion.repository.OperarioOlaRepository;
import oracle.simulacion.repository.ParametroChuteRepository;
import oracle.simulacion.repository.ParametroClusterRepository;
import oracle.simulacion.repository.RutaProyeccionRepository;
import oracle.simulacion.repository.SimulacionConfigRepository;
import oracle.simulacion.repository.SimulacionLotesRepository;
import oracle.simulacion.request.BalanceoRequest;
import oracle.simulacion.request.ChuteOperariosRequest;
import oracle.simulacion.request.ConfiguracionActualizarRequest;
import oracle.simulacion.request.FaseBalanceo;
import oracle.simulacion.request.MoverRutaRequest;
import oracle.simulacion.response.BalanceoResponse;
import oracle.simulacion.response.ClusterParametroResponse;
import oracle.simulacion.response.ConfiguracionSimulacionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Dos balanceos (L-X-V y M-J-S) con rutas diarias compartidas: el primer balanceo automático
 * acomoda las diarias y desde ahí ningún automático las mueve; a mano sí. Chutes con buff: reciben
 * primero la ruta más pesada.
 */
@ExtendWith(MockitoExtension.class)
class SimulacionFrecuenciasTest {

  private static final Long CL1 = 1L;
  private static final Long CL2 = 2L;

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
            new CacheRespuestas(false, 1, 1),
            PlanoChutes.fijo(1, 3, Set.of(), Map.of()));
    lenient()
        .when(transacciones.ejecutar(any()))
        .thenAnswer(invocacion -> ((Supplier<?>) invocacion.getArgument(0)).get());
    lenient()
        .when(transacciones.consultar(any(), anyInt()))
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

  // ---------------- Rutas diarias ----------------

  @Test
  void elSegundoBalanceoNoMueveLasDiariasYSoloVeSusRutas() {
    // L-X-V ya balanceó CL1: sus diarias quedaron fijas. En M-J-S solo se mueven las M-J-S.
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE);
    preparar(List.of(lxv, mjs), List.of(base(1L, false), base(2L, false)), List.of());
    RutaProyeccion diaria = ruta(1L, 1L, "500", Balanceo.DIARIA);
    RutaProyeccion m1 = ruta(2L, 1L, "300", "MJS");
    RutaProyeccion m2 = ruta(3L, 2L, "100", "MJS");
    RutaProyeccion soloLxv = ruta(4L, 2L, "900", "LXV");
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1))
        .thenReturn(List.of(diaria, m1, m2, soloLxv));

    BalanceoResponse resultado =
        servicio.balancear(10L, Balanceo.MJS, CL1, FaseBalanceo.RUTAS, null).datos();

    assertEquals(1L, diaria.getChuteId(), "la diaria fija no se mueve");
    assertEquals(2L, m1.getChuteId(), "la ruta M-J-S se reparte alrededor de la diaria");
    assertEquals(2L, soloLxv.getChuteId(), "una ruta L-X-V no aparece en M-J-S");
    verify(lotes).moverRutas(Map.of(2L, 2L));
    assertTrue(resultado.mensaje().contains("diaria"), resultado.mensaje());
    assertTrue(resultado.mensaje().contains("L-X-V"), resultado.mensaje());
    assertEquals(ParametroCluster.ESTADO_RUTAS, mjs.getEstado());
    // Rango y tramos son del cluster: los definió L-X-V y no se reescriben.
    verify(lotes, never()).guardarGrupos(anyLong(), anyLong(), anyList());
  }

  @Test
  void elPrimerBalanceoSiAcomodaLasDiarias() {
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE);
    preparar(List.of(lxv, mjs), List.of(base(1L, false), base(2L, false)), List.of());
    RutaProyeccion d1 = ruta(1L, 1L, "500", Balanceo.DIARIA);
    RutaProyeccion d2 = ruta(2L, 1L, "500", Balanceo.DIARIA);
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(List.of(d1, d2));

    servicio.balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.RUTAS, new BalanceoRequest(1L, 2L));

    assertTrue(
        d1.getChuteId().equals(2L) || d2.getChuteId().equals(2L),
        "una de las diarias pasa al chute vacío del rango");
    assertEquals(ParametroCluster.ESTADO_RUTAS, lxv.getEstado());
    assertEquals(ParametroCluster.ESTADO_PENDIENTE, mjs.getEstado());
  }

  @Test
  void laConfiguracionDiceSiLasDiariasEstanFijasYQuienDefinioElRango() {
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE);
    when(archivoRepo.findById(10L)).thenReturn(Optional.of(archivo()));
    when(configRepo.findById(10L)).thenReturn(Optional.of(configuracion()));
    prepararParametros(List.of(lxv, mjs), List.of(base(1L, true)), List.of());

    ConfiguracionSimulacionResponse cfg = servicio.obtener(10L, Balanceo.MJS).datos();

    ClusterParametroResponse cl1 = cfg.clusters().get(0);
    assertEquals("MJS", cfg.balanceo());
    assertEquals(ParametroCluster.ESTADO_PENDIENTE, cl1.estado());
    assertEquals(ParametroCluster.ESTADO_RUTAS, cl1.estadoOtro());
    assertTrue(cl1.diariasFijas());
    assertTrue(cl1.rangoDelOtro());
    assertTrue(cfg.buffBloqueado());
    assertTrue(cfg.chutes().get(0).buff());
  }

  // ---------------- A mano ----------------

  @Test
  void moverUnaDiariaAManoTambienInvalidaLosOperariosDelOtroBalanceo() {
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_LISTO);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_LISTO);
    OperarioOla olaLxv = ola(Balanceo.LXV, CL1, 1L);
    OperarioOla olaMjs = ola(Balanceo.MJS, CL1, 1L);
    preparar(List.of(lxv, mjs), List.of(base(1L, false), base(2L, false)), List.of(olaLxv, olaMjs));
    RutaProyeccion diaria = ruta(5L, 1L, "100", Balanceo.DIARIA);
    when(rutaRepo.findByIdAndArchivoId(5L, 10L)).thenReturn(Optional.of(diaria));

    servicio.mover(10L, Balanceo.LXV, 5L, new MoverRutaRequest(2L));

    assertEquals(2L, diaria.getChuteId());
    assertEquals(ParametroCluster.ESTADO_RUTAS, lxv.getEstado());
    assertEquals(ParametroCluster.ESTADO_RUTAS, mjs.getEstado());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<OperarioOla>> borradas = ArgumentCaptor.forClass(List.class);
    verify(operarioOlaRepo, times(2)).deleteAll(borradas.capture());
    List<OperarioOla> todas = new ArrayList<>();
    borradas.getAllValues().forEach(todas::addAll);
    assertTrue(todas.contains(olaLxv) && todas.contains(olaMjs));
  }

  @Test
  void unaDiariaNoSeMueveSiElOtroBalanceoCerroSuCluster() {
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_CERRADO);
    preparar(List.of(lxv, mjs), List.of(base(1L, false), base(2L, false)), List.of());
    RutaProyeccion diaria = ruta(5L, 1L, "100", Balanceo.DIARIA);
    when(rutaRepo.findByIdAndArchivoId(5L, 10L)).thenReturn(Optional.of(diaria));

    ConflictoException error =
        assertThrows(
            ConflictoException.class,
            () -> servicio.mover(10L, Balanceo.LXV, 5L, new MoverRutaRequest(2L)));

    assertTrue(error.getMessage().contains("M-J-S"), error.getMessage());
    assertEquals(1L, diaria.getChuteId());
  }

  @Test
  void unaRutaDeOtraFrecuenciaNoSeMueveDesdeEsteBalanceo() {
    preparar(
        List.of(
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false), base(2L, false)),
        List.of());
    when(rutaRepo.findByIdAndArchivoId(5L, 10L)).thenReturn(Optional.of(ruta(5L, 1L, "100", "MJS")));

    assertThrows(
        ConflictoException.class,
        () -> servicio.mover(10L, Balanceo.LXV, 5L, new MoverRutaRequest(2L)));
  }

  // ---------------- Restablecer ----------------

  @Test
  void restablecerNoDevuelveLasDiariasConLasQueCuentaElOtroBalanceo() {
    preparar(
        List.of(
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS),
            parametro(Balanceo.LXV, CL2, ParametroCluster.ESTADO_RUTAS),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_RUTAS),
            parametro(Balanceo.MJS, CL2, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false)),
        List.of());

    servicio.restablecer(10L, Balanceo.LXV);

    // CL1 ya lo trabajó M-J-S: sus diarias se quedan; las de CL2 sí vuelven.
    verify(rutaRepo).restaurarRutas(10L, "LXV", List.of(CL2));
    verify(lotes).borrarGrupos(10L, List.of(CL2));
    verify(historialRepo).borrarPorArchivo(10L, "LXV");
    verify(lanzamientoRepo).borrarPorArchivo(10L, "LXV");
  }

  // ---------------- Buff ----------------

  @Test
  void elChuteConBuffRecibeLaRutaMasPesada() {
    preparar(
        List.of(
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false), base(2L, false), base(3L, true)),
        List.of());
    RutaProyeccion pesada = ruta(1L, 1L, "900", "LXV");
    RutaProyeccion b = ruta(2L, 1L, "300", "LXV");
    RutaProyeccion c = ruta(3L, 2L, "300", "LXV");
    RutaProyeccion d = ruta(4L, 2L, "200", "LXV");
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(List.of(pesada, b, c, d));

    BalanceoResponse resultado =
        servicio
            .balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.RUTAS, new BalanceoRequest(1L, 3L))
            .datos();

    assertEquals(3L, pesada.getChuteId(), "la ruta más pesada va al chute con buff");
    assertTrue(resultado.mensaje().contains("buff"), resultado.mensaje());
  }

  @Test
  void elBuffSoloSeDefineAntesDeBalancear() {
    preparar(
        List.of(
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false)),
        List.of());

    ConflictoException error =
        assertThrows(
            ConflictoException.class,
            () ->
                servicio.guardar(
                    10L,
                    Balanceo.LXV,
                    new ConfiguracionActualizarRequest(
                        new BigDecimal("100"),
                        null,
                        null,
                        null,
                        List.of(new ChuteOperariosRequest(1L, 0, true)))));

    assertTrue(error.getMessage().contains("buff"), error.getMessage());
  }

  @Test
  void elBuffSeGuardaAntesDelPrimerBalanceo() {
    preparar(
        List.of(
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false)),
        List.of());

    ConfiguracionSimulacionResponse cfg =
        servicio
            .guardar(
                10L,
                Balanceo.LXV,
                new ConfiguracionActualizarRequest(
                    new BigDecimal("100"),
                    null,
                    null,
                    null,
                    List.of(new ChuteOperariosRequest(1L, 0, true))))
            .datos();

    assertTrue(cfg.chutes().get(0).buff());
    verify(parametroChuteRepo).save(any(ParametroChute.class));
  }

  // ---------------- Ayudas ----------------

  private void preparar(
      List<ParametroCluster> parametros, List<ParametroChute> chutes, List<OperarioOla> olas) {
    when(archivoRepo.bloquear(10L)).thenReturn(Optional.of(archivo()));
    when(configRepo.findById(10L)).thenReturn(Optional.of(configuracion()));
    prepararParametros(parametros, chutes, olas);
    lenient().when(archivoRepo.save(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
  }

  private void prepararParametros(
      List<ParametroCluster> parametros, List<ParametroChute> chutes, List<OperarioOla> olas) {
    when(parametroClusterRepo.findByIdArchivoId(10L)).thenReturn(parametros);
    when(parametroChuteRepo.findByIdArchivoId(10L)).thenReturn(chutes);
    when(operarioOlaRepo.findByIdArchivoId(10L)).thenReturn(olas);
    lenient()
        .when(clusterRepo.findAllById(any()))
        .thenReturn(List.of(cluster(CL1, "CL1"), cluster(CL2, "CL2")));
    lenient()
        .when(chuteRepo.findAllById(any()))
        .thenReturn(List.of(chute(1L, "CHUTE 1"), chute(2L, "CHUTE 2"), chute(3L, "CHUTE 3")));
  }

  private static ArchivoExcel archivo() {
    ArchivoExcel archivo = new ArchivoExcel();
    archivo.setId(10L);
    archivo.setEstado(ArchivoExcel.ESTADO_EN_PROCESO);
    return archivo;
  }

  private static SimulacionConfig configuracion() {
    SimulacionConfig config = new SimulacionConfig();
    config.setArchivoId(10L);
    config.setVelocidadBase(new BigDecimal("100"));
    return config;
  }

  private static ParametroCluster parametro(Balanceo balanceo, Long clusterId, String estado) {
    ParametroCluster parametro = new ParametroCluster();
    parametro.setId(new ParametroClusterId(10L, balanceo.name(), clusterId));
    parametro.setLimiteHoras(new BigDecimal("2"));
    parametro.setEstado(estado);
    return parametro;
  }

  private static ParametroChute base(Long chuteId, boolean buff) {
    ParametroChute parametro = new ParametroChute();
    parametro.setId(new ParametroChuteId(10L, chuteId));
    parametro.setOperarios(0);
    parametro.setBuff(buff);
    return parametro;
  }

  private static OperarioOla ola(Balanceo balanceo, Long clusterId, Long chuteId) {
    OperarioOla ola = new OperarioOla();
    ola.setId(new OperarioOlaId(10L, balanceo.name(), clusterId, chuteId));
    ola.setOperarios(2);
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

  private static RutaProyeccion ruta(Long id, Long chuteId, String bultos, String frecuencia) {
    RutaProyeccion ruta = new RutaProyeccion();
    ruta.setId(id);
    ruta.setArchivoId(10L);
    ruta.setClusterId(CL1);
    ruta.setClusterOriginalId(CL1);
    ruta.setChuteId(chuteId);
    ruta.setChuteOriginalId(chuteId);
    ruta.setCodigoRuta("R" + id);
    ruta.setGrupo("A");
    ruta.setFrecuencia(frecuencia);
    ruta.setTotalBultos(new BigDecimal(bultos));
    ruta.setTotalUnidades(new BigDecimal(bultos));
    ruta.setTotalLineas(BigDecimal.ONE);
    ruta.setConteoCod(1);
    return ruta;
  }
}
