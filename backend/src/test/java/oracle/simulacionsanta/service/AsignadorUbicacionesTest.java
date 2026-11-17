package oracle.simulacionsanta.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import oracle.simulacionsanta.model.EstadoPaleta;
import oracle.simulacionsanta.model.Lado;
import oracle.simulacionsanta.model.OrdenLlenado;
import oracle.simulacionsanta.model.TipoUbicacion;
import oracle.simulacionsanta.response.ClusterSimulacion;
import oracle.simulacionsanta.response.PaletaSimulacion;
import oracle.simulacionsanta.response.SimulacionResponse;
import oracle.simulacionsanta.response.Ubicacion;
import org.junit.jupiter.api.Test;

class AsignadorUbicacionesTest {

  private static final OrdenLlenado RAMPAS = OrdenLlenado.RAMPAS_PRIMERO;
  private static final OrdenLlenado PASILLOS = OrdenLlenado.PASILLOS_PRIMERO;

  // ------------------------------------------------------------------
  // Huecos
  // ------------------------------------------------------------------

  @Test
  void rampaLlenaPrimeroIzquierdaYLuegoDerechaConLaMitadRedondeadaHaciaArriba() {
    assertEquals(
        List.of(
            rampa(20, Lado.IZQ, 1),
            rampa(20, Lado.IZQ, 2),
            rampa(20, Lado.IZQ, 3),
            rampa(20, Lado.IZQ, 4),
            rampa(20, Lado.DER, 1),
            rampa(20, Lado.DER, 2),
            rampa(20, Lado.DER, 3)),
        AsignadorUbicaciones.huecosRampa(20, 7));
    assertEquals(10, AsignadorUbicaciones.huecosRampa(15, 10).size());
    assertEquals(rampa(15, Lado.DER, 5), AsignadorUbicaciones.huecosRampa(15, 10).get(9));
    assertEquals(List.of(rampa(16, Lado.IZQ, 1)), AsignadorUbicaciones.huecosRampa(16, 1));
    assertEquals(List.of(), AsignadorUbicaciones.huecosRampa(17, 0));
  }

  @Test
  void pasilloLlenaFilaPorFilaIzquierdaYDerecha() {
    List<Ubicacion> huecos = AsignadorUbicaciones.huecosPasillo(7);
    assertEquals(24, huecos.size());
    assertEquals(
        List.of(pasillo(7, Lado.IZQ, 1), pasillo(7, Lado.DER, 1), pasillo(7, Lado.IZQ, 2)),
        huecos.subList(0, 3));
    assertEquals(pasillo(7, Lado.DER, 12), huecos.get(23));
  }

  @Test
  void rampasYPasillosDelClusterVanEnOrdenAscendente() {
    // C1: rampas 20 (3 posiciones) y 16 (4), pasillos 5 y 2; se pasan desordenados.
    ConfiguracionSanta config =
        config(
            List.of(RAMPAS, PASILLOS, PASILLOS),
            Map.of(20, new int[] {1, 3}, 16, new int[] {1, 4}),
            Map.of(5, 1, 2, 1));

    List<Ubicacion> huecos = AsignadorUbicaciones.huecosCluster(config, 1);

    assertEquals(3 + 4 + 48, huecos.size());
    assertEquals(
        List.of(
            rampa(16, Lado.IZQ, 1),
            rampa(16, Lado.IZQ, 2),
            rampa(16, Lado.DER, 1),
            rampa(16, Lado.DER, 2),
            rampa(20, Lado.IZQ, 1),
            rampa(20, Lado.IZQ, 2),
            rampa(20, Lado.DER, 1),
            pasillo(2, Lado.IZQ, 1)),
        huecos.subList(0, 8));
    assertEquals(pasillo(5, Lado.IZQ, 1), huecos.get(7 + 24));
  }

  @Test
  void rampasPrimeroYPasillosPrimero() {
    Map<Integer, int[]> rampas = Map.of(15, new int[] {1, 2});
    Map<Integer, Integer> pasillos = Map.of(1, 1);
    List<PaletaSanta> paletas = new ArrayList<>();
    for (int i = 1; i <= 26; i++) {
      paletas.add(paleta(i, i + 1, 1, 1, 0, 0));
    }

    List<Ubicacion> conRampas =
        AsignadorUbicaciones.asignar(config(List.of(RAMPAS, RAMPAS, RAMPAS), rampas, pasillos), paletas);
    assertEquals(rampa(15, Lado.IZQ, 1), conRampas.get(0));
    assertEquals(rampa(15, Lado.DER, 1), conRampas.get(1));
    assertEquals(pasillo(1, Lado.IZQ, 1), conRampas.get(2));
    assertEquals(pasillo(1, Lado.DER, 12), conRampas.get(25));

    List<Ubicacion> conPasillos =
        AsignadorUbicaciones.asignar(
            config(List.of(PASILLOS, RAMPAS, RAMPAS), rampas, pasillos), paletas);
    assertEquals(pasillo(1, Lado.IZQ, 1), conPasillos.get(0));
    assertEquals(pasillo(1, Lado.DER, 12), conPasillos.get(23));
    assertEquals(rampa(15, Lado.IZQ, 1), conPasillos.get(24));
    assertEquals(rampa(15, Lado.DER, 1), conPasillos.get(25));
  }

  // ------------------------------------------------------------------
  // Asignación
  // ------------------------------------------------------------------

  @Test
  void lasPaletasOcupanLosHuecosEnElOrdenDelExcelYLasQueSobranQuedanSinUbicacion() {
    // C1 con 3 huecos (rampa 15 con 3 posiciones); C2 y C3 sin ubicaciones.
    ConfiguracionSanta config =
        config(List.of(RAMPAS, RAMPAS, RAMPAS), Map.of(15, new int[] {1, 3}), Map.of());
    List<PaletaSanta> paletas =
        List.of(
            paleta(10, 9, 1, 1, 0, 0),
            paleta(11, 3, 1, 1, 0, 0),
            paleta(12, 7, 2, 1, 0, 0),
            paleta(13, 5, 1, 1, 0, 0),
            paleta(14, 4, 1, 1, 0, 0));

    SimulacionResponse sim = AsignadorUbicaciones.simular(99L, 0, 0, config, paletas);

    assertEquals(List.of(11L, 14L, 13L, 12L, 10L), sim.paletas().stream().map(PaletaSimulacion::id).toList());
    assertEquals(rampa(15, Lado.IZQ, 1), ubicacion(sim, 11L));
    assertEquals(rampa(15, Lado.IZQ, 2), ubicacion(sim, 14L));
    assertEquals(rampa(15, Lado.DER, 1), ubicacion(sim, 13L));
    assertNull(ubicacion(sim, 10L)); // sobra en C1
    assertNull(ubicacion(sim, 12L)); // C2 no tiene huecos

    assertEquals(5, sim.kpis().paletas());
    assertEquals(3, sim.kpis().ubicadas());
    assertEquals(2, sim.kpis().sinUbicar());

    ClusterSimulacion c1 = sim.clusters().get(0);
    assertEquals(new ClusterSimulacion(1, 4, 3, 3, 1, List.of(15), List.of()), c1);
    ClusterSimulacion c2 = sim.clusters().get(1);
    assertEquals(new ClusterSimulacion(2, 1, 0, 0, 1, List.of(), List.of()), c2);
    assertEquals(3, sim.clusters().size());
  }

  @Test
  void cadaClusterUsaSoloSusUbicaciones() {
    // Fábrica: C1 en las rampas, C2 en los pasillos 1-12, C3 en los 13-24.
    ConfiguracionSanta fabrica = ConfiguracionSanta.fabrica();
    List<PaletaSanta> paletas =
        List.of(paleta(1, 2, 3, 1, 0, 0), paleta(2, 3, 2, 1, 0, 0), paleta(3, 4, 1, 1, 0, 0));

    SimulacionResponse sim = AsignadorUbicaciones.simular(1L, 0, 0, fabrica, paletas);

    assertEquals(pasillo(13, Lado.IZQ, 1), ubicacion(sim, 1L));
    assertEquals(pasillo(1, Lado.IZQ, 1), ubicacion(sim, 2L));
    assertEquals(rampa(15, Lado.IZQ, 1), ubicacion(sim, 3L));
    assertEquals(5 * 10 + 14 * 14, sim.clusters().get(0).posiciones());
    assertEquals(12 * 24, sim.clusters().get(1).posiciones());
    assertEquals(12 * 24, sim.clusters().get(2).posiciones());
    assertEquals(19, sim.clusters().get(0).rampas().size());
    assertEquals(List.of(13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24), sim.clusters().get(2).pasillos());
  }

  @Test
  void laAsignacionNoDependeDelEnvio() {
    ConfiguracionSanta fabrica = ConfiguracionSanta.fabrica();
    List<PaletaSanta> paletas = List.of(paleta(1, 2, 1, 0, 0, 5), paleta(2, 3, 1, 5, 0, 0));

    for (int envio = 0; envio <= 3; envio++) {
      SimulacionResponse sim = AsignadorUbicaciones.simular(1L, envio, 2, fabrica, paletas);
      assertEquals(rampa(15, Lado.IZQ, 1), ubicacion(sim, 1L));
      assertEquals(rampa(15, Lado.IZQ, 2), ubicacion(sim, 2L));
      assertEquals(envio, sim.envio());
      assertEquals(2, sim.envioActual());
    }
  }

  // ------------------------------------------------------------------
  // Estados y KPIs
  // ------------------------------------------------------------------

  @Test
  void estadosPorEnvio() {
    ConfiguracionSanta fabrica = ConfiguracionSanta.fabrica();
    // A: 5 + 0 + 5; B: solo en el envío 2; C: solo en el 1.
    List<PaletaSanta> paletas =
        List.of(paleta(1, 2, 1, 5, 0, 5), paleta(2, 3, 2, 0, 4, 0), paleta(3, 4, 3, 6, 0, 0));

    assertEquals(
        List.of(EstadoPaleta.PENDIENTE, EstadoPaleta.PENDIENTE, EstadoPaleta.PENDIENTE),
        estados(AsignadorUbicaciones.simular(1L, 0, 0, fabrica, paletas)));
    assertEquals(
        List.of(EstadoPaleta.EN_CURSO, EstadoPaleta.PENDIENTE, EstadoPaleta.COMPLETA),
        estados(AsignadorUbicaciones.simular(1L, 1, 0, fabrica, paletas)));
    assertEquals(
        List.of(EstadoPaleta.EN_CURSO, EstadoPaleta.COMPLETA, EstadoPaleta.COMPLETA),
        estados(AsignadorUbicaciones.simular(1L, 2, 0, fabrica, paletas)));
    assertEquals(
        List.of(EstadoPaleta.COMPLETA, EstadoPaleta.COMPLETA, EstadoPaleta.COMPLETA),
        estados(AsignadorUbicaciones.simular(1L, 3, 0, fabrica, paletas)));

    SimulacionResponse enUno = AsignadorUbicaciones.simular(1L, 1, 0, fabrica, paletas);
    PaletaSimulacion a = enUno.paletas().get(0);
    assertEquals(List.of(5, 0, 5), a.envios());
    assertEquals(5, a.recibidos());
    assertEquals(10, a.total());
    assertEquals(1, enUno.kpis().completas());
    assertEquals(1, enUno.kpis().enCurso());
    assertEquals(20, enUno.kpis().bultosTotal());
    assertEquals(11, enUno.kpis().bultosRecibidos());
    assertEquals(List.of(11, 4, 5), enUno.kpis().bultosEnvios());
  }

  @Test
  void minutosRestantesConUnDecimal() {
    // 6 operarios × 120 = 720 bultos/hora; faltan 300 bultos → 25 minutos.
    ConfiguracionSanta fabrica = ConfiguracionSanta.fabrica();
    List<PaletaSanta> paletas = List.of(paleta(1, 2, 1, 100, 200, 100));

    SimulacionResponse sim = AsignadorUbicaciones.simular(1L, 1, 1, fabrica, paletas);

    assertEquals(720, sim.kpis().capacidadHora());
    assertEquals(25.0, sim.kpis().minutosRestantes());
    assertEquals(0.0, AsignadorUbicaciones.simular(1L, 3, 3, fabrica, paletas).kpis().minutosRestantes());
    // 7 × 60 / 9 = 46,666… → 46,7
    assertEquals(46.7, AsignadorUbicaciones.minutosRestantes(7, 9));
    // 1 × 60 / 720 = 0,0833… → 0,1
    assertEquals(0.1, AsignadorUbicaciones.minutosRestantes(1, 720));
  }

  @Test
  void envioFueraDeRangoEsUnError() {
    ConfiguracionSanta fabrica = ConfiguracionSanta.fabrica();
    assertThrows(
        IllegalArgumentException.class,
        () -> AsignadorUbicaciones.simular(1L, 4, 0, fabrica, List.of()));
  }

  // ------------------------------------------------------------------

  private static List<EstadoPaleta> estados(SimulacionResponse sim) {
    return sim.paletas().stream().map(PaletaSimulacion::estado).toList();
  }

  private static Ubicacion ubicacion(SimulacionResponse sim, Long id) {
    return sim.paletas().stream()
        .filter((p) -> p.id().equals(id))
        .findFirst()
        .orElseThrow()
        .ubicacion();
  }

  private static PaletaSanta paleta(long id, int orden, int cluster, int e1, int e2, int e3) {
    return new PaletaSanta(id, orden, "R" + id, "Local " + id, cluster, e1, e2, e3);
  }

  private static Ubicacion rampa(int numero, Lado lado, int fila) {
    return new Ubicacion(TipoUbicacion.RAMPA, numero, lado, fila);
  }

  private static Ubicacion pasillo(int numero, Lado lado, int fila) {
    return new Ubicacion(TipoUbicacion.PASILLO, numero, lado, fila);
  }

  /**
   * Las 19 rampas y 24 pasillos sin cluster salvo los indicados.
   *
   * @param rampas número → {cluster, posiciones}
   * @param pasillos número → cluster
   */
  private static ConfiguracionSanta config(
      List<OrdenLlenado> orden, Map<Integer, int[]> rampas, Map<Integer, Integer> pasillos) {
    List<ConfiguracionSanta.Rampa> listaRampas = new ArrayList<>();
    for (int n : PlanoSantaAnita.rampas()) {
      int[] valor = rampas.get(n);
      listaRampas.add(
          valor == null
              ? new ConfiguracionSanta.Rampa(n, null, PlanoSantaAnita.capacidadRampa(n))
              : new ConfiguracionSanta.Rampa(n, valor[0], valor[1]));
    }
    List<ConfiguracionSanta.Pasillo> listaPasillos = new ArrayList<>();
    for (int n : PlanoSantaAnita.pasillos()) {
      listaPasillos.add(new ConfiguracionSanta.Pasillo(n, pasillos.get(n)));
    }
    return new ConfiguracionSanta(6, 120, orden, listaRampas, listaPasillos);
  }
}
