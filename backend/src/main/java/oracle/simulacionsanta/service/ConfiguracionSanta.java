package oracle.simulacionsanta.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.simulacionsanta.model.OrdenLlenado;
import oracle.simulacionsanta.model.TipoUbicacion;
import oracle.simulacionsanta.repository.UbicacionGuardada;
import oracle.simulacionsanta.request.ConfiguracionRequest;

/**
 * Configuración de un registro (o de los predeterminados), ya validada contra el plano: las 19
 * rampas y los 24 pasillos en orden, cada uno con su cluster (null = sin cluster) y, en las
 * rampas, las posiciones habilitadas (0 a su capacidad).
 */
public record ConfiguracionSanta(
    int operarios,
    int bultosHora,
    List<OrdenLlenado> ordenClusters,
    List<Rampa> rampas,
    List<Pasillo> pasillos) {

  public static final int MIN_OPERARIOS = 1;
  public static final int MAX_OPERARIOS = 99;
  public static final int MIN_BULTOS_HORA = 1;
  public static final int MAX_BULTOS_HORA = 9_999;

  /** Valores de fábrica (los mismos que siembra postgresql.sql). */
  public static final int OPERARIOS_FABRICA = 6;
  public static final int BULTOS_HORA_FABRICA = 120;

  public record Rampa(int numero, Integer cluster, int posiciones) {
    public int capacidad() {
      return PlanoSantaAnita.capacidadRampa(numero);
    }
  }

  public record Pasillo(int numero, Integer cluster) {}

  public ConfiguracionSanta {
    ordenClusters = List.copyOf(ordenClusters);
    rampas =
        rampas.stream().sorted(Comparator.comparingInt(Rampa::numero)).toList();
    pasillos =
        pasillos.stream().sorted(Comparator.comparingInt(Pasillo::numero)).toList();
  }

  /** Orden de llenado del cluster (1 a 3). */
  public OrdenLlenado orden(int cluster) {
    return ordenClusters.get(cluster - 1);
  }

  /** Bultos por hora de todo el equipo: operarios × bultos por hora. */
  public int capacidadHora() {
    return operarios * bultosHora;
  }

  /**
   * Predeterminados de fábrica: rampas 15–33 en C1 con todas sus posiciones, pasillos 1–12 en C2
   * y 13–24 en C3; C1 llena primero sus rampas, C2 y C3 sus pasillos; 6 operarios a 120
   * bultos/hora.
   */
  public static ConfiguracionSanta fabrica() {
    List<Rampa> rampas =
        PlanoSantaAnita.rampas().stream()
            .map((n) -> new Rampa(n, 1, PlanoSantaAnita.capacidadRampa(n)))
            .toList();
    List<Pasillo> pasillos =
        PlanoSantaAnita.pasillos().stream().map((n) -> new Pasillo(n, n <= 12 ? 2 : 3)).toList();
    return new ConfiguracionSanta(
        OPERARIOS_FABRICA,
        BULTOS_HORA_FABRICA,
        List.of(
            OrdenLlenado.RAMPAS_PRIMERO, OrdenLlenado.PASILLOS_PRIMERO, OrdenLlenado.PASILLOS_PRIMERO),
        rampas,
        pasillos);
  }

  /**
   * Configuración guardada en la base: los valores de la fila más sus ubicaciones. Una rampa o
   * pasillo que faltara en la base (no debería: se crean las 43 juntas) conserva el valor de
   * fábrica.
   */
  public static ConfiguracionSanta guardada(
      int operarios, int bultosHora, List<String> ordenClusters, List<UbicacionGuardada> ubicaciones) {
    ConfiguracionSanta base = fabrica();
    Map<Integer, Rampa> rampas = new HashMap<>();
    base.rampas().forEach((r) -> rampas.put(r.numero(), r));
    Map<Integer, Pasillo> pasillos = new HashMap<>();
    base.pasillos().forEach((p) -> pasillos.put(p.numero(), p));
    for (UbicacionGuardada u : ubicaciones) {
      if (TipoUbicacion.RAMPA.name().equals(u.tipo()) && rampas.containsKey(u.numero())) {
        int maximo = PlanoSantaAnita.capacidadRampa(u.numero());
        int posiciones = u.posiciones() == null ? 0 : Math.clamp(u.posiciones(), 0, maximo);
        rampas.put(u.numero(), new Rampa(u.numero(), u.cluster(), posiciones));
      } else if (TipoUbicacion.PASILLO.name().equals(u.tipo()) && pasillos.containsKey(u.numero())) {
        pasillos.put(u.numero(), new Pasillo(u.numero(), u.cluster()));
      }
    }
    return new ConfiguracionSanta(
        operarios,
        bultosHora,
        ordenClusters.stream().map(OrdenLlenado::valueOf).toList(),
        new ArrayList<>(rampas.values()),
        new ArrayList<>(pasillos.values()));
  }

  /** Las 43 filas a guardar (rampas y pasillos). */
  public List<UbicacionGuardada> ubicaciones() {
    List<UbicacionGuardada> filas = new ArrayList<>(rampas.size() + pasillos.size());
    rampas.forEach(
        (r) ->
            filas.add(
                new UbicacionGuardada(
                    TipoUbicacion.RAMPA.name(), r.numero(), r.cluster(), r.posiciones())));
    pasillos.forEach(
        (p) ->
            filas.add(
                new UbicacionGuardada(TipoUbicacion.PASILLO.name(), p.numero(), p.cluster(), null)));
    return filas;
  }

  /** Nombres de OrdenLlenado de C1, C2 y C3, como se guardan. */
  public List<String> ordenComoTexto() {
    return ordenClusters.stream().map(OrdenLlenado::name).toList();
  }

  /**
   * Valida un pedido contra el plano y lo normaliza. Repite las reglas de las anotaciones del
   * request (así sirve también sin Bean Validation) y agrega las que cruzan campos: números de
   * rampa y pasillo del plano, sin repetidos ni faltantes, y posiciones entre 0 y la capacidad de
   * cada rampa. Cualquier problema es un 400 con un mensaje claro.
   */
  public static ConfiguracionSanta desde(ConfiguracionRequest pedido) {
    if (pedido == null) {
      throw invalida("Falta la configuración.");
    }
    int operarios =
        enRango(pedido.operarios(), MIN_OPERARIOS, MAX_OPERARIOS, "Los operarios deben estar entre 1 y 99.");
    int bultosHora =
        enRango(
            pedido.bultosHora(),
            MIN_BULTOS_HORA,
            MAX_BULTOS_HORA,
            "Los bultos por hora deben estar entre 1 y 9999.");
    List<OrdenLlenado> orden = orden(pedido.ordenClusters());
    return new ConfiguracionSanta(
        operarios, bultosHora, orden, rampas(pedido.rampas()), pasillos(pedido.pasillos()));
  }

  private static List<OrdenLlenado> orden(List<String> valores) {
    if (valores == null || valores.size() != PlanoSantaAnita.CLUSTERS) {
      throw invalida("El orden de llenado lleva un valor por cluster: C1, C2 y C3.");
    }
    List<OrdenLlenado> orden = new ArrayList<>(PlanoSantaAnita.CLUSTERS);
    for (int i = 0; i < valores.size(); i++) {
      String valor = valores.get(i);
      OrdenLlenado elegido = null;
      for (OrdenLlenado opcion : OrdenLlenado.values()) {
        if (opcion.name().equals(valor)) {
          elegido = opcion;
        }
      }
      if (elegido == null) {
        throw invalida(
            "El orden de llenado del cluster " + (i + 1) + " debe ser RAMPAS_PRIMERO o PASILLOS_PRIMERO.");
      }
      orden.add(elegido);
    }
    return orden;
  }

  private static List<Rampa> rampas(List<ConfiguracionRequest.RampaRequest> pedido) {
    if (pedido == null) {
      throw invalida("Deben venir las 19 rampas (15 a 33).");
    }
    Map<Integer, Rampa> vistas = new HashMap<>();
    for (ConfiguracionRequest.RampaRequest rampa : pedido) {
      if (rampa == null || rampa.numero() == null) {
        throw invalida("Cada rampa lleva su número.");
      }
      int numero = rampa.numero();
      if (!PlanoSantaAnita.esRampa(numero)) {
        throw invalida("La rampa " + numero + " no existe: las rampas van de la 15 a la 33.");
      }
      if (vistas.containsKey(numero)) {
        throw invalida("La rampa " + numero + " viene repetida.");
      }
      Integer cluster = cluster(rampa.cluster(), "la rampa " + numero);
      int capacidad = PlanoSantaAnita.capacidadRampa(numero);
      if (rampa.posiciones() == null) {
        throw invalida("Indica las posiciones de la rampa " + numero + ".");
      }
      if (rampa.posiciones() < 0 || rampa.posiciones() > capacidad) {
        throw invalida(
            "La rampa " + numero + " admite de 0 a " + capacidad + " posiciones (llegaron "
                + rampa.posiciones() + ").");
      }
      vistas.put(numero, new Rampa(numero, cluster, rampa.posiciones()));
    }
    exigirTodas(vistas.keySet(), PlanoSantaAnita.rampas(), "Faltan las rampas: ");
    return new ArrayList<>(vistas.values());
  }

  private static List<Pasillo> pasillos(List<ConfiguracionRequest.PasilloRequest> pedido) {
    if (pedido == null) {
      throw invalida("Deben venir los 24 pasillos (1 a 24).");
    }
    Map<Integer, Pasillo> vistos = new HashMap<>();
    for (ConfiguracionRequest.PasilloRequest pasillo : pedido) {
      if (pasillo == null || pasillo.numero() == null) {
        throw invalida("Cada pasillo lleva su número.");
      }
      int numero = pasillo.numero();
      if (!PlanoSantaAnita.esPasillo(numero)) {
        throw invalida("El pasillo " + numero + " no existe: los pasillos van del 1 al 24.");
      }
      if (vistos.containsKey(numero)) {
        throw invalida("El pasillo " + numero + " viene repetido.");
      }
      vistos.put(numero, new Pasillo(numero, cluster(pasillo.cluster(), "el pasillo " + numero)));
    }
    exigirTodas(vistos.keySet(), PlanoSantaAnita.pasillos(), "Faltan los pasillos: ");
    return new ArrayList<>(vistos.values());
  }

  private static Integer cluster(Integer cluster, String deQuien) {
    if (cluster != null && (cluster < 1 || cluster > PlanoSantaAnita.CLUSTERS)) {
      throw invalida("El cluster de " + deQuien + " debe ser 1, 2, 3 o vacío.");
    }
    return cluster;
  }

  private static void exigirTodas(
      Set<Integer> recibidas, List<Integer> esperadas, String mensaje) {
    TreeSet<Integer> faltan = new TreeSet<>(esperadas);
    faltan.removeAll(recibidas);
    if (!faltan.isEmpty()) {
      throw invalida(
          mensaje + faltan.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ".");
    }
  }

  private static int enRango(Integer valor, int minimo, int maximo, String mensaje) {
    if (valor == null || valor < minimo || valor > maximo) {
      throw invalida(mensaje);
    }
    return valor;
  }

  private static SolicitudInvalidaException invalida(String mensaje) {
    return new SolicitudInvalidaException(mensaje);
  }
}
