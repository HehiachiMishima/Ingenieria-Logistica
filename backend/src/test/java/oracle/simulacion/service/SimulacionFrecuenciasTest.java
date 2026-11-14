package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    assertEquals(2L, m1.getChuteId(), "la ruta M-J-S se reparte alrededor de la diaria");
    assertEquals(2L, m2.getChuteId(), "la que estaba fuera del rango de las diarias entra");
    assertEquals(2L, soloLxv.getChuteId(), "una ruta L-X-V no aparece en M-J-S");
    assertTrue(resultado.mensaje().contains("diaria"), resultado.mensaje());
    assertEquals(ParametroCluster.ESTADO_RUTAS, mjs.getEstado());
    // El rango pedido no cuenta: manda el de las diarias. Los tramos no se reescriben.
    assertEquals(2L, mjs.getChuteHastaId());
    verify(lotes, never()).guardarGrupos(anyLong(), anyLong(), anyList());
  }

  @Test
  void unGrupoSinTramoNoSeBalanceaEnUnDia() {
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
    // Solo A tiene tramo: B llegó después de balancear las diarias.
    when(lotes.grupos(10L)).thenReturn(List.of(new GrupoGuardado(CL1, "A", 1, 1L, 2L)));

    ConflictoException error =
        assertThrows(
            ConflictoException.class,
            () -> servicio.balancear(10L, Balanceo.LXV, CL1, FaseBalanceo.RUTAS, null));

    assertTrue(error.getMessage().contains("B"), error.getMessage());
    assertTrue(error.getMessage().contains("Restablece"), error.getMessage());
    verify(lotes, never()).moverRutas(any());
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
  void elTramoDeUnGrupoSeDimensionaConLasRutasDelDiaConMasRutas() {
    ParametroCluster dia = parametro(Balanceo.DIA, CL1, ParametroCluster.ESTADO_PENDIENTE);
    when(archivoRepo.findById(10L)).thenReturn(Optional.of(archivo()));
    when(configRepo.findById(10L)).thenReturn(Optional.of(configuracion()));
    prepararParametros(
        List.of(dia, parametro(Balanceo.LXV, CL1, ParametroCluster.ESTADO_PENDIENTE)),
        List.of(base(1L, false), base(2L, false), base(3L, false)),
        List.of());
    // Grupo A: L-X-V 2 rutas pesadas, M-J-S 10 rutas livianas, 1 diaria.
    when(rutaRepo.cargaPorGrupo(10L))
        .thenReturn(
            List.of(
                cargaGrupo("LXV", "A", 1L, 2, "1000"),
                cargaGrupo("MJS", "A", 1L, 10, "900"),
                cargaGrupo(Balanceo.DIARIA, "A", 1L, 1, "50")));

    var vista = servicio.vistaBalanceo(10L, Balanceo.DIA, CL1, new BalanceoRequest(1L, 3L)).datos();

    assertEquals(11, vista.grupos().get(0).rutas(), "diarias + el día con más rutas (M-J-S)");
    assertEquals(0, new BigDecimal("1050").compareTo(vista.grupos().get(0).bultos()), "diarias + el día con más bultos");
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
    assertTrue(resultado.mensaje().contains("buff"), resultado.mensaje());
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
