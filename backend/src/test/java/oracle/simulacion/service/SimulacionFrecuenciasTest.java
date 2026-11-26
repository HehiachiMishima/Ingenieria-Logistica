package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
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
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.simulacion.model.ArchivoExcel;
import oracle.simulacion.model.Balanceo;
import oracle.simulacion.model.Chute;
import oracle.simulacion.model.Cluster;
import oracle.simulacion.model.HistorialBalanceo;
import oracle.simulacion.model.LanzamientoBalanceo;
import oracle.simulacion.model.OperarioOla;
import oracle.simulacion.model.OperarioOlaId;
import oracle.simulacion.model.ParametroChute;
import oracle.simulacion.model.Predeterminados;
import oracle.simulacion.model.ParametroChuteId;
import oracle.simulacion.model.ParametroCluster;
import oracle.simulacion.model.ParametroClusterId;
import oracle.simulacion.model.RutaProyeccion;
import oracle.simulacion.model.SimulacionConfig;
import oracle.simulacion.repository.ArchivoExcelRepository;
import oracle.simulacion.repository.CambioParaDeshacer;
import oracle.simulacion.repository.CargaGrupo;
import oracle.simulacion.repository.CargaRutas;
import oracle.simulacion.repository.DeltaChute;
import oracle.simulacion.repository.GrupoGuardado;
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
 * Tres balanceos por cluster: Diarias primero (reparte las diarias y define el rango y los tramos),
 * luego L-X-V y M-J-S (cada uno mueve solo sus rutas dentro de ese rango, con las diarias como carga
 * fija). Operarios con la misma cantidad en los dos días; cerrar, restablecer y el límite valen para
 * los tres. A mano todo se mueve. Chutes con buff: reciben primero la ruta más pesada de cada paso.
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
  private SimulacionHistorialService historialServicio;

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
    historialServicio =
        new SimulacionHistorialService(servicio, historialRepo, lanzamientoRepo, rutaRepo, transacciones);
    // Todos los clusters del registro tienen rutas (como con el Excel): ninguno se salta.
    lenient()
        .when(rutaRepo.clustersConRutas(org.mockito.ArgumentMatchers.anyLong()))
        .thenAnswer(
            invocacion ->
                parametroClusterRepo.findByIdArchivoId(invocacion.getArgument(0)).stream()
                    .map((p) -> p.getId().getClusterId())
                    .collect(java.util.stream.Collectors.toSet()));
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

  // ---------------- Diarias primero ----------------

  @Test
  void lasDiariasSeBalanceanPrimeroYDefinenElRango() {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_PENDIENTE);
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE);
    preparar(List.of(dia, lxv, mjs), List.of(base(1L, false), base(2L, false)), List.of());
    RutaProyeccion d1 = ruta(1L, 1L, "500", Balanceo.DIARIA);
    RutaProyeccion d2 = ruta(2L, 1L, "500", Balanceo.DIARIA);
    RutaProyeccion soloLxv = ruta(3L, 1L, "900", "LXV");
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(List.of(d1, d2, soloLxv));

    servicio.balancear(10L, Balanceo.DIA, CL1, FaseBalanceo.RUTAS, new BalanceoRequest(1L, 2L));

    assertTrue(
        d1.getChuteId().equals(2L) || d2.getChuteId().equals(2L),
        "una de las diarias pasa al chute vacío del rango");
    assertEquals(1L, soloLxv.getChuteId(), "Diarias no mueve las rutas de un día");
    assertEquals(ParametroCluster.ESTADO_RUTAS, dia.getEstado());
    assertEquals(1L, dia.getChuteDesdeId());
    assertEquals(2L, dia.getChuteHastaId());
    assertEquals(ParametroCluster.ESTADO_PENDIENTE, lxv.getEstado());
    // El rango y los tramos se guardan aquí: los días los reutilizan.
    verify(lotes).guardarGrupos(anyLong(), anyLong(), anyList());
  }

  @Test
  void unDiaEsperaQueSeBalanceenLasDiarias() {
    preparar(
        List.of(
            parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false)),
        List.of());

    ConflictoException error =
        assertThrows(
            ConflictoException.class,
            () -> servicio.balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.RUTAS, null));

    assertTrue(error.getMessage().contains("diarias"), error.getMessage());
  }

  @Test
  void lasDiariasNoSeRebalanceanSiUnDiaYaLasUsa() {
    preparar(
        List.of(
            parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS),
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false)),
        List.of());

    ConflictoException error =
        assertThrows(
            ConflictoException.class,
            () -> servicio.balancear(10L, Balanceo.DIA, CL1, FaseBalanceo.RUTAS, new BalanceoRequest(1L, 2L)));

    assertTrue(error.getMessage().contains("restablece"), error.getMessage());
  }

  @Test
  void unDiaSoloMueveSusRutasDentroDelRangoDeLasDiarias() {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS);
    dia.setChuteDesdeId(1L);
    dia.setChuteHastaId(2L);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE);
    preparar(
        List.of(dia, parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE), mjs),
        List.of(base(1L, false), base(2L, false), base(3L, false)),
        List.of());
    RutaProyeccion diaria = ruta(1L, 1L, "500", Balanceo.DIARIA);
    RutaProyeccion m1 = ruta(2L, 1L, "300", "MJS");
    RutaProyeccion m2 = ruta(3L, 3L, "100", "MJS");
    RutaProyeccion soloLxv = ruta(4L, 2L, "900", "LXV");
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1))
        .thenReturn(List.of(diaria, m1, m2, soloLxv));

    BalanceoResponse resultado =
        servicio.balancear(10L, Balanceo.MJS, CL1, FaseBalanceo.RUTAS, new BalanceoRequest(1L, 3L)).datos();

    assertEquals(1L, diaria.getChuteId(), "la diaria ya balanceada no se mueve");
    // Cada frecuencia es independiente: la diaria no cuenta en M-J-S, así que la de 300 se queda
    // en su chute (apilada con la diaria) y la otra ocupa el chute libre.
    assertEquals(1L, m1.getChuteId(), "la ruta M-J-S no se aparta de la diaria");
    assertEquals(2L, m2.getChuteId(), "la que estaba fuera del rango de las diarias entra");
    assertEquals(2L, soloLxv.getChuteId(), "una ruta L-X-V no aparece en M-J-S");
    assertTrue(resultado.mensaje().contains("diaria"), resultado.mensaje());
    assertEquals(ParametroCluster.ESTADO_RUTAS, mjs.getEstado());
    // El rango pedido no cuenta: manda el de las diarias. Los tramos no se reescriben.
    assertEquals(2L, mjs.getChuteHastaId());
    verify(lotes, never()).guardarGrupos(anyLong(), anyLong(), anyList());
  }

  @Test
  void unGrupoQueLlegoDespuesTieneSuPropioTramoEnElDia() {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS);
    dia.setChuteDesdeId(1L);
    dia.setChuteHastaId(3L);
    preparar(
        List.of(
            dia,
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false), base(2L, false), base(3L, false)),
        List.of());
    RutaProyeccion a = ruta(1L, 1L, "100", "LXV");
    RutaProyeccion b = ruta(2L, 3L, "100", "LXV");
    b.setGrupo("B");
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(List.of(a, b));
    // Solo A tiene tramo guardado (B llegó después de las diarias): el día arma los suyos igual.
    when(lotes.grupos(10L)).thenReturn(List.of(new GrupoGuardado(CL1, "A", 1, 1L, 2L)));

    servicio.balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.RUTAS, null);

    assertEquals(1L, a.getChuteId());
    assertEquals(2L, b.getChuteId(), "B tiene su tramo, pegado al de A");
    verify(lotes).moverRutas(Map.of(2L, 2L));
  }

  @Test
  void laConfiguracionTraeElAvanceDeLosTresBalanceos() {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS);
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE);
    when(archivoRepo.findById(10L)).thenReturn(Optional.of(archivo()));
    when(configRepo.findById(10L)).thenReturn(Optional.of(configuracion()));
    prepararParametros(List.of(dia, lxv, mjs), List.of(base(1L, true)), List.of());

    ConfiguracionSimulacionResponse cfg = servicio.obtener(10L, Balanceo.MJS).datos();

    ClusterParametroResponse cl1 = cfg.clusters().get(0);
    assertEquals("MJS", cfg.balanceo());
    assertEquals(ParametroCluster.ESTADO_PENDIENTE, cl1.estado());
    assertEquals(ParametroCluster.ESTADO_RUTAS, cl1.avance().diarias());
    assertEquals(ParametroCluster.ESTADO_RUTAS, cl1.avance().lxv());
    assertEquals(ParametroCluster.ESTADO_PENDIENTE, cl1.avance().mjs());
    assertTrue(cl1.diariasFijas());
    assertTrue(cfg.buffBloqueado());
    assertTrue(cfg.chutes().get(0).buff());
  }

  @Test
  void laVistaDeDiariasSoloMideLasDiariasYRecortaElRango() {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_PENDIENTE);
    when(archivoRepo.findById(10L)).thenReturn(Optional.of(archivo()));
    when(configRepo.findById(10L)).thenReturn(Optional.of(configuracion()));
    prepararParametros(
        List.of(dia, parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false), base(2L, false), base(3L, false), base(4L, false), base(5L, false)),
        List.of());
    // Grupo A: 1 diaria, 2 L-X-V y 3 M-J-S. Cada frecuencia es independiente.
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1))
        .thenReturn(
            List.of(
                ruta(1L, 1L, "50", Balanceo.DIARIA),
                ruta(2L, 1L, "500", "LXV"),
                ruta(3L, 1L, "500", "LXV"),
                ruta(4L, 1L, "300", "MJS"),
                ruta(5L, 1L, "300", "MJS"),
                ruta(6L, 1L, "300", "MJS")));

    var vista = servicio.vistaBalanceo(10L, Balanceo.DIA, CL1, new BalanceoRequest(1L, 5L)).datos();

    assertEquals(1, vista.grupos().get(0).rutas(), "solo las diarias");
    assertEquals(0, new BigDecimal("50").compareTo(vista.grupos().get(0).bultos()));
    assertEquals(1, vista.grupos().get(0).chutes());
    // La frecuencia con más rutas (M-J-S, 3) llena 3 de los 5 chutes: sobran 2.
    assertEquals(2, vista.chutesSinUsar());
  }

  // ---------------- A mano ----------------

  @Test
  void moverUnaDiariaAManoInvalidaLosOperariosDeLosDosDiasYQuedaEnElHistorialDeDiarias() {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS);
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_LISTO);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_LISTO);
    OperarioOla olaLxv = ola(Balanceo.LXV, CL1, 1L);
    OperarioOla olaMjs = ola(Balanceo.MJS, CL1, 1L);
    preparar(List.of(dia, lxv, mjs), List.of(base(1L, false), base(2L, false)), List.of(olaLxv, olaMjs));
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
    ArgumentCaptor<HistorialBalanceo> registro = ArgumentCaptor.forClass(HistorialBalanceo.class);
    verify(historialRepo).save(registro.capture());
    assertEquals("DIA", registro.getValue().getBalanceo(), "el cambio de una diaria es de Diarias");
  }

  @Test
  void unaDiariaNoSeMueveSiSuClusterEstaCerrado() {
    preparar(
        List.of(
            parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_CERRADO),
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_CERRADO),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_CERRADO)),
        List.of(base(1L, false), base(2L, false)),
        List.of());
    RutaProyeccion diaria = ruta(5L, 1L, "100", Balanceo.DIARIA);
    when(rutaRepo.findByIdAndArchivoId(5L, 10L)).thenReturn(Optional.of(diaria));

    ConflictoException error =
        assertThrows(
            ConflictoException.class,
            () -> servicio.mover(10L, Balanceo.DIA, 5L, new MoverRutaRequest(2L)));

    assertTrue(error.getMessage().contains("cerrado"), error.getMessage());
    assertEquals(1L, diaria.getChuteId());
  }

  @Test
  void unaRutaDeOtraFrecuenciaNoSeMueveDesdeEsteBalanceo() {
    preparar(
        List.of(
            parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false), base(2L, false)),
        List.of());
    RutaProyeccion parcial = ruta(5L, 1L, "100", "MJS");
    parcial.setDias("M-J");
    when(rutaRepo.findByIdAndArchivoId(5L, 10L)).thenReturn(Optional.of(parcial));

    ConflictoException error =
        assertThrows(
            ConflictoException.class,
            () -> servicio.mover(10L, Balanceo.DIA, 5L, new MoverRutaRequest(2L)));

    assertTrue(error.getMessage().contains("M-J"), "dice sus días reales: " + error.getMessage());
  }

  // ---------------- Restablecer ----------------

  @Test
  void restablecerTodoDevuelveLosTresBalanceosEnUnaSolaOperacion() {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_CERRADO);
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_CERRADO);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_CERRADO);
    lxv.setOperariosOla(4);
    preparar(List.of(dia, lxv, mjs), List.of(base(1L, false)), List.of());
    ArchivoExcel archivo = archivo();
    when(archivoRepo.bloquear(10L)).thenReturn(Optional.of(archivo));

    servicio.restablecer(10L);

    verify(rutaRepo).restaurarTodas(10L);
    verify(lanzamientoRepo).borrarTodoPorArchivo(10L);
    verify(historialRepo).borrarTodoPorArchivo(10L);
    verify(lotes).borrarGrupos(10L, Set.of(CL1));
    for (ParametroCluster p : List.of(dia, lxv, mjs)) {
      assertEquals(ParametroCluster.ESTADO_PENDIENTE, p.getEstado());
    }
    assertEquals(null, lxv.getOperariosOla());
    assertEquals(ArchivoExcel.ESTADO_PROYECCION, archivo.getEstado());
  }

  @Test
  void restablecerElClusterLoDevuelveEnLosTresBalanceos() {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS);
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_RUTAS);
    preparar(List.of(dia, lxv, mjs), List.of(base(1L, false)), List.of());
    when(historialRepo.trabajoParaDeshacerAmbos(10L, List.of(CL1)))
        .thenReturn(List.of(deshacer(9L, 1L)));

    servicio.restablecerCluster(10L, Balanceo.MJS, CL1);

    for (ParametroCluster p : List.of(dia, lxv, mjs)) {
      assertEquals(ParametroCluster.ESTADO_PENDIENTE, p.getEstado());
    }
    verify(lotes).moverRutas(Map.of(9L, 1L));
    verify(historialRepo).borrarManualesDeTrabajoAmbos(10L, List.of(CL1));
    verify(lanzamientoRepo).borrarPorClusters(10L, List.of(CL1));
    verify(lotes).quitarTramos(10L, List.of(CL1));
  }

  // ---------------- Buff ----------------

  @Test
  void elChuteConBuffRecibeLaRutaMasPesadaDeCadaPaso() {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS);
    dia.setChuteDesdeId(1L);
    dia.setChuteHastaId(3L);
    preparar(
        List.of(
            dia,
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
        servicio.balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.RUTAS, null).datos();

    assertEquals(3L, pesada.getChuteId(), "la ruta más pesada va al chute con buff");
    assertTrue(resultado.mensaje().contains("Buff CHUTE 3: grupo A (900 bultos)"), resultado.mensaje());
  }

  /** Diarias de CL1 ya balanceadas en CHUTE 1 → 3; L-X-V y M-J-S pendientes; buff donde se pida. */
  private void prepararBuff(boolean buff1, boolean buff2, boolean buff3) {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS);
    dia.setChuteDesdeId(1L);
    dia.setChuteHastaId(3L);
    preparar(
        List.of(
            dia,
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, buff1), base(2L, buff2), base(3L, buff3)),
        List.of());
  }

  @Test
  void primeroSeBalanceaPorGrupoYDespuesElBuffTomaLaColumnaMasCargada() {
    prepararBuff(false, false, true);
    String[] bultos = {"500", "450", "400", "300", "250", "200", "150", "150", "100", "100", "50", "50"};
    List<RutaProyeccion> rutas = new java.util.ArrayList<>();
    for (int i = 0; i < bultos.length; i++) {
      rutas.add(ruta((long) i + 1, i % 2 == 0 ? 1L : 2L, bultos[i], "LXV"));
    }
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(rutas);

    BalanceoResponse resultado =
        servicio.balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.RUTAS, null).datos();

    // 2.700 bultos en 3 chutes: quedan parejos (900 cada uno) y el buff tiene uno de ellos.
    Map<Long, BigDecimal> carga = new java.util.TreeMap<>();
    rutas.forEach((r) -> carga.merge(r.getChuteId(), r.getTotalBultos(), BigDecimal::add));
    carga.values().forEach((c) -> assertEquals(0, new BigDecimal("900").compareTo(c), carga.toString()));
    assertTrue(resultado.mensaje().contains("Buff CHUTE 3: grupo A (900 bultos)"), resultado.mensaje());
  }

  @Test
  void laRutaMasPesadaTerminaEnElBuffYNingunChuteQuedaVacio() {
    prepararBuff(false, false, true);
    // Todo en CHUTE 1 y la más pesada fuera del tramo (CHUTE 5): el buff la toma directo.
    RutaProyeccion fuera = ruta(1L, 5L, "800", "LXV");
    List<RutaProyeccion> rutas =
        List.of(fuera, ruta(2L, 1L, "300", "LXV"), ruta(3L, 1L, "250", "LXV"), ruta(4L, 1L, "200", "LXV"));
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(rutas);

    BalanceoResponse resultado =
        servicio.balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.RUTAS, null).datos();

    assertEquals(3L, fuera.getChuteId(), "la más pesada, aunque esté fuera, va al buff");
    for (Long chute : List.of(1L, 2L, 3L)) {
      assertTrue(rutas.stream().anyMatch((r) -> r.getChuteId().equals(chute)), "CHUTE " + chute + " sin rutas");
    }
    assertTrue(resultado.mensaje().contains("1 desde chutes fuera del rango"), resultado.mensaje());
    assertTrue(resultado.mensaje().contains("Buff CHUTE 3: grupo A (800 bultos)"), resultado.mensaje());
  }

  @Test
  void entreDosBuffNoQuedanHuecos() {
    // Como CL4 M-J-S: buff en el 2.º y el último chute del tramo y menos rutas que chutes.
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS);
    dia.setChuteDesdeId(1L);
    dia.setChuteHastaId(5L);
    preparar(
        List.of(
            dia,
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false), base(2L, true), base(3L, false), base(4L, false), base(5L, true)),
        List.of());
    RutaProyeccion r1000 = ruta(1L, 1L, "1000", "LXV");
    RutaProyeccion r800 = ruta(2L, 1L, "800", "LXV");
    RutaProyeccion r300 = ruta(3L, 1L, "300", "LXV");
    RutaProyeccion r200 = ruta(4L, 1L, "200", "LXV");
    List<RutaProyeccion> rutas = List.of(r1000, r800, r300, r200);
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(rutas);

    BalanceoResponse resultado =
        servicio.balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.RUTAS, null).datos();

    assertEquals(2L, r1000.getChuteId(), "la más pesada al primer buff");
    assertEquals(5L, r800.getChuteId(), "la segunda al otro buff");
    // Las otras dos llenan el 3 y el 4 (entre los buff); el libre es el 1, fuera del bloque.
    assertEquals(Set.of(3L, 4L), Set.of(r300.getChuteId(), r200.getChuteId()));
    assertTrue(resultado.mensaje().contains("1 chute(s) del rango quedan fuera de este día"), resultado.mensaje());
  }

  /** Chutes 15 → 29 del registro real (19, 20 y 21 bloqueados): buff en el 18 y el 25. */
  private static final List<Long> TRAMO_REAL = List.of(15L, 16L, 17L, 18L, 22L, 23L, 24L, 25L, 26L, 27L, 28L, 29L);

  /** Rango de las diarias 15 → 29, ya balanceadas; el día pendiente. Devuelve la carga por chute. */
  private Map<Long, BigDecimal> balancearReal(Balanceo dia, List<RutaProyeccion> rutas) {
    ParametroCluster diarias = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS);
    diarias.setChuteDesdeId(15L);
    diarias.setChuteHastaId(29L);
    preparar(
        List.of(
            diarias,
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        TRAMO_REAL.stream().map((id) -> base(id, id == 18L || id == 25L)).toList(),
        List.of());
    when(chuteRepo.findAllById(any()))
        .thenReturn(TRAMO_REAL.stream().map((id) -> chute(id, String.valueOf(id))).toList());
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(rutas);
    servicio.balancear(10L, dia, CL1, FaseBalanceo.RUTAS, null);
    Map<Long, BigDecimal> carga = new java.util.TreeMap<>();
    rutas.forEach((r) -> carga.merge(r.getChuteId(), r.getTotalBultos(), BigDecimal::add));
    return carga;
  }

  @Test
  void datoRealCl4ElRangoSoloPierdeLosChutesQueNingunDiaLlena() {
    // CL4 sin diarias: L-X-V 10 rutas, M-J-S 5. Del rango pedido 15 → 29 sobran el 28 y el 29.
    ParametroCluster diarias = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_PENDIENTE);
    preparar(
        List.of(
            diarias,
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        TRAMO_REAL.stream().map((id) -> base(id, id == 18L || id == 25L)).toList(),
        List.of());
    when(chuteRepo.findAllById(any()))
        .thenReturn(TRAMO_REAL.stream().map((id) -> chute(id, String.valueOf(id))).toList());
    String[][] lxv = {
      {"15", "350.72"}, {"17", "614.59"}, {"18", "445.72"}, {"24", "1054.35"}, {"25", "628.32"},
      {"26", "565.08"}, {"27", "134.79"}, {"27", "149.96"}, {"27", "59.16"}, {"28", "1246.69"}
    };
    String[][] mjs = {{"16", "405.58"}, {"17", "559.99"}, {"18", "1172.81"}, {"27", "638.58"}, {"29", "920.74"}};
    List<RutaProyeccion> rutas = new ArrayList<>();
    for (String[] r : lxv) {
      rutas.add(ruta(rutas.size() + 1L, Long.valueOf(r[0]), r[1], "LXV"));
    }
    for (String[] r : mjs) {
      rutas.add(ruta(rutas.size() + 1L, Long.valueOf(r[0]), r[1], "MJS"));
    }
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(rutas);

    BalanceoResponse resultado =
        servicio.balancear(10L, Balanceo.DIA, CL1, FaseBalanceo.RUTAS, new BalanceoRequest(15L, 29L)).datos();

    assertEquals(15L, diarias.getChuteDesdeId());
    assertEquals(27L, diarias.getChuteHastaId());
    assertTrue(resultado.mensaje().contains("Rango usado: 10 de 12 chutes"), resultado.mensaje());
  }

  /** Registro «principal», CL3: rango 15 → 26 (sin 19-21), buff en 18, 22 y 25, 6 grupos. */
  private static final List<Long> RANGO_PRINCIPAL = List.of(15L, 16L, 17L, 18L, 22L, 23L, 24L, 25L, 26L);

  private List<RutaProyeccion> rutasPrincipal() {
    String[][] datos = {
      {"1", "DIARIA", "15", "465.05"}, {"1", "DIARIA", "15", "197.49"},
      {"1", "LXV", "15", "145.11"}, {"1", "LXV", "15", "380.53"}, {"1", "MJS", "15", "556.61"},
      {"2", "MJS", "23", "785.12"}, {"2", "MJS", "24", "611.6"}, {"2", "MJS", "24", "662"}, {"2", "MJS", "25", "878.19"},
      {"3", "LXV", "16", "455.45"}, {"3", "LXV", "16", "360.48"}, {"3", "LXV", "17", "217.22"},
      {"3", "LXV", "17", "270.2"}, {"3", "LXV", "17", "338.15"},
      {"4", "LXV", "18", "460.44"}, {"4", "LXV", "18", "66.41"}, {"4", "LXV", "18", "70.53"}, {"4", "LXV", "18", "96.81"},
      {"5", "MJS", "22", "1531.63"},
      {"6", "LXV", "26", "784.49"}
    };
    List<RutaProyeccion> rutas = new ArrayList<>();
    for (String[] d : datos) {
      RutaProyeccion r = ruta(rutas.size() + 1L, Long.valueOf(d[2]), d[3], d[1]);
      r.setGrupo(d[0]);
      rutas.add(r);
    }
    return rutas;
  }

  /** Diarias de CL3 ya balanceadas en 15 → 26; el día pendiente. Devuelve la carga del día por chute. */
  private Map<Long, BigDecimal> balancearPrincipal(Balanceo dia, List<RutaProyeccion> rutas) {
    ParametroCluster diarias = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS);
    diarias.setChuteDesdeId(15L);
    diarias.setChuteHastaId(26L);
    preparar(
        List.of(
            diarias,
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        RANGO_PRINCIPAL.stream().map((id) -> base(id, id == 18L || id == 22L || id == 25L)).toList(),
        List.of());
    when(chuteRepo.findAllById(any()))
        .thenReturn(RANGO_PRINCIPAL.stream().map((id) -> chute(id, String.valueOf(id))).toList());
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(rutas);
    servicio.balancear(10L, dia, CL1, FaseBalanceo.RUTAS, null);
    Map<Long, BigDecimal> carga = new java.util.TreeMap<>();
    rutas.stream()
        .filter((r) -> dia.incluye(r.getFrecuencia()))
        .forEach((r) -> carga.merge(r.getChuteId(), r.getTotalBultos(), BigDecimal::add));
    return carga;
  }

  private static RutaProyeccion conBultos(List<RutaProyeccion> rutas, String bultos) {
    return rutas.stream()
        .filter((r) -> r.getTotalBultos().compareTo(new BigDecimal(bultos)) == 0)
        .findFirst()
        .orElseThrow();
  }

  /** Chutes que usan las rutas de esa frecuencia (sin las diarias), en el orden del rango. */
  private static List<Long> usadosPor(List<RutaProyeccion> rutas, String frecuencia) {
    return RANGO_PRINCIPAL.stream()
        .filter((c) -> rutas.stream().anyMatch((r) -> frecuencia.equals(r.getFrecuencia()) && c.equals(r.getChuteId())))
        .toList();
  }

  @Test
  void datoRealPrincipalLxvUsaTodoElRangoYElGrupoMayorTieneBuff() {
    List<RutaProyeccion> rutas = rutasPrincipal();

    balancearPrincipal(Balanceo.LXV, rutas);

    // Solo las 12 rutas de L-X-V (las diarias no cuentan): llenan los 9 chutes.
    assertEquals(RANGO_PRINCIPAL, usadosPor(rutas, "LXV"));
    // El G3 (1.641,50) es el grupo con más bultos: su chute más cargado es un buff. La de 784,49,
    // sola en su chute, también queda en un buff.
    Map<Long, BigDecimal> g3 = new java.util.HashMap<>();
    rutas.stream()
        .filter((r) -> "LXV".equals(r.getFrecuencia()) && "3".equals(r.getGrupo()))
        .forEach((r) -> g3.merge(r.getChuteId(), r.getTotalBultos(), BigDecimal::add));
    Long masCargado = g3.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    assertTrue(Set.of(18L, 22L, 25L).contains(masCargado), g3.toString());
    assertTrue(Set.of(18L, 22L, 25L).contains(conBultos(rutas, "784.49").getChuteId()));
    assertEquals(15L, conBultos(rutas, "465.05").getChuteId(), "las diarias no se mueven");
  }

  @Test
  void datoRealPrincipalMjsLlevaLasMasPesadasALosBuffSinHuecos() {
    List<RutaProyeccion> rutas = rutasPrincipal();

    balancearPrincipal(Balanceo.MJS, rutas);

    // 6 rutas de M-J-S: 6 chutes seguidos, sin huecos; los demás quedan fuera de M-J-S.
    List<Long> usados = usadosPor(rutas, "MJS");
    int desde = RANGO_PRINCIPAL.indexOf(usados.getFirst());
    assertEquals(RANGO_PRINCIPAL.subList(desde, desde + 6), usados);
    // Sin diarias que estorben, la de 1.531,63 y la más pesada del G2 (el mayor) van a buff.
    assertTrue(Set.of(18L, 22L, 25L).contains(conBultos(rutas, "1531.63").getChuteId()));
    assertTrue(Set.of(18L, 22L, 25L).contains(conBultos(rutas, "878.19").getChuteId()));
    assertEquals(15L, conBultos(rutas, "465.05").getChuteId(), "las diarias no se mueven");
  }

  @Test
  void datoRealCl4LxvLlenaElTramoSinHuecos() {
    // CL4 L-X-V tal como está hoy (pendiente): 10 rutas para 12 chutes, la de 1.246,69 en el 28.
    String[][] datos = {
      {"15", "350.72"}, {"17", "614.59"}, {"18", "445.72"}, {"24", "1054.35"}, {"25", "628.32"},
      {"26", "565.08"}, {"27", "134.79"}, {"27", "149.96"}, {"27", "59.16"}, {"28", "1246.69"}
    };
    List<RutaProyeccion> rutas = new ArrayList<>();
    for (int i = 0; i < datos.length; i++) {
      rutas.add(ruta(i + 1L, Long.valueOf(datos[i][0]), datos[i][1], "LXV"));
    }

    Map<Long, BigDecimal> carga = balancearReal(Balanceo.LXV, rutas);

    assertEquals(18L, rutas.get(9).getChuteId(), "1.246,69 al primer buff");
    assertEquals(25L, rutas.get(3).getChuteId(), "1.054,35 al segundo buff");
    // 10 rutas: 10 chutes con una ruta cada uno, en bloque; solo 28 y 29 libres.
    assertEquals(List.of(15L, 16L, 17L, 18L, 22L, 23L, 24L, 25L, 26L, 27L), List.copyOf(carga.keySet()), carga.toString());
  }

  @Test
  void datoRealCl4MjsUsaUnBloqueContinuoEntreLosBuff() {
    String[][] datos = {{"16", "405.58"}, {"17", "559.99"}, {"18", "1172.81"}, {"27", "638.58"}, {"29", "920.74"}};
    List<RutaProyeccion> rutas = new ArrayList<>();
    for (int i = 0; i < datos.length; i++) {
      rutas.add(ruta(i + 1L, Long.valueOf(datos[i][0]), datos[i][1], "MJS"));
    }

    Map<Long, BigDecimal> carga = balancearReal(Balanceo.MJS, rutas);

    assertEquals(18L, rutas.get(2).getChuteId(), "1.172,81 al primer buff");
    assertEquals(25L, rutas.get(4).getChuteId(), "920,74 al segundo buff");
    // 5 rutas para 12 chutes: se usan 5 seguidos (18 → 25), ninguno vacío entre ellos; los demás
    // quedan fuera de este día.
    assertEquals(List.of(18L, 22L, 23L, 24L, 25L), List.copyOf(carga.keySet()), carga.toString());
  }

  @Test
  void laNivelacionIntercambiaRutasCuandoMoverUnaSolaNoAlcanza() {
    prepararBuff(false, false, false);
    // Como CL1 grupo 1: ninguna ruta del chute más cargado pesa menos que la diferencia, así que
    // mover una sola no mejora (antes quedaba en 454,50 · 336,68 · 329,33: 33 %).
    List<RutaProyeccion> rutas =
        List.of(
            ruta(1L, 1L, "262.83", "LXV"),
            ruta(2L, 1L, "191.67", "LXV"),
            ruta(3L, 2L, "171.31", "LXV"),
            ruta(4L, 2L, "165.37", "LXV"),
            ruta(5L, 3L, "200.34", "LXV"),
            ruta(6L, 3L, "128.99", "LXV"));
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(rutas);

    servicio.balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.RUTAS, null);

    Map<Long, BigDecimal> carga = new java.util.HashMap<>();
    rutas.forEach((r) -> carga.merge(r.getChuteId(), r.getTotalBultos(), BigDecimal::add));
    BigDecimal mayor = carga.values().stream().max(BigDecimal::compareTo).orElseThrow();
    BigDecimal menor = carga.values().stream().min(BigDecimal::compareTo).orElseThrow();
    // Promedio 373,50: queda dentro de la tolerancia del 10 % (≤ 37,35 bultos de diferencia).
    assertEquals(3, carga.size(), "los tres chutes con rutas");
    assertTrue(mayor.subtract(menor).compareTo(new BigDecimal("37.35")) <= 0, carga.toString());
  }

  // ---------------- Buff: cada frecuencia por su cuenta ----------------

  /** Diarias pendientes en chutes 1 → n; buff donde se pida. Devuelve el parámetro de diarias. */
  private ParametroCluster prepararDiarias(int chutes, Set<Long> buff, List<RutaProyeccion> rutas) {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_PENDIENTE);
    List<Long> ids = java.util.stream.LongStream.rangeClosed(1, chutes).boxed().toList();
    preparar(
        List.of(
            dia,
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        ids.stream().map((id) -> base(id, buff.contains(id))).toList(),
        List.of());
    when(chuteRepo.findAllById(any()))
        .thenReturn(ids.stream().map((id) -> chute(id, "CHUTE " + id)).toList());
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(rutas);
    return dia;
  }

  @Test
  void lasDiariasUsanElBuffSinMirarLasRutasDeLosDias() {
    // Aunque M-J-S tenga una ruta de 1.531, las diarias no la miran: la de 465 va al buff (CHUTE 2).
    RutaProyeccion d465 = ruta(1L, 2L, "465.05", Balanceo.DIARIA);
    RutaProyeccion d197 = ruta(2L, 2L, "197.49", Balanceo.DIARIA);
    List<RutaProyeccion> rutas =
        List.of(d465, d197, ruta(3L, 3L, "784.49", "LXV"), ruta(4L, 3L, "1531.63", "MJS"));
    prepararDiarias(3, Set.of(2L), rutas);

    BalanceoResponse resultado =
        servicio.balancear(10L, Balanceo.DIA, CL1, FaseBalanceo.RUTAS, new BalanceoRequest(1L, 3L)).datos();

    assertEquals(2L, d465.getChuteId(), "la diaria más pesada al buff");
    assertTrue(!resultado.mensaje().contains("reservado"), resultado.mensaje());
    // Ninguna frecuencia tiene más de 2 rutas: el rango queda en 2 chutes, con el buff.
    assertTrue(resultado.mensaje().contains("Rango usado: 2 de 3 chutes"), resultado.mensaje());
  }

  @Test
  void laDiariaMasPesadaVaAlBuff() {
    RutaProyeccion d900 = ruta(1L, 1L, "900", Balanceo.DIARIA);
    RutaProyeccion d100 = ruta(2L, 1L, "100", Balanceo.DIARIA);
    List<RutaProyeccion> rutas =
        List.of(d900, d100, ruta(3L, 3L, "300", "LXV"), ruta(4L, 3L, "200", "MJS"));
    prepararDiarias(3, Set.of(2L), rutas);

    BalanceoResponse resultado =
        servicio.balancear(10L, Balanceo.DIA, CL1, FaseBalanceo.RUTAS, new BalanceoRequest(1L, 3L)).datos();

    assertEquals(2L, d900.getChuteId(), "la diaria más pesada al buff");
    assertTrue(!resultado.mensaje().contains("reservado"), resultado.mensaje());
  }

  @Test
  void elRangoRecortadoSeQuedaConElBuff() {
    // Buff en el último chute (5) y ninguna frecuencia con más de 2 rutas: el rango queda en 4-5
    // y la diaria más pesada va al buff.
    RutaProyeccion d300 = ruta(1L, 1L, "300", Balanceo.DIARIA);
    RutaProyeccion d250 = ruta(2L, 1L, "250", Balanceo.DIARIA);
    List<RutaProyeccion> rutas =
        List.of(d300, d250, ruta(3L, 1L, "800", "LXV"), ruta(4L, 1L, "700", "MJS"));
    ParametroCluster dia = prepararDiarias(5, Set.of(5L), rutas);

    servicio.balancear(10L, Balanceo.DIA, CL1, FaseBalanceo.RUTAS, new BalanceoRequest(1L, 5L));

    assertEquals(5L, d300.getChuteId());
    assertEquals(4L, d250.getChuteId());
    assertEquals(4L, dia.getChuteDesdeId());
    assertEquals(5L, dia.getChuteHastaId());
  }

  @Test
  void unDiaUsaElBuffAunqueTengaUnaDiariaDebajo() {
    // L-X-V no mira las diarias: la de 800 va al buff 3 y se apila sobre la diaria fija.
    prepararBuff(false, false, true);
    RutaProyeccion diaria = ruta(1L, 3L, "300", Balanceo.DIARIA);
    RutaProyeccion r800 = ruta(2L, 1L, "800", "LXV");
    List<RutaProyeccion> rutas = List.of(diaria, r800, ruta(3L, 1L, "250", "LXV"), ruta(4L, 2L, "200", "LXV"));
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(rutas);

    BalanceoResponse resultado =
        servicio.balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.RUTAS, null).datos();

    assertEquals(3L, diaria.getChuteId(), "la diaria fija no se mueve");
    assertEquals(3L, r800.getChuteId(), "la más pesada del día al buff");
    assertTrue(resultado.mensaje().contains("Buff CHUTE 3: grupo A (800 bultos)"), resultado.mensaje());
  }

  @Test
  void conMasChutesConBuffQueRutasElBuffSobranteQuedaLibreNoVacio() {
    prepararBuff(false, true, true);
    RutaProyeccion unica = ruta(1L, 1L, "300", "LXV");
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(List.of(unica));

    BalanceoResponse resultado =
        servicio.balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.RUTAS, null).datos();

    assertEquals(2L, unica.getChuteId(), "va al primer buff del tramo");
    // CHUTE 3 (buff) y CHUTE 1 no tienen ruta para ese día: salen del tramo en vez de quedar vacíos.
    assertTrue(resultado.mensaje().contains("2 chute(s) del rango quedan fuera de este día"), resultado.mensaje());
  }

  @Test
  void conPocasRutasElBuffNoSeRecortaYRecibeLaMasPesada() {
    prepararBuff(false, false, true);
    // Una sola ruta para tres chutes: antes se quedaba en CHUTE 1 y el buff (al final) quedaba libre.
    RutaProyeccion unica = ruta(1L, 1L, "300", "LXV");
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(List.of(unica));

    BalanceoResponse resultado =
        servicio.balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.RUTAS, null).datos();

    assertEquals(3L, unica.getChuteId(), "el buff nunca queda libre: recibe la ruta más pesada");
    assertTrue(resultado.mensaje().contains("Buff CHUTE 3"), resultado.mensaje());
  }

  @Test
  void dosChutesConBuffSeTurnanLasRutasMasPesadas() {
    prepararBuff(false, true, true);
    RutaProyeccion r600 = ruta(1L, 1L, "600", "LXV");
    RutaProyeccion r500 = ruta(2L, 1L, "500", "LXV");
    RutaProyeccion r400 = ruta(3L, 1L, "400", "LXV");
    List<RutaProyeccion> rutas =
        List.of(r600, r500, r400, ruta(4L, 1L, "100", "LXV"), ruta(5L, 1L, "100", "LXV"), ruta(6L, 1L, "100", "LXV"));
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(rutas);

    servicio.balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.RUTAS, null);

    // Tres columnas de 600: los dos buff reciben la de 600 y la de 500 + 100; la de 400 + 100 + 100
    // se queda en CHUTE 1, donde ya estaban (mueve menos).
    assertEquals(2L, r600.getChuteId());
    assertEquals(3L, r500.getChuteId());
    assertEquals(1L, r400.getChuteId());
  }

  @Test
  void elBuffDelDiaEsDelGrupoConMasBultos() {
    prepararBuff(false, true, false);
    // Los tramos de las diarias no mandan en el día: cada frecuencia arma los suyos.
    when(lotes.grupos(10L))
        .thenReturn(List.of(new GrupoGuardado(CL1, "A", 1, 1L, 2L), new GrupoGuardado(CL1, "B", 2, 3L, 3L)));
    RutaProyeccion a100 = ruta(1L, 1L, "100", "LXV");
    RutaProyeccion a50 = ruta(2L, 1L, "50", "LXV");
    RutaProyeccion b900 = ruta(3L, 3L, "900", "LXV");
    RutaProyeccion b800 = ruta(4L, 3L, "800", "LXV");
    b900.setGrupo("B");
    b800.setGrupo("B");
    when(rutaRepo.findByArchivoIdAndClusterIdOrderByIdAsc(10L, CL1)).thenReturn(List.of(a100, a50, b900, b800));

    servicio.balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.RUTAS, null);

    // B suma 1.700 bultos (más que A): el buff (CHUTE 2) es de B, con su columna más cargada, y
    // su tramo lo rodea (2 → 3).
    assertEquals(2L, b900.getChuteId(), "la más pesada del día va al buff");
    assertEquals(3L, b800.getChuteId());
    assertEquals(1L, a100.getChuteId(), "A queda en su propio tramo, sin mezclarse con B");
    assertEquals(1L, a50.getChuteId());
  }

  @Test
  void elBuffSoloSeDefineAntesDeBalancearLasDiarias() {
    preparar(
        List.of(
            parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS),
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
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
  void conLosPredeterminadosBloqueadosNoSeCambiaLaVelocidadNiElBuff() {
    preparar(
        List.of(
            parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false)),
        List.of());
    when(lotes.leerPredeterminados())
        .thenReturn(new Predeterminados(new BigDecimal("100"), List.of(), true));

    ConflictoException velocidad =
        assertThrows(
            ConflictoException.class,
            () ->
                servicio.guardar(
                    10L,
                    Balanceo.LXV,
                    new ConfiguracionActualizarRequest(new BigDecimal("120"), null, null, null, List.of())));
    ConflictoException buff =
        assertThrows(
            ConflictoException.class,
            () ->
                servicio.guardar(
                    10L,
                    Balanceo.LXV,
                    new ConfiguracionActualizarRequest(
                        new BigDecimal("100"), null, null, null, List.of(new ChuteOperariosRequest(1L, 0, true)))));

    assertTrue(velocidad.getMessage().contains("bloqueados"), velocidad.getMessage());
    assertTrue(buff.getMessage().contains("bloqueados"), buff.getMessage());
    verify(parametroChuteRepo, never()).save(any(ParametroChute.class));
  }

  @Test
  void conLosPredeterminadosBloqueadosLoDemasSiSeGuarda() {
    preparar(
        List.of(
            parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false)),
        List.of());
    when(lotes.leerPredeterminados())
        .thenReturn(new Predeterminados(new BigDecimal("100"), List.of(), true));

    // Misma velocidad y mismo buff: el máximo por chute sí cambia.
    ConfiguracionSimulacionResponse cfg =
        servicio
            .guardar(
                10L,
                Balanceo.LXV,
                new ConfiguracionActualizarRequest(
                    new BigDecimal("100.00"), null, 5, null, List.of(new ChuteOperariosRequest(1L, 0, false))))
            .datos();

    assertFalse(cfg.chutes().get(0).buff());
  }

  @Test
  void elBuffSeGuardaAntesDelPrimerBalanceo() {
    preparar(
        List.of(
            parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_PENDIENTE),
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

  // ---------------- Pasos compartidos ----------------

  @Test
  void losOperariosSeRepartenEnCadaDiaSegunSuCargaConLaMismaCantidad() {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS);
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_RUTAS);
    preparar(List.of(dia, lxv, mjs), List.of(base(1L, false), base(2L, false)), List.of());
    // L-X-V carga solo el chute 1; M-J-S, solo el chute 2 (las diarias van en los dos días).
    when(rutaRepo.cargaPorArchivo(10L))
        .thenReturn(List.of(carga("LXV", CL1, 1L, "400"), carga("MJS", CL1, 2L, "400")));

    BalanceoResponse resultado =
        servicio
            .balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.OPERARIOS, BalanceoRequest.conOperarios(4))
            .datos();

    assertEquals(ParametroCluster.ESTADO_LISTO, lxv.getEstado());
    assertEquals(ParametroCluster.ESTADO_LISTO, mjs.getEstado());
    assertEquals(4, lxv.getOperariosOla());
    assertEquals(4, mjs.getOperariosOla());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<OperarioOla>> guardadas = ArgumentCaptor.forClass(List.class);
    verify(operarioOlaRepo, times(2)).saveAll(guardadas.capture());
    OperarioOla delLxv = guardadas.getAllValues().get(0).getFirst();
    OperarioOla delMjs = guardadas.getAllValues().get(1).getFirst();
    assertEquals("LXV", delLxv.getId().getBalanceo());
    assertEquals(1L, delLxv.getId().getChuteId(), "L-X-V pone su gente donde está su carga");
    assertEquals("MJS", delMjs.getId().getBalanceo());
    assertEquals(2L, delMjs.getId().getChuteId(), "M-J-S, en el suyo");
    assertTrue(resultado.mensaje().contains("L-X-V") && resultado.mensaje().contains("M-J-S"));
  }

  @Test
  void losOperariosEsperanLosChutesDeLosDosDias() {
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS);
    preparar(
        List.of(
            parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS),
            lxv,
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false)),
        List.of());

    ConflictoException error =
        assertThrows(
            ConflictoException.class,
            () ->
                servicio.balancear(
                    10L, Balanceo.LXV, CL1, FaseBalanceo.OPERARIOS, BalanceoRequest.conOperarios(4)));

    assertTrue(error.getMessage().contains("M-J-S"), error.getMessage());
    assertEquals(ParametroCluster.ESTADO_RUTAS, lxv.getEstado());
  }

  @Test
  void unErrorDeOperariosDiceDeQueDiaViene() {
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_RUTAS);
    mjs.setLimiteHoras(BigDecimal.ZERO);
    preparar(
        List.of(parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS), lxv, mjs),
        List.of(base(1L, false)),
        List.of());

    ConflictoException error =
        assertThrows(
            ConflictoException.class,
            () ->
                servicio.balancear(
                    10L, Balanceo.LXV, CL1, FaseBalanceo.OPERARIOS, BalanceoRequest.conOperarios(4)));

    assertTrue(error.getMessage().startsWith("M-J-S: "), error.getMessage());
  }

  @Test
  void cerrarCierraLosTresYSoloConTodoListo() {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS);
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_LISTO);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_RUTAS);
    preparar(List.of(dia, lxv, mjs), List.of(base(1L, false)), List.of());

    assertThrows(ConflictoException.class, () -> servicio.cerrar(10L, Balanceo.LXV, CL1));
    assertEquals(ParametroCluster.ESTADO_LISTO, lxv.getEstado());

    mjs.setEstado(ParametroCluster.ESTADO_LISTO);
    servicio.cerrar(10L, Balanceo.DIA, CL1);

    for (ParametroCluster p : List.of(dia, lxv, mjs)) {
      assertEquals(ParametroCluster.ESTADO_CERRADO, p.getEstado());
    }
  }

  // ---------------- Días sin rutas propias ----------------

  @Test
  void unDiaSinRutasPropiasNoSeBalancea() {
    preparar(
        List.of(
            parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS),
            parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false)),
        List.of());
    when(rutaRepo.cargaPorArchivo(10L))
        .thenReturn(List.of(carga("DIARIA", CL1, 1L, "100"), carga("LXV", CL1, 1L, "300")));

    ConflictoException error =
        assertThrows(
            ConflictoException.class,
            () -> servicio.balancear(10L, Balanceo.MJS, CL1, FaseBalanceo.RUTAS, null));

    assertTrue(error.getMessage().contains("no tiene rutas M-J-S"), error.getMessage());
  }

  @Test
  void losOperariosNoEsperanAlDiaSinRutasPropias() {
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE);
    preparar(
        List.of(parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS), lxv, mjs),
        List.of(base(1L, false)),
        List.of());
    // M-J-S no tiene rutas propias, pero sí las diarias: lleva operarios igual.
    when(rutaRepo.cargaPorArchivo(10L))
        .thenReturn(List.of(carga("DIARIA", CL1, 1L, "100"), carga("LXV", CL1, 1L, "300")));

    servicio.balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.OPERARIOS, BalanceoRequest.conOperarios(2));

    assertEquals(ParametroCluster.ESTADO_LISTO, lxv.getEstado());
    assertEquals(ParametroCluster.ESTADO_LISTO, mjs.getEstado());
  }

  @Test
  void cerrarNoEsperaAlDiaSinCarga() {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_RUTAS);
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_LISTO);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE);
    preparar(List.of(dia, lxv, mjs), List.of(base(1L, false)), List.of());
    // Solo rutas L-X-V: M-J-S no tiene carga, ni operarios ni cierre.
    when(rutaRepo.cargaPorArchivo(10L)).thenReturn(List.of(carga("LXV", CL1, 1L, "300")));

    servicio.cerrar(10L, Balanceo.LXV, CL1);

    for (ParametroCluster p : List.of(dia, lxv, mjs)) {
      assertEquals(ParametroCluster.ESTADO_CERRADO, p.getEstado());
    }
  }

  @Test
  void reabrirVuelveAlPasoAnteriorACerrarEnLosTres() {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_CERRADO);
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_CERRADO);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_CERRADO);
    ParametroCluster siguiente = parametro(Balanceo.DIA, CL2, ParametroCluster.ESTADO_RUTAS);
    preparar(
        List.of(
            dia, lxv, mjs, siguiente,
            parametro(Balanceo.LXV, CL2, ParametroCluster.ESTADO_PENDIENTE),
            parametro(Balanceo.MJS, CL2, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false)),
        List.of());

    servicio.reabrir(10L, Balanceo.MJS, CL1);

    assertEquals(ParametroCluster.ESTADO_RUTAS, dia.getEstado());
    assertEquals(ParametroCluster.ESTADO_LISTO, lxv.getEstado());
    assertEquals(ParametroCluster.ESTADO_LISTO, mjs.getEstado());
    assertEquals(ParametroCluster.ESTADO_PENDIENTE, siguiente.getEstado(), "CL2 solo había hecho sus diarias");
  }

  @Test
  void elLimiteDeHorasEsElMismoParaLosTres() {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_PENDIENTE);
    ParametroCluster lxv = parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE);
    ParametroCluster mjs = parametro(Balanceo.MJS, CL1, ParametroCluster.ESTADO_PENDIENTE);
    preparar(List.of(dia, lxv, mjs), List.of(base(1L, false)), List.of());

    servicio.guardar(
        10L,
        Balanceo.MJS,
        new ConfiguracionActualizarRequest(
            new BigDecimal("100"),
            null,
            null,
            List.of(new oracle.simulacion.request.ClusterLimiteRequest(CL1, new BigDecimal("4"))),
            null));

    for (ParametroCluster p : List.of(dia, lxv, mjs)) {
      assertEquals(0, new BigDecimal("4").compareTo(p.getLimiteHoras()));
    }
  }

  // ---------------- Historial ----------------

  @Test
  void unDiaVeTambienElHistorialDeLasDiarias() {
    when(archivoRepo.findById(10L)).thenReturn(Optional.of(archivo()));
    when(configRepo.findById(10L)).thenReturn(Optional.of(configuracion()));
    prepararParametros(
        List.of(parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS)), List.of(base(1L, false)), List.of());
    LanzamientoBalanceo diarias = new LanzamientoBalanceo();
    diarias.setId(5L);
    diarias.setBalanceo("DIA");
    diarias.setClusterId(CL1);
    diarias.setMensaje("CL1: 2 ruta(s) movida(s).");
    when(lanzamientoRepo.findTop200ByArchivoIdAndBalanceoInOrderByIdDesc(10L, List.of("LXV", "DIA")))
        .thenReturn(List.of(diarias));

    var historial = historialServicio.historial(10L, Balanceo.LXV).datos();

    assertEquals("Diarias · CL1: 2 ruta(s) movida(s).", historial.lanzamientos().getFirst().mensaje());
    verify(historialRepo).countByArchivoIdAndBalanceoInAndLanzamientoIdIsNull(10L, List.of("LXV", "DIA"));
  }

  @Test
  void elComparativoDeRutasDescuentaLosCambiosPosteriores() {
    when(archivoRepo.findById(10L)).thenReturn(Optional.of(archivo()));
    when(configRepo.findById(10L)).thenReturn(Optional.of(configuracion()));
    prepararParametros(
        List.of(parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS)), List.of(base(1L, false)), List.of());
    LanzamientoBalanceo rutas = new LanzamientoBalanceo();
    rutas.setId(5L);
    rutas.setArchivoId(10L);
    rutas.setBalanceo("LXV");
    rutas.setClusterId(CL1);
    rutas.setFase("RUTAS");
    rutas.setRutas(2);
    when(lanzamientoRepo.findById(5L)).thenReturn(Optional.of(rutas));
    // Hoy: CHUTE 1 con 100 bultos y CHUTE 2 con 50.
    when(rutaRepo.cargaPorCluster(10L, CL1))
        .thenReturn(List.of(carga("LXV", CL1, 1L, "100"), carga("LXV", CL1, 2L, "50")));
    when(historialRepo.ultimoCambio(5L)).thenReturn(40L);
    // El lanzamiento pasó 30 bultos del 1 al 2; después, a mano, 10 volvieron al 1.
    when(historialRepo.deltasDeLanzamiento(10L, CL1, 5L, 40L, List.of("LXV", "DIA")))
        .thenReturn(List.of(delta(1L, "-30", "10"), delta(2L, "30", "-10")));

    var comparativo = historialServicio.comparativoLanzamiento(10L, Balanceo.LXV, 5L).datos();

    assertEquals(30, comparativo.bultosMovidos());
    assertEquals(2, comparativo.rutas());
    var uno = comparativo.chutes().stream().filter((c) -> c.chuteId() == 1L).findFirst().orElseThrow();
    var dos = comparativo.chutes().stream().filter((c) -> c.chuteId() == 2L).findFirst().orElseThrow();
    // Después del lanzamiento: 100 − 10 y 50 + 10; antes: 90 + 30 y 60 − 30.
    assertEquals(120, uno.antes());
    assertEquals(90, uno.despues());
    assertEquals(30, dos.antes());
    assertEquals(60, dos.despues());
  }

  @Test
  void elComparativoDeOperariosUsaLoQueGuardaElHistorial() {
    when(archivoRepo.findById(10L)).thenReturn(Optional.of(archivo()));
    when(configRepo.findById(10L)).thenReturn(Optional.of(configuracion()));
    prepararParametros(
        List.of(parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS)), List.of(base(1L, false)), List.of());
    LanzamientoBalanceo operarios = new LanzamientoBalanceo();
    operarios.setId(6L);
    operarios.setArchivoId(10L);
    operarios.setBalanceo("LXV");
    operarios.setClusterId(CL1);
    operarios.setFase("OPERARIOS");
    when(lanzamientoRepo.findById(6L)).thenReturn(Optional.of(operarios));
    HistorialBalanceo asignado = new HistorialBalanceo();
    asignado.setChuteModificadoId(1L);
    asignado.setOperariosOrigen(0);
    asignado.setOperariosDestino(3);
    when(historialRepo.findByLanzamientoIdAndTipoAccion(6L, HistorialBalanceo.ACCION_ASIGNAR_OPERARIOS))
        .thenReturn(List.of(asignado));

    var chutes = historialServicio.comparativoLanzamiento(10L, Balanceo.LXV, 6L).datos().chutes();

    assertEquals(1, chutes.size());
    assertEquals(0, chutes.getFirst().antes());
    assertEquals(3, chutes.getFirst().despues());
    verify(historialRepo, never()).deltasDeLanzamiento(any(), any(), any(), any(), any());
  }

  @Test
  void losCambiosDeUnLanzamientoDeOtroDiaNoSeVen() {
    when(archivoRepo.findById(10L)).thenReturn(Optional.of(archivo()));
    when(configRepo.findById(10L)).thenReturn(Optional.of(configuracion()));
    prepararParametros(
        List.of(parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_RUTAS)), List.of(base(1L, false)), List.of());
    LanzamientoBalanceo deLxv = new LanzamientoBalanceo();
    deLxv.setId(5L);
    deLxv.setArchivoId(10L);
    deLxv.setBalanceo("LXV");
    when(lanzamientoRepo.findById(5L)).thenReturn(Optional.of(deLxv));

    assertThrows(
        RecursoNoEncontradoException.class,
        () -> historialServicio.historialCambios(10L, Balanceo.MJS, 5L, 1, 25));
  }

  @Test
  void cargarTresClustersExigeAsignarYCerrarEnOrdenSinRelanzarRutas() {
    var archivo = archivo();
    archivo.setBalanceoGuardado(true);
    archivo.setEstado(ArchivoExcel.ESTADO_PROYECCION);
    List<Long> ids = List.of(1L, 2L, 3L);
    List<ParametroCluster> parametros = new ArrayList<>();
    for (long id : ids) {
      for (Balanceo b : Balanceo.values()) {
        parametros.add(parametro(b, id, ParametroCluster.ESTADO_PENDIENTE));
      }
    }
    preparar(parametros, List.of(base(1L, false)), List.of());
    when(archivoRepo.bloquear(10L)).thenReturn(Optional.of(archivo));
    when(archivoRepo.findById(10L)).thenReturn(Optional.of(archivo));
    when(clusterRepo.findAllById(any())).thenReturn(ids.stream().map(id -> cluster(id, "CL" + id)).toList());
    when(rutaRepo.cargaPorArchivo(10L)).thenReturn(List.of(
        carga("DIARIA", 1L, 1L, "100"), carga("LXV", 2L, 1L, "100"), carga("MJS", 3L, 1L, "100")));
    org.mockito.Mockito.doAnswer(inv -> {
      for (var p : parametros) {
        String b = p.getId().getBalanceo();
        long id = p.getId().getClusterId();
        if (b.equals("DIA") || (id == 2 && b.equals("LXV")) || (id == 3 && b.equals("MJS"))) {
          p.setEstado(ParametroCluster.ESTADO_RUTAS);
        }
      }
      return null;
    }).when(lotes).cargarBalanceoRutas(10L);

    servicio.cargarBalanceoRutas(10L);
    for (long id : ids) {
      var equipo = BalanceoRequest.conOperarios(1);
      servicio.balancear(10L, Balanceo.LXV, id, FaseBalanceo.OPERARIOS, equipo);
      if (id < 3) {
        assertThrows(ConflictoException.class,
            () -> servicio.balancear(10L, Balanceo.LXV, id + 1, FaseBalanceo.OPERARIOS, equipo));
      }
      servicio.cerrar(10L, Balanceo.LXV, id);
    }
    assertEquals(ArchivoExcel.ESTADO_FINALIZADO, archivo.getEstado());
  }

  @Test
  void guardarNoAvanzaElTrabajoActual() {
    var parametros = List.of(parametro(Balanceo.DIA, CL1, "PENDIENTE"),
        parametro(Balanceo.LXV, CL1, "PENDIENTE"), parametro(Balanceo.MJS, CL1, "PENDIENTE"));
    preparar(parametros, List.of(base(1L, false)), List.of());
    when(clusterRepo.findAllById(any())).thenReturn(List.of(cluster(CL1, "CL1")));
    when(rutaRepo.cargaPorArchivo(10L)).thenReturn(List.of(carga("DIARIA", CL1, 1L, "100")));
    servicio.guardarBalanceoRutas(10L);
    verify(lotes).guardarBalanceoRutas(10L);
    assertTrue(parametros.stream().allMatch(p -> p.getEstado().equals("PENDIENTE")));
  }

  @Test
  void guardaAunqueFalteBalancearUnaFrecuencia() {
    // Guardar es transparente: a medio balancear también guarda dónde está cada ruta.
    preparar(List.of(parametro(Balanceo.DIA, CL1, "RUTAS"), parametro(Balanceo.LXV, CL1, "RUTAS"),
        parametro(Balanceo.MJS, CL1, "PENDIENTE")), List.of(), List.of());
    when(rutaRepo.cargaPorArchivo(10L)).thenReturn(List.of(carga("MJS", CL1, 1L, "100")));
    servicio.guardarBalanceoRutas(10L);
    verify(lotes).guardarBalanceoRutas(10L);
  }

  @Test
  void noGuardaSinRutasEnLosClusters() {
    preparar(List.of(parametro(Balanceo.DIA, CL1, "PENDIENTE")), List.of(), List.of());
    when(rutaRepo.cargaPorArchivo(10L)).thenReturn(List.of());
    var error = assertThrows(ConflictoException.class, () -> servicio.guardarBalanceoRutas(10L));
    assertTrue(error.getMessage().contains("pásalas desde DATA"), error.getMessage());
    verify(lotes, never()).guardarBalanceoRutas(anyLong());
  }

  @Test
  void rechazaUnPredeterminadoAnteriorIncompleto() {
    var archivo = archivo();
    archivo.setBalanceoGuardado(true);
    preparar(List.of(parametro(Balanceo.DIA, CL1, "PENDIENTE")), List.of(), List.of());
    when(archivoRepo.bloquear(10L)).thenReturn(Optional.of(archivo));
    when(archivoRepo.findById(10L)).thenReturn(Optional.of(archivo));
    when(rutaRepo.cargaPorArchivo(10L)).thenReturn(List.of(carga("DIARIA", CL1, 1L, "100")));
    assertThrows(ConflictoException.class, () -> servicio.cargarBalanceoRutas(10L));
    verify(archivoRepo, never()).save(any());
  }

  @Test
  void noCargaMientrasQuedeUnClusterSinRestablecer() {
    var archivo = archivo();
    archivo.setBalanceoGuardado(true);
    when(archivoRepo.bloquear(10L)).thenReturn(Optional.of(archivo));
    when(lotes.clusterSinRestablecer(10L)).thenReturn(Optional.of("CL2"));
    var error = assertThrows(ConflictoException.class, () -> servicio.cargarBalanceoRutas(10L));
    assertTrue(error.getMessage().contains("CL2"));
    verify(lotes, never()).cargarBalanceoRutas(10L);
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

  private static CargaGrupo cargaGrupo(
      String frecuencia, String grupo, Long chuteId, long rutas, String bultos) {
    return new CargaGrupo() {
      public String getFrecuencia() {
        return frecuencia;
      }

      public Long getClusterId() {
        return CL1;
      }

      public String getGrupo() {
        return grupo;
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
    };
  }

  private static DeltaChute delta(Long chuteId, String delta, String posterior) {
    return new DeltaChute() {
      public Long getChuteId() {
        return chuteId;
      }

      public BigDecimal getDelta() {
        return new BigDecimal(delta);
      }

      public BigDecimal getDeltaPosterior() {
        return new BigDecimal(posterior);
      }
    };
  }

  private static CargaRutas carga(String frecuencia, Long clusterId, Long chuteId, String bultos) {
    return new CargaRutas() {
      public String getFrecuencia() {
        return frecuencia;
      }

      public Long getClusterId() {
        return clusterId;
      }

      public Long getChuteId() {
        return chuteId;
      }

      public Long getRutas() {
        return 1L;
      }

      public BigDecimal getBultos() {
        return new BigDecimal(bultos);
      }

      public BigDecimal getUnidades() {
        return new BigDecimal(bultos);
      }

      public BigDecimal getLineas() {
        return BigDecimal.ONE;
      }

      public BigDecimal getCod() {
        return BigDecimal.ONE;
      }
    };
  }

  private static CambioParaDeshacer deshacer(Long rutaId, Long chuteOrigen) {
    return new CambioParaDeshacer() {
      public Long getRutaId() {
        return rutaId;
      }

      public String getTipoAccion() {
        return HistorialBalanceo.ACCION_MOVER_RUTA;
      }

      public Long getChuteOrigenId() {
        return chuteOrigen;
      }

      public Long getClusterOrigenId() {
        return null;
      }

      public String getFrecuencia() {
        return Balanceo.DIARIA;
      }

      public Long getClusterId() {
        return CL1;
      }
    };
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
