package oracle.simulacion.diccionario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import oracle.common.config.Transacciones;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Cluster;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Local;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Ruta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Reglas del Registro v2 (diccionario de rutas) con el repositorio simulado (sin BD). */
class DiccionarioRutasServiceTest {

  private DiccionarioRutasRepository repo;
  private EscenarioDiccionario escenario;
  private DiccionarioRutasService servicio;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void preparar() {
    repo = mock(DiccionarioRutasRepository.class);
    escenario = mock(EscenarioDiccionario.class);
    when(escenario.asegurar(any())).thenReturn(1L);
    when(escenario.buscar()).thenReturn(1L);
    when(escenario.clustersCerrados(any())).thenReturn(Set.of());
    Transacciones transacciones = mock(Transacciones.class);
    when(transacciones.ejecutar(any())).thenAnswer((i) -> ((Supplier<Object>) i.getArgument(0)).get());
    when(transacciones.consultar(any())).thenAnswer((i) -> ((Supplier<Object>) i.getArgument(0)).get());
    servicio = new DiccionarioRutasService(repo, transacciones, escenario);
  }

  private static DiccionarioPeticiones.Local local(String cod, String bultos, String unidades, String lineas) {
    return new DiccionarioPeticiones.Local(cod, new BigDecimal(bultos), new BigDecimal(unidades), new BigDecimal(lineas));
  }

  private static Ruta ruta(long id, String codigo, String tipo, Long clusterId, String frecuencia, String grupo) {
    return new Ruta(id, codigo, tipo, clusterId, frecuencia, grupo, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1,
        List.of("7875"));
  }

  // ---------------- Crear y editar ----------------

  @Test
  void unaRutaConUnSoloCodigoTieneEsosVolumenes() {
    var datos =
        DiccionarioRutasService.normalizar(
            new DiccionarioPeticiones.Ruta(" 2002 ", List.of(local("7875", "122.46", "5878.11", "221.91"))));

    assertThat(datos.codigo()).isEqualTo("2002");
    assertThat(datos.locales())
        .containsExactly(
            new Local("7875", 1, new BigDecimal("122.46"), new BigDecimal("5878.11"), new BigDecimal("221.91")));
    assertThat(datos.sumados()).isZero();
  }

  @Test
  void elCodigoRepetidoSeSumaComoEnElExcelSinDistinguirMayusculasNiEspacios() {
    var datos =
        DiccionarioRutasService.normalizar(
            new DiccionarioPeticiones.Ruta(
                "r 30",
                List.of(
                    local("f045", "100", "5000", "180"),
                    local("7880", "22.46", "878.11", "41.91"),
                    local("  F045 ", "1.50", "10", "2"))));

    assertThat(datos.codigo()).isEqualTo("R 30");
    assertThat(datos.locales())
        .containsExactly(
            new Local("F045", 2, new BigDecimal("101.50"), new BigDecimal("5010.00"), new BigDecimal("182.00")),
            new Local("7880", 1, new BigDecimal("22.46"), new BigDecimal("878.11"), new BigDecimal("41.91")));
    assertThat(datos.sumados()).isEqualTo(1);
  }

  @Test
  void unaLineaSinCodigoOUnaSumaDemasiadoGrandeSeRechazan() {
    assertThatThrownBy(
            () ->
                DiccionarioRutasService.normalizar(
                    new DiccionarioPeticiones.Ruta("2002", List.of(local("7875", "1", "1", "1"), local("  ", "1", "1", "1")))))
        .isInstanceOf(SolicitudInvalidaException.class)
        .hasMessageContaining("línea 2");
    assertThatThrownBy(
            () ->
                DiccionarioRutasService.normalizar(
                    new DiccionarioPeticiones.Ruta(
                        "2002", List.of(local("A", "9999999999.99", "0", "0"), local("A", "0.01", "0", "0")))))
        .isInstanceOf(SolicitudInvalidaException.class)
        .hasMessageContaining("A suma más");
  }

  @Test
  @SuppressWarnings("unchecked")
  void crearLaDejaEnDatosSinTipoConSusLocales() {
    when(repo.insertarRuta("2002", null, 0L)).thenReturn(9L);
    when(repo.rutasPorIds(List.of(9L))).thenReturn(List.of(ruta(9, "2002", null, null, null, null)));

    var respuesta =
        servicio.crear(new DiccionarioPeticiones.Ruta("2002", List.of(local("7875", "122.46", "5878.11", "221.91"))));

    verify(repo).existe("2002", null, null);
    ArgumentCaptor<List<Local>> locales = ArgumentCaptor.forClass(List.class);
    verify(repo).reemplazarLocales(eq(9L), locales.capture());
    assertThat(locales.getValue()).hasSize(1);
    assertThat(respuesta.datos().clusterId()).isNull();
    assertThat(respuesta.datos().tipo()).isNull();
    assertThat(respuesta.mensaje()).isEqualTo("Ruta 2002 creada en Datos.");
  }

  @Test
  void crearUnaRutaQueYaEstaEnDatosNoInsertaNada() {
    when(repo.existe("2001", null, null)).thenReturn(true);

    assertThatThrownBy(() -> servicio.crear(new DiccionarioPeticiones.Ruta("2001", List.of(local("1", "1", "1", "1")))))
        .isInstanceOf(ConflictoException.class)
        .hasMessage("La ruta 2001 (sin tipo) ya existe.");
    verify(repo, never()).insertarRuta(anyString(), any(), any());
  }

  @Test
  void editarConservaElTipoDeLaRuta() {
    when(repo.rutasPorIds(List.of(4L))).thenReturn(List.of(ruta(4, "2005", "B2C", 101L, "LXV", "1")));
    when(repo.actualizarRuta(4L, "2050", "B2C", 0L)).thenReturn(true);

    servicio.actualizar(4L, new DiccionarioPeticiones.Ruta("2050", List.of(local("1", "1", "1", "1"))));

    verify(repo).existe("2050", "B2C", 4L);
    verify(repo).actualizarRuta(4L, "2050", "B2C", 0L);
  }

  // ---------------- Bloques ----------------

  @Test
  void moverADatosLasDejaSinClusterTipoFrecuenciaNiGrupo() {
    when(repo.mover(List.of(1L, 2L), null, 0L)).thenReturn(2);
    when(repo.rutasPorIds(List.of(1L, 2L)))
        .thenReturn(List.of(ruta(1, "2002", null, null, null, null), ruta(2, "2015", null, null, null, null)));

    var respuesta = servicio.mover(new DiccionarioPeticiones.Mover(List.of(1L, 2L, 1L), null));

    verify(repo).repetidasConTipo(List.of(1L, 2L), null);
    verify(repo).mover(List.of(1L, 2L), null, 0L);
    assertThat(respuesta.mensaje()).isEqualTo("2 rutas movidas a Datos: sin cluster, tipo, frecuencia ni grupo.");
  }

  @Test
  void enDatosNoQuedanDosRutasConElMismoNumero() {
    when(repo.repetidasConTipo(List.of(1L, 2L), null)).thenReturn(List.of("2001"));

    assertThatThrownBy(() -> servicio.mover(new DiccionarioPeticiones.Mover(List.of(1L, 2L), null)))
        .isInstanceOf(ConflictoException.class)
        .hasMessage("No se movió a Datos: quedarían repetidas sin tipo las rutas 2001.");
    verify(repo, never()).mover(any(), any(), anyLong());
  }

  @Test
  void moverAUnClusterQueNoExisteOConRutasBorradasSeRechaza() {
    when(repo.clusters()).thenReturn(List.of(new Cluster(5L, 1, "CL1", false)));
    assertThatThrownBy(() -> servicio.mover(new DiccionarioPeticiones.Mover(List.of(1L), 99L)))
        .isInstanceOf(ConflictoException.class);
    verify(repo, never()).mover(any(), eq(99L), anyLong());

    when(repo.mover(List.of(1L, 2L), 5L, 0L)).thenReturn(1);
    assertThatThrownBy(() -> servicio.mover(new DiccionarioPeticiones.Mover(List.of(1L, 2L), 5L)))
        .isInstanceOf(ConflictoException.class)
        .hasMessageContaining("ya no existe");
  }

  // ---------------- Acciones ----------------

  @Test
  void laFrecuenciaYElGrupoSoloLleganALasRutasDeUnCluster() {
    when(repo.contar(List.of(1L, 2L, 3L))).thenReturn(3);
    when(repo.asignarFrecuencia(List.of(1L, 2L, 3L), "LXV", 0L)).thenReturn(List.of(1L, 2L));
    when(repo.rutasPorIds(List.of(1L, 2L)))
        .thenReturn(List.of(ruta(1, "2002", null, 5L, "LXV", null), ruta(2, "2015", null, 5L, "LXV", null)));

    var frecuencia = servicio.asignarFrecuencia(new DiccionarioPeticiones.Frecuencia(List.of(1L, 2L, 3L), "LXV"));

    assertThat(frecuencia.datos().omitidas()).isEqualTo(1);
    assertThat(frecuencia.mensaje())
        .isEqualTo("Frecuencia L-X-V asignada a 2 rutas. 1 ruta en Datos se dejó sin frecuencia.");

    when(repo.asignarGrupo(List.of(1L, 2L, 3L), "A", 0L)).thenReturn(List.of(1L, 2L));
    var grupo = servicio.asignarGrupo(new DiccionarioPeticiones.Grupo(List.of(1L, 2L, 3L), "  a "));

    verify(repo).asignarGrupo(List.of(1L, 2L, 3L), "A", 0L);
    assertThat(grupo.datos().omitidas()).isEqualTo(1);
  }

  @Test
  void elTipoSoloLlegaALasRutasDeUnClusterYNoDejaRepetidas() {
    when(repo.contar(List.of(1L, 2L, 3L))).thenReturn(3);
    when(repo.enCluster(List.of(1L, 2L, 3L))).thenReturn(List.of(1L, 2L));
    when(repo.rutasPorIds(List.of(1L, 2L)))
        .thenReturn(List.of(ruta(1, "2002", "B2C", 5L, null, null), ruta(2, "2015", "B2C", 5L, null, null)));

    var tipo = servicio.cambiarTipo(new DiccionarioPeticiones.Tipo(List.of(1L, 2L, 3L), "B2C"));

    verify(repo).repetidasConTipo(List.of(1L, 2L), "B2C");
    verify(repo).cambiarTipo(List.of(1L, 2L), "B2C", 0L);
    assertThat(tipo.mensaje()).isEqualTo("Tipo B2C asignado a 2 rutas. 1 ruta en Datos se dejó sin tipo.");

    when(repo.repetidasConTipo(List.of(1L, 2L), "B2B")).thenReturn(List.of("2002"));
    assertThatThrownBy(() -> servicio.cambiarTipo(new DiccionarioPeticiones.Tipo(List.of(1L, 2L, 3L), "B2B")))
        .isInstanceOf(ConflictoException.class)
        .hasMessage("No se cambió el tipo: quedarían repetidas como B2B las rutas 2002.");
    verify(repo, never()).cambiarTipo(any(), eq("B2B"), any());
  }

  @Test
  void siTodasEstanEnDatosNoSeAsignaFrecuenciaGrupoNiTipo() {
    when(repo.contar(List.of(1L))).thenReturn(1);
    when(repo.asignarFrecuencia(List.of(1L), "MJS", 0L)).thenReturn(List.of());
    when(repo.asignarGrupo(List.of(1L), "2", 0L)).thenReturn(List.of());
    when(repo.enCluster(List.of(1L))).thenReturn(List.of());

    assertThatThrownBy(() -> servicio.asignarFrecuencia(new DiccionarioPeticiones.Frecuencia(List.of(1L), "MJS")))
        .isInstanceOf(SolicitudInvalidaException.class)
        .hasMessageContaining("Datos");
    assertThatThrownBy(() -> servicio.asignarGrupo(new DiccionarioPeticiones.Grupo(List.of(1L), "2")))
        .isInstanceOf(SolicitudInvalidaException.class)
        .hasMessageContaining("Datos");
    assertThatThrownBy(() -> servicio.cambiarTipo(new DiccionarioPeticiones.Tipo(List.of(1L), "B2C")))
        .isInstanceOf(SolicitudInvalidaException.class)
        .hasMessageContaining("Datos");
    verify(repo, never()).cambiarTipo(any(), anyString(), any());
  }

  @Test
  void deshacerNoAceptaTipoFrecuenciaNiGrupoEnDatos() {
    for (var u :
        List.of(
            new DiccionarioPeticiones.Ubicacion(1L, null, "B2C", null, null),
            new DiccionarioPeticiones.Ubicacion(1L, null, null, "LXV", null),
            new DiccionarioPeticiones.Ubicacion(1L, null, null, null, "1"))) {
      assertThatThrownBy(() -> servicio.restaurar(new DiccionarioPeticiones.Restaurar(List.of(u))))
          .isInstanceOf(SolicitudInvalidaException.class);
    }
    verify(repo, never()).restaurar(any(), any());
  }

  // ---------------- Clusters ----------------

  @Test
  void quitarClustersDevuelveSusRutasADatos() {
    when(repo.ultimoOrden()).thenReturn(4);
    when(repo.rutasDeClustersDespuesDe(2)).thenReturn(List.of(7L, 8L));
    when(repo.quitarClustersDespuesDe(2, 0L)).thenReturn(12);

    var respuesta = servicio.cambiarClusters(new DiccionarioPeticiones.Clusters(2));

    verify(repo).bloquearClusters();
    verify(repo).repetidasConTipo(List.of(7L, 8L), null);
    verify(repo, never()).agregarClusters(anyInt(), anyInt());
    assertThat(respuesta.mensaje())
        .isEqualTo("Ahora hay 2 clusters; 12 rutas volvieron a Datos sin tipo, frecuencia ni grupo.");

    when(repo.ultimoOrden()).thenReturn(2);
    servicio.cambiarClusters(new DiccionarioPeticiones.Clusters(5));
    verify(repo).agregarClusters(3, 5);
  }

  @Test
  void noSeQuitaUnClusterSiEnDatosQuedariaUnNumeroRepetido() {
    when(repo.ultimoOrden()).thenReturn(3);
    when(repo.rutasDeClustersDespuesDe(2)).thenReturn(List.of(7L));
    when(repo.repetidasConTipo(List.of(7L), null)).thenReturn(List.of("2001"));

    assertThatThrownBy(() -> servicio.cambiarClusters(new DiccionarioPeticiones.Clusters(2)))
        .isInstanceOf(ConflictoException.class)
        .hasMessageContaining("2001");
    verify(repo, never()).quitarClustersDespuesDe(anyInt(), any());
  }

  @Test
  void elArregloDeIdsSoloLlevaNumeros() {
    assertThat(DiccionarioRutasRepository.arreglo(List.of(3L, 10L))).isEqualTo("{3,10}");
    assertThat(DiccionarioRutasRepository.arreglo(List.of())).isEqualTo("{}");
  }

  // ---------------- Escenario de Configuración ----------------

  @Test
  void cadaCambioPoneAlDiaElEscenarioConEsasRutas() {
    when(repo.clusters()).thenReturn(List.of(new Cluster(5L, 1, "CL1", false)));
    when(repo.mover(List.of(1L, 2L), 5L, 0L)).thenReturn(2);
    when(repo.contar(List.of(1L, 2L))).thenReturn(2);
    when(repo.asignarGrupo(List.of(1L, 2L), "1", 0L)).thenReturn(List.of(1L, 2L));

    servicio.mover(new DiccionarioPeticiones.Mover(List.of(1L, 2L), 5L));
    servicio.asignarGrupo(new DiccionarioPeticiones.Grupo(List.of(1L, 2L), "1"));

    verify(escenario, org.mockito.Mockito.times(2)).sincronizar(1L, List.of(1L, 2L), 0L);
  }

  @Test
  void unClusterCerradoEnConfiguracionNoAceptaCambios() {
    when(escenario.clustersCerrados(1L)).thenReturn(Set.of(5L));
    when(repo.contar(List.of(1L))).thenReturn(1);
    when(repo.algunaEnClusters(List.of(1L), Set.of(5L))).thenReturn(true);

    assertThatThrownBy(() -> servicio.mover(new DiccionarioPeticiones.Mover(List.of(2L), 5L)))
        .isInstanceOf(ConflictoException.class)
        .hasMessageContaining("cerrado en Configuración");
    assertThatThrownBy(() -> servicio.asignarFrecuencia(new DiccionarioPeticiones.Frecuencia(List.of(1L), "LXV")))
        .isInstanceOf(ConflictoException.class)
        .hasMessageContaining("cerrado en Configuración");
    verify(repo, never()).mover(any(), any(), anyLong());
    verify(repo, never()).asignarFrecuencia(any(), anyString(), any());
    verify(escenario, never()).sincronizar(anyLong(), any(), any());
  }

  @Test
  void laListaMarcaLosClustersCerradosYNoSeQuitaUnoCerrado() {
    when(escenario.clustersCerrados(1L)).thenReturn(Set.of(6L));
    when(repo.clusters()).thenReturn(List.of(new Cluster(5L, 1, "CL1", false), new Cluster(6L, 2, "CL2", false)));

    var diccionario = servicio.ver().datos();

    assertThat(diccionario.clusters()).extracting(Cluster::cerrado).containsExactly(false, true);

    when(repo.ultimoOrden()).thenReturn(2);
    assertThatThrownBy(() -> servicio.cambiarClusters(new DiccionarioPeticiones.Clusters(1)))
        .isInstanceOf(ConflictoException.class)
        .hasMessageContaining("CL2 está cerrado");
    verify(repo, never()).quitarClustersDespuesDe(anyInt(), any());
  }
}
