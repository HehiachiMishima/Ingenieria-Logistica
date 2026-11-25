package oracle.simulacion.diccionario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
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
  private DiccionarioRutasService servicio;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void preparar() {
    repo = mock(DiccionarioRutasRepository.class);
    Transacciones transacciones = mock(Transacciones.class);
    when(transacciones.ejecutar(any())).thenAnswer((i) -> ((Supplier<Object>) i.getArgument(0)).get());
    when(transacciones.consultar(any())).thenAnswer((i) -> ((Supplier<Object>) i.getArgument(0)).get());
    servicio = new DiccionarioRutasService(repo, transacciones);
  }

  private static DiccionarioPeticiones.Local local(String cod, String bultos, String unidades, String lineas) {
    return new DiccionarioPeticiones.Local(cod, new BigDecimal(bultos), new BigDecimal(unidades), new BigDecimal(lineas));
  }

  private static Ruta ruta(long id, String codigo, Long clusterId, String frecuencia, String grupo) {
    return new Ruta(id, codigo, "B2C", clusterId, frecuencia, grupo, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1,
        List.of("7875"));
  }

  @Test
  void unaRutaConUnSoloCodigoTieneEsosVolumenes() {
    var datos =
        DiccionarioRutasService.normalizar(
            new DiccionarioPeticiones.Ruta(" 2002 ", "B2C", List.of(local("7875", "122.46", "5878.11", "221.91"))));

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
                "B2B",
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
                    new DiccionarioPeticiones.Ruta("2002", "B2C", List.of(local("7875", "1", "1", "1"), local("  ", "1", "1", "1")))))
        .isInstanceOf(SolicitudInvalidaException.class)
        .hasMessageContaining("línea 2");
    assertThatThrownBy(
            () ->
                DiccionarioRutasService.normalizar(
                    new DiccionarioPeticiones.Ruta(
                        "2002",
                        "B2C",
                        List.of(local("A", "9999999999.99", "0", "0"), local("A", "0.01", "0", "0")))))
        .isInstanceOf(SolicitudInvalidaException.class)
        .hasMessageContaining("A suma más");
  }

  @Test
  void crearUnaRutaQueYaExisteNoInsertaNada() {
    when(repo.existe("2002", "B2C", null)).thenReturn(true);

    assertThatThrownBy(
            () -> servicio.crear(new DiccionarioPeticiones.Ruta("2002", "B2C", List.of(local("7875", "1", "1", "1")))))
        .isInstanceOf(ConflictoException.class)
        .hasMessage("La ruta 2002 B2C ya existe.");
    verify(repo, never()).insertarRuta(anyString(), anyString(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void crearLaDejaEnDatosConSusLocales() {
    when(repo.insertarRuta("2002", "B2C", 0L)).thenReturn(9L);
    when(repo.rutasPorIds(List.of(9L))).thenReturn(List.of(ruta(9, "2002", null, null, null)));

    var respuesta =
        servicio.crear(new DiccionarioPeticiones.Ruta("2002", "B2C", List.of(local("7875", "122.46", "5878.11", "221.91"))));

    ArgumentCaptor<List<Local>> locales = ArgumentCaptor.forClass(List.class);
    verify(repo).reemplazarLocales(eq(9L), locales.capture());
    assertThat(locales.getValue()).hasSize(1);
    assertThat(respuesta.datos().clusterId()).isNull();
    assertThat(respuesta.mensaje()).isEqualTo("Ruta 2002 B2C creada en Datos.");
  }

  @Test
  void moverADatosPasaSinClusterYMoverAUnClusterQueNoExisteSeRechaza() {
    when(repo.mover(List.of(1L, 2L), null, 0L)).thenReturn(2);
    when(repo.rutasPorIds(List.of(1L, 2L)))
        .thenReturn(List.of(ruta(1, "2002", null, null, null), ruta(2, "2015", null, null, null)));

    var respuesta = servicio.mover(new DiccionarioPeticiones.Mover(List.of(1L, 2L, 1L), null));

    verify(repo).mover(List.of(1L, 2L), null, 0L);
    assertThat(respuesta.mensaje()).isEqualTo("2 rutas movidas a Datos: sin cluster, frecuencia ni grupo.");

    when(repo.clusters()).thenReturn(List.of(new Cluster(5L, 1, "CL1")));
    assertThatThrownBy(() -> servicio.mover(new DiccionarioPeticiones.Mover(List.of(1L), 99L)))
        .isInstanceOf(ConflictoException.class);
    verify(repo, never()).mover(any(), eq(99L), anyLong());
  }

  @Test
  void moverRutasQueYaNoExistenDeshaceTodo() {
    when(repo.clusters()).thenReturn(List.of(new Cluster(5L, 1, "CL1")));
    when(repo.mover(List.of(1L, 2L), 5L, 0L)).thenReturn(1);

    assertThatThrownBy(() -> servicio.mover(new DiccionarioPeticiones.Mover(List.of(1L, 2L), 5L)))
        .isInstanceOf(ConflictoException.class)
        .hasMessageContaining("ya no existe");
  }

  @Test
  void laFrecuenciaYElGrupoSoloLleganALasRutasDeUnCluster() {
    when(repo.contar(List.of(1L, 2L, 3L))).thenReturn(3);
    when(repo.asignarFrecuencia(List.of(1L, 2L, 3L), "LXV", 0L)).thenReturn(List.of(1L, 2L));
    when(repo.rutasPorIds(List.of(1L, 2L)))
        .thenReturn(List.of(ruta(1, "2002", 5L, "LXV", null), ruta(2, "2015", 5L, "LXV", null)));

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
  void siTodasEstanEnDatosNoSeAsignaFrecuenciaNiGrupo() {
    when(repo.contar(List.of(1L))).thenReturn(1);
    when(repo.asignarFrecuencia(List.of(1L), "MJS", 0L)).thenReturn(List.of());
    when(repo.asignarGrupo(List.of(1L), "2", 0L)).thenReturn(List.of());

    assertThatThrownBy(() -> servicio.asignarFrecuencia(new DiccionarioPeticiones.Frecuencia(List.of(1L), "MJS")))
        .isInstanceOf(SolicitudInvalidaException.class)
        .hasMessageContaining("Datos");
    assertThatThrownBy(() -> servicio.asignarGrupo(new DiccionarioPeticiones.Grupo(List.of(1L), "2")))
        .isInstanceOf(SolicitudInvalidaException.class)
        .hasMessageContaining("Datos");
  }

  @Test
  void cambiarElTipoNoDejaRutasRepetidas() {
    when(repo.contar(List.of(1L, 2L))).thenReturn(2);
    when(repo.repetidasConTipo(List.of(1L, 2L), "B2B")).thenReturn(List.of("2002"));

    assertThatThrownBy(() -> servicio.cambiarTipo(new DiccionarioPeticiones.Tipo(List.of(1L, 2L), "B2B")))
        .isInstanceOf(ConflictoException.class)
        .hasMessage("No se cambió el tipo: quedarían repetidas como B2B las rutas 2002.");
    verify(repo, never()).cambiarTipo(any(), anyString(), any());
  }

  @Test
  void deshacerNoAceptaFrecuenciaNiGrupoEnDatos() {
    assertThatThrownBy(
            () ->
                servicio.restaurar(
                    new DiccionarioPeticiones.Restaurar(
                        List.of(new DiccionarioPeticiones.Ubicacion(1L, null, null, "1")))))
        .isInstanceOf(SolicitudInvalidaException.class);
    verify(repo, never()).restaurar(any(), any());
  }

  @Test
  void quitarClustersDevuelveSusRutasADatos() {
    when(repo.ultimoOrden()).thenReturn(4);
    when(repo.quitarClustersDespuesDe(2, 0L)).thenReturn(12);

    var respuesta = servicio.cambiarClusters(new DiccionarioPeticiones.Clusters(2));

    verify(repo).bloquearClusters();
    verify(repo, never()).agregarClusters(anyInt(), anyInt());
    assertThat(respuesta.mensaje()).isEqualTo("Ahora hay 2 clusters; 12 rutas volvieron a Datos sin frecuencia ni grupo.");

    when(repo.ultimoOrden()).thenReturn(2);
    servicio.cambiarClusters(new DiccionarioPeticiones.Clusters(5));
    verify(repo).agregarClusters(3, 5);
  }

  @Test
  void elArregloDeIdsSoloLlevaNumeros() {
    assertThat(DiccionarioRutasRepository.arreglo(List.of(3L, 10L))).isEqualTo("{3,10}");
    assertThat(DiccionarioRutasRepository.arreglo(List.of())).isEqualTo("{}");
    verify(repo, never()).mover(any(), isNull(), anyLong());
  }

  @Test
  void unaRutaSinTipoSeCreaEnDatosYTampocoSeRepite() {
    when(repo.insertarRuta("2002", null, 0L)).thenReturn(7L);
    when(repo.rutasPorIds(List.of(7L)))
        .thenReturn(
            List.of(
                new Ruta(7L, "2002", null, null, null, null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 2,
                    List.of("2514", "2526"))));

    var respuesta =
        servicio.crear(
            new DiccionarioPeticiones.Ruta(
                "2002", null, List.of(local("2514", "4.71", "169.12", "0"), local("2526", "3.83", "167.62", "0"))));

    verify(repo).existe("2002", null, null);
    assertThat(respuesta.mensaje()).isEqualTo("Ruta 2002 (sin tipo) creada en Datos.");

    when(repo.existe("2001", null, null)).thenReturn(true);
    assertThatThrownBy(
            () -> servicio.crear(new DiccionarioPeticiones.Ruta("2001", null, List.of(local("1", "1", "1", "1")))))
        .isInstanceOf(ConflictoException.class)
        .hasMessage("La ruta 2001 (sin tipo) ya existe.");
  }
}
