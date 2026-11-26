package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import oracle.simulacion.model.ArchivoExcel;
import oracle.simulacion.model.Balanceo;
import oracle.simulacion.model.Chute;
import oracle.simulacion.model.Cluster;
import oracle.simulacion.model.ParametroChute;
import oracle.simulacion.model.ParametroChuteId;
import oracle.simulacion.model.ParametroCluster;
import oracle.simulacion.model.ParametroClusterId;
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
import oracle.simulacion.repository.RutasDataRepository;
import oracle.simulacion.repository.RutasDataRepository.LocalGuardado;
import oracle.simulacion.repository.RutasDataRepository.RutaData;
import oracle.simulacion.repository.SimulacionConfigRepository;
import oracle.simulacion.repository.SimulacionLotesRepository;
import oracle.simulacion.request.DataRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DATA en Configuración: las rutas nacen ahí, reciben tipo, frecuencia y grupo, y solo completas
 * pasan a un cluster (la celda donde se sueltan es su posición original). Un cluster que no empezó
 * a balancearse devuelve rutas a DATA y se puede quitar; uno vacío se salta en el balanceo.
 */
@ExtendWith(MockitoExtension.class)
class SimulacionDataServiceTest {

  private static final Long ARCHIVO = 10L;
  private static final Long CL1 = 1L;
  private static final Long CL2 = 2L;
  private static final String PENDIENTE = ParametroCluster.ESTADO_PENDIENTE;

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
  @Mock private RutasDataRepository repo;

  private SimulacionConfiguracionService configuracion;
  private SimulacionDataService servicio;
  private ArchivoExcel archivo;

  @BeforeEach
  void preparar() {
    // Chutes 1, 2 y 3; el 3 bloqueado.
    PlanoChutes plano = PlanoChutes.fijo(1, 3, Set.of(3), Map.of(1, 1L, 2, 2L, 3, 3L));
    configuracion =
        new SimulacionConfiguracionService(
            archivoRepo, configRepo, clusterRepo, chuteRepo, parametroClusterRepo, parametroChuteRepo,
            rutaRepo, historialRepo, operarioOlaRepo, lanzamientoRepo, lotes, transacciones,
            new CacheRespuestas(false, 1, 1), plano);
    servicio = new SimulacionDataService(configuracion, repo, rutaRepo, lotes, plano, transacciones);
    archivo = new ArchivoExcel();
    archivo.setId(ARCHIVO);
    archivo.setEstado(ArchivoExcel.ESTADO_PROYECCION);
    lenient()
        .when(transacciones.ejecutar(any()))
        .thenAnswer(invocacion -> ((Supplier<?>) invocacion.getArgument(0)).get());
    lenient().when(archivoRepo.bloquear(ARCHIVO)).thenReturn(Optional.of(archivo));
    lenient().when(archivoRepo.findById(ARCHIVO)).thenReturn(Optional.of(archivo));
    lenient().when(archivoRepo.save(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
    SimulacionConfig config = new SimulacionConfig();
    config.setArchivoId(ARCHIVO);
    config.setVelocidadBase(new BigDecimal("100"));
    lenient().when(configRepo.findById(ARCHIVO)).thenReturn(Optional.of(config));
    lenient()
        .when(parametroChuteRepo.findByIdArchivoId(ARCHIVO))
        .thenReturn(List.of(chute(1L), chute(2L), chute(3L)));
    lenient().when(operarioOlaRepo.findByIdArchivoId(ARCHIVO)).thenReturn(List.of());
    lenient()
        .when(clusterRepo.findAllById(any()))
        .thenReturn(List.of(catalogoCluster(CL1, "CL1"), catalogoCluster(CL2, "CL2"), catalogoCluster(3L, "CL3")));
    lenient()
        .when(chuteRepo.findAllById(any()))
        .thenReturn(List.of(catalogo(1L, "1"), catalogo(2L, "2"), catalogo(3L, "3")));
    lenient().when(repo.totales(ARCHIVO)).thenReturn(new RutasDataRepository.Totales(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0));
  }

  // ---------------- Crear ----------------

  @Test
  void unaRutaNuevaNaceEnDataEnUnChuteVisibleConSusCodigosSumados() {
    clusters(Set.of());
    when(repo.insertar(eq(ARCHIVO), eq("2002"), anyLong(), any(), any(), any(), anyInt())).thenReturn(50L);

    ApiResponse<oracle.simulacion.response.CambioDataResponse> r =
        servicio.crear(
            ARCHIVO,
            new DataRequest.Ruta(
                " 2002 ",
                List.of(local(" a1 ", "10.00"), local("A1", "5.50"), local("B2", "1.00"))));

    ArgumentCaptor<Long> chute = ArgumentCaptor.forClass(Long.class);
    verify(repo)
        .insertar(eq(ARCHIVO), eq("2002"), chute.capture(), eq(new BigDecimal("16.50")), any(), any(), eq(2));
    assertTrue(Set.of(1L, 2L).contains(chute.getValue()), "chute visible, nunca el bloqueado");
    ArgumentCaptor<List<RutasDataRepository.Local>> locales = captorLocales();
    verify(repo).reemplazarLocales(eq(50L), locales.capture());
    assertEquals("A1", locales.getValue().getFirst().cod());
    assertEquals(2, locales.getValue().getFirst().filas());
    assertTrue(r.mensaje().contains("Se sumaron"));
    verify(archivoRepo).save(archivo);
  }

  @Test
  void noSeCreaOtraRutaSinTipoConElMismoNumero() {
    clusters(Set.of());
    when(repo.existe(ARCHIVO, "2002", null, null)).thenReturn(true);

    assertThrows(
        ConflictoException.class,
        () -> servicio.crear(ARCHIVO, new DataRequest.Ruta("2002", List.of(local("A1", "1")))));
    verify(repo, never()).insertar(anyLong(), any(), anyLong(), any(), any(), any(), anyInt());
  }

  // ---------------- De DATA a un cluster ----------------

  @Test
  void soloUnaRutaCompletaPasaAUnCluster() {
    clusters(Set.of(CL1), cluster(CL1, PENDIENTE));
    when(repo.rutas(ARCHIVO, List.of(5L))).thenReturn(List.of(enData(5, "2002", null, "LXV", "1")));

    ConflictoException e =
        assertThrows(ConflictoException.class, () -> servicio.mover(ARCHIVO, mover(CL1, 1L, 5L)));
    assertTrue(e.getMessage().contains("Asigna tipo, frecuencia y grupo"), e.getMessage());
    verify(repo, never()).aCluster(anyLong(), anyList(), anyLong(), anyLong());
  }

  @Test
  void unClusterCerradoNoRecibeRutasDeData() {
    clusters(Set.of(CL1), cluster(CL1, ParametroCluster.ESTADO_CERRADO));
    when(repo.rutas(ARCHIVO, List.of(5L))).thenReturn(List.of(enData(5, "2002", "B2C", "LXV", "1")));

    ConflictoException e =
        assertThrows(ConflictoException.class, () -> servicio.mover(ARCHIVO, mover(CL1, 1L, 5L)));
    assertTrue(e.getMessage().contains("CL1 está cerrado"), e.getMessage());
  }

  @Test
  void unClusterVacioNoQuedaAntesDeUnoQueYaEmpezo() {
    List<ParametroCluster> parametros = new ArrayList<>(cluster(CL1, PENDIENTE));
    parametros.addAll(cluster(CL2, ParametroCluster.ESTADO_RUTAS));
    clusters(Set.of(CL2), parametros);
    when(repo.rutas(ARCHIVO, List.of(5L))).thenReturn(List.of(enData(5, "2002", "B2C", "LXV", "1")));

    ConflictoException e =
        assertThrows(ConflictoException.class, () -> servicio.mover(ARCHIVO, mover(CL1, 1L, 5L)));
    assertTrue(e.getMessage().contains("CL1 va antes de CL2"), e.getMessage());
  }

  @Test
  void alEntrarAUnClusterConOperariosEsteVuelveAlPaso2() {
    List<ParametroCluster> parametros = cluster(CL1, ParametroCluster.ESTADO_LISTO);
    clusters(Set.of(CL1), parametros);
    when(repo.rutas(ARCHIVO, List.of(5L))).thenReturn(List.of(enData(5, "2002", "B2C", "LXV", "1")));
    when(repo.aCluster(ARCHIVO, List.of(5L), CL1, 2L)).thenReturn(1);

    ApiResponse<oracle.simulacion.response.CambioDataResponse> r = servicio.mover(ARCHIVO, mover(CL1, 2L, 5L));

    verify(repo).aCluster(ARCHIVO, List.of(5L), CL1, 2L);
    assertEquals(ParametroCluster.ESTADO_RUTAS, estado(parametros, Balanceo.LXV));
    // M-J-S no tiene esa ruta: sus operarios siguen.
    assertEquals(ParametroCluster.ESTADO_LISTO, estado(parametros, Balanceo.MJS));
    assertTrue(r.mensaje().contains("CL1"), r.mensaje());
    verify(archivoRepo).save(archivo);
  }

  @Test
  void lasRutasDeDataYLasDeUnClusterSeMuevenPorSeparado() {
    clusters(Set.of(CL1), cluster(CL1, PENDIENTE));
    when(repo.rutas(ARCHIVO, List.of(5L, 6L)))
        .thenReturn(List.of(enData(5, "2002", "B2C", "LXV", "1"), enCluster(6, "2015", CL1)));

    ConflictoException e =
        assertThrows(ConflictoException.class, () -> servicio.mover(ARCHIVO, mover(CL1, 1L, 5L, 6L)));
    assertTrue(e.getMessage().contains("por separado"), e.getMessage());
  }

  // ---------------- De un cluster a DATA ----------------

  @Test
  void unClusterSinEmpezarDevuelveSusRutasADataYSeBorraSuHistorial() {
    clusters(Set.of(CL1), cluster(CL1, PENDIENTE));
    when(repo.rutas(ARCHIVO, List.of(6L))).thenReturn(List.of(enCluster(6, "2015", CL1)));

    servicio.mover(ARCHIVO, mover(null, 2L, 6L));

    verify(repo).aData(ARCHIVO, List.of(6L), 2L);
    verify(repo).borrarHistorialDeRutas(ARCHIVO, List.of(6L));
  }

  @Test
  void unClusterQueYaEmpezoNoDevuelveRutasAData() {
    clusters(Set.of(CL1), cluster(CL1, ParametroCluster.ESTADO_RUTAS));
    when(repo.rutas(ARCHIVO, List.of(6L))).thenReturn(List.of(enCluster(6, "2015", CL1)));

    ConflictoException e =
        assertThrows(ConflictoException.class, () -> servicio.mover(ARCHIVO, mover(null, 2L, 6L)));
    assertTrue(e.getMessage().contains("ya empezó a balancearse"), e.getMessage());
    verify(repo, never()).aData(anyLong(), anyList(), anyLong());
  }

  @Test
  void enDataLaRutaQuedaEnElBloqueDondeSeSuelta() {
    clusters(Set.of(CL1), cluster(CL1, PENDIENTE));
    // De un bloque a otro de DATA: solo cambia su columna; no tenía historial que borrar.
    when(repo.rutas(ARCHIVO, List.of(5L))).thenReturn(List.of(enData(5, "2002", null, null, null)));
    ApiResponse<oracle.simulacion.response.CambioDataResponse> r = servicio.mover(ARCHIVO, mover(null, 2L, 5L));
    verify(repo).aData(ARCHIVO, List.of(5L), 2L);
    verify(repo, never()).borrarHistorialDeRutas(anyLong(), anyList());
    assertTrue(r.mensaje().contains("bloque 2"), r.mensaje());

    // Soltarla en el mismo bloque no hace nada.
    ConflictoException e =
        assertThrows(ConflictoException.class, () -> servicio.mover(ARCHIVO, mover(null, 1L, 5L)));
    assertTrue(e.getMessage().contains("ya están en ese bloque"), e.getMessage());
  }

  @Test
  void enDataCualquierColumnaRecibeRutasInclusoUnaBloqueada() {
    clusters(Set.of(CL1), cluster(CL1, PENDIENTE));
    when(repo.rutas(ARCHIVO, List.of(6L))).thenReturn(List.of(enCluster(6, "2015", CL1)));

    servicio.mover(ARCHIVO, mover(null, 3L, 6L));

    verify(repo).aData(ARCHIVO, List.of(6L), 3L);
  }

  @Test
  void moverSiempreNecesitaLaColumna() {
    clusters(Set.of(CL1), cluster(CL1, PENDIENTE));
    assertThrows(SolicitudInvalidaException.class, () -> servicio.mover(ARCHIVO, mover(CL1, null, 5L)));
    assertThrows(SolicitudInvalidaException.class, () -> servicio.mover(ARCHIVO, mover(null, null, 5L)));
    assertThrows(SolicitudInvalidaException.class, () -> servicio.mover(ARCHIVO, mover(null, 99L, 5L)));
    verify(repo, never()).rutas(anyLong(), anyList());
  }

  @Test
  void unaRutaQueLlegoAManoDesdeUnClusterEmpezadoNoPasaAData() {
    List<ParametroCluster> parametros = new ArrayList<>(cluster(CL1, ParametroCluster.ESTADO_RUTAS));
    parametros.addAll(cluster(CL2, PENDIENTE));
    clusters(Set.of(CL1, CL2), parametros);
    when(repo.rutas(ARCHIVO, List.of(6L))).thenReturn(List.of(movida(6, "2015", CL2, CL1)));

    ConflictoException e =
        assertThrows(ConflictoException.class, () -> servicio.mover(ARCHIVO, mover(null, 2L, 6L)));
    assertTrue(e.getMessage().contains("llegó a mano desde CL1"), e.getMessage());
  }

  @Test
  void quedarseEnUnChuteBloqueadoSePermite() {
    clusters(Set.of(CL1), cluster(CL1, PENDIENTE));
    when(repo.rutas(ARCHIVO, List.of(5L)))
        .thenReturn(List.of(new RutaData(5, "2002", "B2C", null, null, 3L, "LXV", "1")));
    when(repo.aCluster(ARCHIVO, List.of(5L), CL1, 3L)).thenReturn(1);

    servicio.mover(ARCHIVO, mover(CL1, 3L, 5L));

    verify(repo).aCluster(ARCHIVO, List.of(5L), CL1, 3L);
  }

  @Test
  void entreClustersUnoVacioTampocoQuedaAntesDeUnoQueYaEmpezo() {
    List<ParametroCluster> parametros = new ArrayList<>(cluster(CL1, PENDIENTE));
    parametros.addAll(cluster(CL2, ParametroCluster.ESTADO_RUTAS));
    clusters(Set.of(CL2), parametros);

    ConflictoException e =
        assertThrows(
            ConflictoException.class,
            () ->
                configuracion.moverVarias(
                    ARCHIVO, Balanceo.LXV, new oracle.simulacion.request.MoverRutasRequest(List.of(6L), CL1, 1L)));
    assertTrue(e.getMessage().contains("CL1 va antes de CL2"), e.getMessage());
  }

  // ---------------- Mover locales ----------------

  @Test
  void unLocalPasaAlFinalDeLaOtraRutaYSeRecalculanLasDos() {
    clusters(Set.of(CL1), cluster(CL1, PENDIENTE));
    dosRutas(enData(5, "2002", null, null, null), enData(6, "2015", "B2C", "LXV", "1"));
    when(repo.locales(5L)).thenReturn(List.of(loc(1, "A1", "10"), loc(2, "B2", "5")));
    when(repo.locales(6L)).thenReturn(List.of(loc(4, "C3", "7")));

    ApiResponse<oracle.simulacion.response.CambioDataResponse> r = servicio.moverLocales(ARCHIVO, moverLocales(5, 6, 1));

    verify(repo).pasarLocales(eq(5L), eq(6L), argThat((List<int[]> p) -> p.size() == 1 && p.get(0)[0] == 1 && p.get(0)[1] == 5));
    verify(repo, never()).fijarLocales(anyLong(), anyList());
    verify(repo).recalcularTotales(ARCHIVO, List.of(5L, 6L));
    verify(archivoRepo).save(archivo);
    assertTrue(r.mensaje().contains("1 local de 2002 → 2015"), r.mensaje());
  }

  @Test
  void elMismoCodigoSeSumaEnLaRutaDestino() {
    clusters(Set.of(CL1), cluster(CL1, PENDIENTE));
    dosRutas(enData(5, "2002", null, null, null), enData(6, "2015", "B2C", "LXV", "1"));
    when(repo.locales(5L)).thenReturn(List.of(loc(1, " a1 ", "10"), loc(2, "B2", "5"), loc(3, "D4", "1")));
    when(repo.locales(6L)).thenReturn(List.of(loc(1, "A1", "7")));

    ApiResponse<oracle.simulacion.response.CambioDataResponse> r = servicio.moverLocales(ARCHIVO, moverLocales(5, 6, 1, 3));

    verify(repo).fijarLocales(6L, List.of(new LocalGuardado(1, "A1", 2, new BigDecimal("17"), new BigDecimal("2"), new BigDecimal("2"))));
    verify(repo).quitarLocales(5L, List.of(1));
    verify(repo).pasarLocales(eq(5L), eq(6L), argThat((List<int[]> p) -> p.size() == 1 && p.get(0)[0] == 3 && p.get(0)[1] == 2));
    assertTrue(r.mensaje().contains("2 locales") && r.mensaje().contains("1 se sumó"), r.mensaje());
  }

  @Test
  void unaRutaNoSeQuedaSinLocales() {
    clusters(Set.of(CL1), cluster(CL1, PENDIENTE));
    dosRutas(enData(5, "2002", null, null, null), enData(6, "2015", "B2C", "LXV", "1"));
    when(repo.locales(5L)).thenReturn(List.of(loc(1, "A1", "10"), loc(2, "B2", "5")));

    ConflictoException e =
        assertThrows(ConflictoException.class, () -> servicio.moverLocales(ARCHIVO, moverLocales(5, 6, 1, 2)));
    assertTrue(e.getMessage().contains("2002 se quedaría sin locales"), e.getMessage());
    verify(repo, never()).recalcularTotales(anyLong(), anyList());
  }

  @Test
  void unClusterQueYaEmpezoNoRecibeNiDaLocales() {
    clusters(Set.of(CL1), cluster(CL1, ParametroCluster.ESTADO_RUTAS));
    dosRutas(enData(5, "2002", null, null, null), enCluster(6, "2015", CL1));

    ConflictoException e =
        assertThrows(ConflictoException.class, () -> servicio.moverLocales(ARCHIVO, moverLocales(5, 6, 1)));
    assertTrue(e.getMessage().contains("CL1 ya empezó a balancearse") && e.getMessage().contains("2015"), e.getMessage());
    dosRutas(enCluster(6, "2015", CL1), enData(5, "2002", null, null, null));
    e = assertThrows(ConflictoException.class, () -> servicio.moverLocales(ARCHIVO, moverLocales(6, 5, 1)));
    assertTrue(e.getMessage().contains("sacarle locales"), e.getMessage());
    verify(repo, never()).locales(anyLong());
  }

  @Test
  void enUnClusterSinEmpezarSeMuevenLocales() {
    clusters(Set.of(CL1), cluster(CL1, PENDIENTE));
    dosRutas(enCluster(6, "2015", CL1), enData(5, "2002", null, null, null));
    when(repo.locales(6L)).thenReturn(List.of(loc(1, "A1", "10"), loc(2, "B2", "5")));
    when(repo.locales(5L)).thenReturn(List.of(loc(1, "C3", "7")));

    servicio.moverLocales(ARCHIVO, moverLocales(6, 5, 2));

    verify(repo).recalcularTotales(ARCHIVO, List.of(6L, 5L));
  }

  @Test
  void unLocalQueYaNoEstaOLaMismaRutaNoMuevenNada() {
    clusters(Set.of(CL1), cluster(CL1, PENDIENTE));
    assertThrows(SolicitudInvalidaException.class, () -> servicio.moverLocales(ARCHIVO, moverLocales(5, 5, 1)));
    dosRutas(enData(5, "2002", null, null, null), enData(6, "2015", "B2C", "LXV", "1"));
    when(repo.locales(5L)).thenReturn(List.of(loc(1, "A1", "10"), loc(2, "B2", "5")));
    ConflictoException e =
        assertThrows(ConflictoException.class, () -> servicio.moverLocales(ARCHIVO, moverLocales(5, 6, 9)));
    assertTrue(e.getMessage().contains("ya no está"), e.getMessage());
    verify(repo, never()).recalcularTotales(anyLong(), anyList());
  }

  // ---------------- Tipo, frecuencia y grupo ----------------

  @Test
  void elTipoSeAsignaEnDataYEnUnClusterSinEmpezar() {
    clusters(Set.of(CL1), cluster(CL1, PENDIENTE));
    when(repo.rutas(ARCHIVO, List.of(5L, 6L)))
        .thenReturn(List.of(enData(5, "2002", null, null, null), enCluster(6, "2015", CL1)));
    when(repo.repetidasConTipo(ARCHIVO, List.of(5L, 6L), "B2C")).thenReturn(List.of());
    when(repo.asignarTipo(ARCHIVO, List.of(5L, 6L), "B2C")).thenReturn(2);

    ApiResponse<oracle.simulacion.response.CambioDataResponse> r = servicio.asignarTipo(ARCHIVO, new DataRequest.Tipo(List.of(5L, 6L), "B2C"));

    verify(repo).asignarTipo(ARCHIVO, List.of(5L, 6L), "B2C");
    assertTrue(r.mensaje().contains("2 rutas"), r.mensaje());
  }

  @Test
  void elTipoNoDejaDosRutasIguales() {
    clusters(Set.of());
    when(repo.rutas(ARCHIVO, List.of(5L))).thenReturn(List.of(enData(5, "2002", null, null, null)));
    when(repo.repetidasConTipo(ARCHIVO, List.of(5L), "B2C")).thenReturn(List.of("2002"));

    assertThrows(
        ConflictoException.class, () -> servicio.asignarTipo(ARCHIVO, new DataRequest.Tipo(List.of(5L), "B2C")));
    verify(repo, never()).asignarTipo(anyLong(), anyList(), any());
  }

  @Test
  void enUnClusterQueYaEmpezoNoSeCambiaNadaYNingunaRecibeElCambio() {
    clusters(Set.of(CL1), cluster(CL1, ParametroCluster.ESTADO_RUTAS));
    when(repo.rutas(ARCHIVO, List.of(5L, 6L)))
        .thenReturn(List.of(enData(5, "2002", null, null, null), enCluster(6, "2015", CL1)));

    ConflictoException e =
        assertThrows(
            ConflictoException.class,
            () -> servicio.asignarGrupo(ARCHIVO, new DataRequest.Grupo(List.of(5L, 6L), "3")));
    assertTrue(e.getMessage().contains("ya empezó a balancearse"), e.getMessage());
    verify(repo, never()).asignarGrupo(anyLong(), anyList(), any());
  }

  @Test
  void editarUnaRutaDeUnClusterPideDevolverlaAData() {
    clusters(Set.of(CL1), cluster(CL1, PENDIENTE));
    when(repo.rutas(ARCHIVO, List.of(6L))).thenReturn(List.of(enCluster(6, "2015", CL1)));

    ConflictoException e =
        assertThrows(
            ConflictoException.class,
            () -> servicio.actualizar(ARCHIVO, 6L, new DataRequest.Ruta("2015", List.of(local("A1", "1")))));
    assertTrue(e.getMessage().contains("devuélvela a DATA"), e.getMessage());
  }

  // ---------------- Clusters ----------------

  @Test
  void agregarUnClusterSigueLaNumeracion() {
    List<ParametroCluster> parametros = new ArrayList<>(cluster(CL1, PENDIENTE));
    parametros.addAll(cluster(CL2, PENDIENTE));
    clusters(Set.of(CL1), parametros);
    when(lotes.asegurarClusters(List.of("CL3"))).thenReturn(Map.of("CL3", 3L));

    servicio.cambiarClusters(ARCHIVO, new DataRequest.Clusters(3));

    verify(lotes).asegurarClusters(List.of("CL3"));
    verify(lotes).asegurarParametrosCluster(eq(ARCHIVO), any());
  }

  @Test
  void quitarElUltimoClusterDevuelveSusRutasAData() {
    List<ParametroCluster> parametros = new ArrayList<>(cluster(CL1, ParametroCluster.ESTADO_RUTAS));
    parametros.addAll(cluster(CL2, PENDIENTE));
    clusters(Set.of(CL1, CL2), parametros);
    when(repo.devolverADataEnSuChute(ARCHIVO, List.of(CL2))).thenReturn(List.of(6L));

    ApiResponse<oracle.simulacion.response.CambioDataResponse> r = servicio.cambiarClusters(ARCHIVO, new DataRequest.Clusters(1));

    verify(repo).borrarHistorialDeRutas(ARCHIVO, List.of(6L));
    verify(repo).quitarClusters(ARCHIVO, List.of(CL2));
    assertTrue(r.mensaje().contains("1 ruta volvieron a DATA"), r.mensaje());
  }

  @Test
  void alQuitarUnClusterLasRutasQueSalieronDeElTomanSuClusterComoOriginal() {
    List<ParametroCluster> parametros = new ArrayList<>(cluster(CL1, PENDIENTE));
    parametros.addAll(cluster(CL2, PENDIENTE));
    clusters(Set.of(CL1, CL2), parametros);
    when(repo.rutasCruzadas(ARCHIVO, List.of(CL2))).thenReturn(List.of(movida(7, "2020", CL1, CL2)));
    when(repo.devolverADataEnSuChute(ARCHIVO, List.of(CL2))).thenReturn(List.of());

    servicio.cambiarClusters(ARCHIVO, new DataRequest.Clusters(1));

    verify(repo).fijarOrigenEnSuCluster(ARCHIVO, List.of(7L));
    verify(repo).borrarHistorialDeRutas(ARCHIVO, List.of(7L));
    verify(repo).quitarClusters(ARCHIVO, List.of(CL2));
  }

  @Test
  void noSeQuitaUnClusterSiUnaRutaSalioDeElAUnoQueYaEmpezo() {
    List<ParametroCluster> parametros = new ArrayList<>(cluster(CL1, ParametroCluster.ESTADO_RUTAS));
    parametros.addAll(cluster(CL2, PENDIENTE));
    clusters(Set.of(CL1, CL2), parametros);
    when(repo.rutasCruzadas(ARCHIVO, List.of(CL2))).thenReturn(List.of(movida(7, "2020", CL1, CL2)));

    ConflictoException e =
        assertThrows(ConflictoException.class, () -> servicio.cambiarClusters(ARCHIVO, new DataRequest.Clusters(1)));
    assertTrue(e.getMessage().contains("salió de CL2 a CL1"), e.getMessage());
    verify(repo, never()).quitarClusters(anyLong(), anyList());
  }

  @Test
  void unClusterQueYaEmpezoNoSeQuita() {
    List<ParametroCluster> parametros = new ArrayList<>(cluster(CL1, PENDIENTE));
    parametros.addAll(cluster(CL2, ParametroCluster.ESTADO_LISTO));
    clusters(Set.of(CL1, CL2), parametros);

    assertThrows(ConflictoException.class, () -> servicio.cambiarClusters(ARCHIVO, new DataRequest.Clusters(1)));
    verify(repo, never()).quitarClusters(anyLong(), anyList());
  }

  // ---------------- Clusters vacíos en Configuración ----------------

  @Test
  void unClusterVacioSeSaltaEnElBalanceoYNoFrenaElFinal() {
    List<ParametroCluster> parametros = new ArrayList<>(cluster(CL1, ParametroCluster.ESTADO_CERRADO));
    parametros.addAll(cluster(CL2, PENDIENTE));
    clusters(Set.of(CL1), parametros);

    SimulacionConfiguracionService.Contexto ctx = configuracion.contexto(ARCHIVO, Balanceo.LXV, false);
    assertEquals(List.of(CL1), SimulacionConfiguracionService.orden(ctx));
    SimulacionConfiguracionService.actualizarEstado(ctx);
    assertEquals(ArchivoExcel.ESTADO_FINALIZADO, archivo.getEstado());
  }

  @Test
  void elNumeroDeUnClusterSaleDeSuNombre() {
    assertEquals(7, SimulacionDataService.numero("cl7"));
    assertEquals(12, SimulacionDataService.numero("CL 12"));
    assertEquals(0, SimulacionDataService.numero("Norte"));
  }

  // ---------------- Apoyo ----------------

  @SafeVarargs
  private void clusters(Set<Long> conRutas, List<ParametroCluster>... parametros) {
    List<ParametroCluster> todos = new ArrayList<>();
    for (List<ParametroCluster> p : parametros) {
      todos.addAll(p);
    }
    lenient().when(parametroClusterRepo.findByIdArchivoId(ARCHIVO)).thenReturn(todos);
    lenient().when(rutaRepo.clustersConRutas(ARCHIVO)).thenReturn(conRutas);
  }

  /** El cluster en los tres balanceos con ese estado. */
  private static List<ParametroCluster> cluster(Long clusterId, String estado) {
    List<ParametroCluster> filas = new ArrayList<>();
    for (Balanceo b : Balanceo.values()) {
      ParametroCluster p = new ParametroCluster();
      p.setId(new ParametroClusterId(ARCHIVO, b.name(), clusterId));
      p.setLimiteHoras(BigDecimal.ZERO);
      p.setEstado(estado);
      filas.add(p);
    }
    return filas;
  }

  private static String estado(List<ParametroCluster> parametros, Balanceo balanceo) {
    return parametros.stream()
        .filter((p) -> p.getId().getBalanceo().equals(balanceo.name()))
        .findFirst()
        .orElseThrow()
        .getEstado();
  }

  private static RutaData enData(long id, String codigo, String tipo, String frecuencia, String grupo) {
    return new RutaData(id, codigo, tipo, null, null, 1L, frecuencia, grupo);
  }

  private static RutaData enCluster(long id, String codigo, Long clusterId) {
    return new RutaData(id, codigo, "B2B", clusterId, clusterId, 1L, "MJS", "1");
  }

  /** En {@code clusterId}, movida a mano desde {@code origen}. */
  private static RutaData movida(long id, String codigo, Long clusterId, Long origen) {
    return new RutaData(id, codigo, "B2B", clusterId, origen, 1L, "MJS", "1");
  }

  private void dosRutas(RutaData origen, RutaData destino) {
    when(repo.rutas(ARCHIVO, List.of(origen.id(), destino.id()))).thenReturn(List.of(origen, destino));
  }

  private static LocalGuardado loc(int orden, String cod, String bultos) {
    return new LocalGuardado(orden, cod, 1, new BigDecimal(bultos), BigDecimal.ONE, BigDecimal.ONE);
  }

  private static DataRequest.MoverLocales moverLocales(long origen, long destino, Integer... ordenes) {
    return new DataRequest.MoverLocales(origen, destino, List.of(ordenes));
  }

  private static DataRequest.Mover mover(Long clusterId, Long chuteId, Long... ids) {
    return new DataRequest.Mover(List.of(ids), clusterId, chuteId);
  }

  private static DataRequest.Local local(String cod, String bultos) {
    return new DataRequest.Local(cod, new BigDecimal(bultos), BigDecimal.ONE, BigDecimal.ONE, null);
  }

  private static ParametroChute chute(Long chuteId) {
    ParametroChute p = new ParametroChute();
    p.setId(new ParametroChuteId(ARCHIVO, chuteId));
    p.setOperarios(0);
    return p;
  }

  private static Cluster catalogoCluster(Long id, String nombre) {
    Cluster c = new Cluster();
    c.setId(id);
    c.setNombre(nombre);
    return c;
  }

  private static Chute catalogo(Long id, String nombre) {
    Chute c = new Chute();
    c.setId(id);
    c.setNombre(nombre);
    return c;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ArgumentCaptor<List<RutasDataRepository.Local>> captorLocales() {
    return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
  }
}
