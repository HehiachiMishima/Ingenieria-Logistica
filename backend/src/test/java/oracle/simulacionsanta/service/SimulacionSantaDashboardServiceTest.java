package oracle.simulacionsanta.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import oracle.common.config.Transacciones;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.simulacionsanta.controller.SimulacionSantaDashboardController;
import oracle.simulacionsanta.model.RegistroSanta;
import oracle.simulacionsanta.repository.AgregadoCluster;
import oracle.simulacionsanta.repository.Candado;
import oracle.simulacionsanta.repository.RegistroSantaRepository;
import oracle.simulacionsanta.repository.SimulacionSantaLotesRepository;
import oracle.simulacionsanta.repository.UbicacionGuardada;
import oracle.simulacionsanta.response.DashboardSanta;
import oracle.simulacionsanta.response.SimulacionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

/** Dashboard de un registro armado a partir de los agregados por cluster, sin BD. */
class SimulacionSantaDashboardServiceTest {

  private static final Long ID = 8L;

  private RegistroSantaRepository registros;
  private SimulacionSantaLotesRepository lotes;
  private SimulacionSantaDashboardService servicio;
  private RegistroSanta registro;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void preparar() {
    registros = mock(RegistroSantaRepository.class);
    lotes = mock(SimulacionSantaLotesRepository.class);
    Transacciones transacciones = mock(Transacciones.class);
    when(transacciones.consultar(any())).thenAnswer((i) -> ((Supplier<Object>) i.getArgument(0)).get());
    servicio = new SimulacionSantaDashboardService(registros, lotes, transacciones);

    registro = new RegistroSanta();
    registro.setId(ID);
    registro.setNombre("Despacho 17/11");
    registro.setEnvioActual(2);
    registro.setOperarios(7);
    registro.setBultosHora(130);
    registro.setOrdenCluster1("RAMPAS_PRIMERO");
    registro.setOrdenCluster2("PASILLOS_PRIMERO");
    registro.setOrdenCluster3("PASILLOS_PRIMERO");
    when(registros.findById(ID)).thenReturn(Optional.of(registro));
    when(lotes.ubicaciones(ID)).thenReturn(ubicaciones());
    when(lotes.candado()).thenReturn(Candado.ABIERTO);
  }

  @Test
  void armaLosTotalesYCadaClusterConSusPaletasCompletasPorEnvio() {
    // C2 no tiene filas: igual sale, en cero.
    when(lotes.agregadosPorCluster(ID))
        .thenReturn(
            List.of(
                new AgregadoCluster(1, 5, List.of(10, 4, 6), List.of(2, 3, 5)),
                new AgregadoCluster(3, 1, List.of(0, 0, 7), List.of(0, 0, 1))));

    DashboardSanta datos = servicio.dashboard(ID).datos();

    assertEquals(ID, datos.registroId());
    assertEquals("Despacho 17/11", datos.nombre());
    assertEquals(2, datos.envioActual());
    assertEquals(7, datos.operarios());
    assertEquals(130, datos.bultosHora());
    assertEquals(false, datos.bloqueado());
    assertEquals(27, datos.bultosTotal());
    assertEquals(6, datos.paletasTotal());
    assertEquals(
        List.of(new DashboardSanta.Envio(1, 10), new DashboardSanta.Envio(2, 4), new DashboardSanta.Envio(3, 13)),
        datos.envios());

    assertEquals(3, datos.clusters().size());
    DashboardSanta.Cluster c1 = datos.clusters().get(0);
    // Rampa 15 con 2 posiciones: de 5 paletas quedan 3 sin ubicar.
    assertEquals(new DashboardSanta.Cluster(
            1, 1, 0, 2, 5, 3, 20,
            List.of(
                new DashboardSanta.EnvioCluster(1, 10, 2),
                new DashboardSanta.EnvioCluster(2, 4, 3),
                new DashboardSanta.EnvioCluster(3, 6, 5))),
        c1);
    DashboardSanta.Cluster c2 = datos.clusters().get(1);
    assertEquals(List.of(2, 0, 1, 24, 0, 0, 0), List.of(c2.cluster(), c2.rampas(), c2.pasillos(),
        c2.posiciones(), c2.paletas(), c2.sinUbicar(), c2.bultos()));
    assertEquals(List.of(0, 0, 0), c2.porEnvio().stream().map(DashboardSanta.EnvioCluster::paletasCompletas).toList());
    DashboardSanta.Cluster c3 = datos.clusters().get(2);
    // Sin rampas ni pasillos: su única paleta queda sin ubicar.
    assertEquals(List.of(0, 1, 1), List.of(c3.posiciones(), c3.paletas(), c3.sinUbicar()));
  }

  @Test
  void elSinUbicarEsElMismoDeLaSimulacion() {
    when(lotes.agregadosPorCluster(ID))
        .thenReturn(
            List.of(
                new AgregadoCluster(1, 5, List.of(5, 0, 0), List.of(5, 5, 5)),
                new AgregadoCluster(2, 30, List.of(30, 0, 0), List.of(30, 30, 30)),
                new AgregadoCluster(3, 1, List.of(1, 0, 0), List.of(1, 1, 1))));
    List<PaletaSanta> paletas = new ArrayList<>();
    int orden = 1;
    for (int[] cuenta : new int[][] {{1, 5}, {2, 30}, {3, 1}}) {
      for (int i = 0; i < cuenta[1]; i++, orden++) {
        paletas.add(new PaletaSanta((long) orden, orden, "R" + orden, "L" + orden, cuenta[0], 1, 0, 0));
      }
    }
    ConfiguracionSanta config =
        ConfiguracionSanta.guardada(7, 130, SimulacionSantaRespuestas.ordenDe(registro), ubicaciones());
    SimulacionResponse simulacion = AsignadorUbicaciones.simular(ID, 2, 2, config, paletas);

    DashboardSanta datos = servicio.dashboard(ID).datos();

    for (int c = 0; c < 3; c++) {
      assertEquals(simulacion.clusters().get(c).sinUbicar(), datos.clusters().get(c).sinUbicar());
      assertEquals(simulacion.clusters().get(c).posiciones(), datos.clusters().get(c).posiciones());
    }
    assertEquals(List.of(3, 6, 1), datos.clusters().stream().map(DashboardSanta.Cluster::sinUbicar).toList());
  }

  @Test
  void conCandadoLoInforma() {
    when(lotes.agregadosPorCluster(ID)).thenReturn(List.of());
    when(lotes.candado()).thenReturn(new Candado(true, "Ana Pérez", null));

    DashboardSanta datos = servicio.dashboard(ID).datos();

    assertTrue(datos.bloqueado());
    assertEquals(0, datos.bultosTotal());
  }

  @Test
  void unRegistroQueNoExisteEs404() {
    when(registros.findById(99L)).thenReturn(Optional.empty());

    RecursoNoEncontradoException error =
        assertThrows(RecursoNoEncontradoException.class, () -> servicio.dashboard(99L));

    assertEquals("No existe el registro con id 99.", error.getMessage());
  }

  @Test
  void elEndpointPideElPermisoDelDashboard() throws Exception {
    Method metodo = SimulacionSantaDashboardController.class.getMethod("dashboard", Long.class);

    assertEquals(
        "hasAuthority('VER_DASHBOARD_SIMULACION_SANTA')",
        metodo.getAnnotation(PreAuthorize.class).value());
    assertEquals("/{id}/dashboard", metodo.getAnnotation(GetMapping.class).value()[0]);
  }

  /** Rampa 15 en C1 con 2 posiciones, pasillo 1 en C2; nada en C3 y lo demás sin cluster. */
  private static List<UbicacionGuardada> ubicaciones() {
    List<UbicacionGuardada> ubicaciones = new ArrayList<>();
    for (int n : PlanoSantaAnita.rampas()) {
      ubicaciones.add(new UbicacionGuardada("RAMPA", n, n == 15 ? 1 : null, n == 15 ? 2 : 0));
    }
    for (int n : PlanoSantaAnita.pasillos()) {
      ubicaciones.add(new UbicacionGuardada("PASILLO", n, n == 1 ? 2 : null, null));
    }
    return ubicaciones;
  }
}
