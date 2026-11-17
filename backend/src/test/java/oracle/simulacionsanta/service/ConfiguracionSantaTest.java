package oracle.simulacionsanta.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.simulacionsanta.model.OrdenLlenado;
import oracle.simulacionsanta.repository.UbicacionGuardada;
import oracle.simulacionsanta.request.ConfiguracionRequest;
import oracle.simulacionsanta.request.ConfiguracionRequest.PasilloRequest;
import oracle.simulacionsanta.request.ConfiguracionRequest.RampaRequest;
import org.junit.jupiter.api.Test;

class ConfiguracionSantaTest {

  @Test
  void unPedidoCompletoSeNormalizaEnOrden() {
    List<RampaRequest> rampas = new ArrayList<>(rampasFabrica());
    Collections.reverse(rampas);

    ConfiguracionSanta config =
        ConfiguracionSanta.desde(
            new ConfiguracionRequest(
                8, 150, List.of("PASILLOS_PRIMERO", "RAMPAS_PRIMERO", "RAMPAS_PRIMERO"), rampas, pasillosFabrica()));

    assertEquals(8, config.operarios());
    assertEquals(150, config.bultosHora());
    assertEquals(1200, config.capacidadHora());
    assertEquals(OrdenLlenado.PASILLOS_PRIMERO, config.orden(1));
    assertEquals(15, config.rampas().get(0).numero());
    assertEquals(33, config.rampas().get(18).numero());
    assertEquals(43, config.ubicaciones().size());
  }

  @Test
  void laFabricaYSuIdaYVueltaPorLaBaseCoinciden() {
    ConfiguracionSanta fabrica = ConfiguracionSanta.fabrica();
    assertEquals(10, fabrica.rampas().get(0).posiciones());
    assertEquals(14, fabrica.rampas().get(5).posiciones()); // rampa 20
    assertEquals(10, fabrica.rampas().get(10).posiciones()); // rampa 25

    ConfiguracionSanta guardada =
        ConfiguracionSanta.guardada(6, 120, fabrica.ordenComoTexto(), fabrica.ubicaciones());
    assertEquals(fabrica, guardada);

    // Una ubicación guardada pisa la de fábrica; las que falten quedan como en fábrica.
    ConfiguracionSanta cambiada =
        ConfiguracionSanta.guardada(
            6,
            120,
            fabrica.ordenComoTexto(),
            List.of(new UbicacionGuardada("RAMPA", 18, null, 4), new UbicacionGuardada("PASILLO", 2, 3, null)));
    assertEquals(new ConfiguracionSanta.Rampa(18, null, 4), cambiada.rampas().get(3));
    assertEquals(new ConfiguracionSanta.Pasillo(2, 3), cambiada.pasillos().get(1));
    assertEquals(new ConfiguracionSanta.Pasillo(1, 2), cambiada.pasillos().get(0));
  }

  @Test
  void posicionesMayoresALaCapacidadDeLaRampa() {
    assertMensaje(
        "La rampa 15 admite de 0 a 10 posiciones (llegaron 11).",
        conRampa(15, (r) -> new RampaRequest(15, 1, 11)));
    // La 19 es de 14: 14 sí vale.
    ConfiguracionSanta.desde(pedido(rampasCon(19, new RampaRequest(19, 1, 14)), pasillosFabrica()));
  }

  @Test
  void rampasYPasillosDebenSerExactamenteLosDelPlano() {
    assertMensaje(
        "La rampa 34 no existe: las rampas van de la 15 a la 33.",
        conRampa(33, (r) -> new RampaRequest(34, 1, 2)));
    assertMensaje(
        "La rampa 15 viene repetida.", conRampa(16, (r) -> new RampaRequest(15, 1, 2)));

    List<RampaRequest> faltan = new ArrayList<>(rampasFabrica());
    faltan.remove(0);
    assertMensaje("Faltan las rampas: 15.", pedido(faltan, pasillosFabrica()));

    List<PasilloRequest> pasillos = new ArrayList<>(pasillosFabrica());
    pasillos.set(23, new PasilloRequest(25, 3));
    assertMensaje(
        "El pasillo 25 no existe: los pasillos van del 1 al 24.", pedido(rampasFabrica(), pasillos));
  }

  @Test
  void rangosYOrdenDeLlenado() {
    assertMensaje(
        "Los operarios deben estar entre 1 y 99.",
        new ConfiguracionRequest(0, 120, orden(), rampasFabrica(), pasillosFabrica()));
    assertMensaje(
        "Los bultos por hora deben estar entre 1 y 9999.",
        new ConfiguracionRequest(6, 10_000, orden(), rampasFabrica(), pasillosFabrica()));
    assertMensaje(
        "El orden de llenado del cluster 2 debe ser RAMPAS_PRIMERO o PASILLOS_PRIMERO.",
        new ConfiguracionRequest(
            6, 120, List.of("RAMPAS_PRIMERO", "otro", "RAMPAS_PRIMERO"), rampasFabrica(), pasillosFabrica()));
    assertMensaje(
        "El cluster de la rampa 15 debe ser 1, 2, 3 o vacío.",
        conRampa(15, (r) -> new RampaRequest(15, 4, 2)));
  }

  // ------------------------------------------------------------------

  private static void assertMensaje(String esperado, ConfiguracionRequest pedido) {
    SolicitudInvalidaException error =
        assertThrows(SolicitudInvalidaException.class, () -> ConfiguracionSanta.desde(pedido));
    assertEquals(esperado, error.getMessage());
  }

  private static ConfiguracionRequest conRampa(int numero, UnaryOperator<RampaRequest> cambio) {
    List<RampaRequest> rampas = new ArrayList<>(rampasFabrica());
    int indice = numero - PlanoSantaAnita.PRIMERA_RAMPA;
    rampas.set(indice, cambio.apply(rampas.get(indice)));
    return pedido(rampas, pasillosFabrica());
  }

  private static List<RampaRequest> rampasCon(int numero, RampaRequest rampa) {
    List<RampaRequest> rampas = new ArrayList<>(rampasFabrica());
    rampas.set(numero - PlanoSantaAnita.PRIMERA_RAMPA, rampa);
    return rampas;
  }

  private static ConfiguracionRequest pedido(List<RampaRequest> rampas, List<PasilloRequest> pasillos) {
    return new ConfiguracionRequest(6, 120, orden(), rampas, pasillos);
  }

  private static List<String> orden() {
    return List.of("RAMPAS_PRIMERO", "PASILLOS_PRIMERO", "PASILLOS_PRIMERO");
  }

  private static List<RampaRequest> rampasFabrica() {
    return ConfiguracionSanta.fabrica().rampas().stream()
        .map((r) -> new RampaRequest(r.numero(), r.cluster(), r.posiciones()))
        .toList();
  }

  private static List<PasilloRequest> pasillosFabrica() {
    return ConfiguracionSanta.fabrica().pasillos().stream()
        .map((p) -> new PasilloRequest(p.numero(), p.cluster()))
        .toList();
  }
}
