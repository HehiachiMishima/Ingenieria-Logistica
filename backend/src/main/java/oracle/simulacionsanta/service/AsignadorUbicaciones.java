package oracle.simulacionsanta.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import oracle.simulacionsanta.model.EstadoPaleta;
import oracle.simulacionsanta.model.Lado;
import oracle.simulacionsanta.model.OrdenLlenado;
import oracle.simulacionsanta.model.TipoUbicacion;
import oracle.simulacionsanta.response.ClusterSimulacion;
import oracle.simulacionsanta.response.KpisSimulacion;
import oracle.simulacionsanta.response.PaletaSimulacion;
import oracle.simulacionsanta.response.SimulacionResponse;
import oracle.simulacionsanta.response.Ubicacion;

/**
 * Asigna un hueco a cada paleta y arma la simulación de un envío. Clase pura y determinista: sin
 * base de datos ni reloj; la usan GET …/simulacion (con la configuración guardada) y POST
 * …/simulacion/previsualizar (con la del pedido), así ambas dan siempre lo mismo.
 *
 * <p>Orden de llenado:
 *
 * <ul>
 *   <li>Rampa con h posiciones habilitadas: porLado = ⌈h/2⌉; primero IZQ filas 1..porLado y
 *       luego DER filas 1..(h − porLado).
 *   <li>Pasillo: filas 1 a 12 y, en cada fila, IZQ y luego DER.
 *   <li>Cluster c: sus rampas en orden ascendente y sus pasillos en orden ascendente; según
 *       ordenClusters[c−1] van primero las rampas o los pasillos.
 *   <li>Las paletas del cluster ocupan los huecos en el orden del Excel; las que sobran quedan sin
 *       ubicación.
 * </ul>
 */
public final class AsignadorUbicaciones {

  /** Envíos del Excel. */
  public static final int ENVIOS = 3;

  private static final Comparator<PaletaSanta> ORDEN_EXCEL =
      Comparator.comparingInt(PaletaSanta::orden)
          .thenComparing(PaletaSanta::id, Comparator.nullsLast(Comparator.naturalOrder()));

  private AsignadorUbicaciones() {}

  /** Huecos de una rampa con {@code posiciones} habilitadas, en orden de llenado. */
  public static List<Ubicacion> huecosRampa(int numero, int posiciones) {
    int porLado = (posiciones + 1) / 2;
    List<Ubicacion> huecos = new ArrayList<>(posiciones);
    for (int fila = 1; fila <= porLado; fila++) {
      huecos.add(new Ubicacion(TipoUbicacion.RAMPA, numero, Lado.IZQ, fila));
    }
    for (int fila = 1; fila <= posiciones - porLado; fila++) {
      huecos.add(new Ubicacion(TipoUbicacion.RAMPA, numero, Lado.DER, fila));
    }
    return huecos;
  }

  /** Los 24 huecos de un pasillo, en orden de llenado. */
  public static List<Ubicacion> huecosPasillo(int numero) {
    List<Ubicacion> huecos = new ArrayList<>(PlanoSantaAnita.POSICIONES_PASILLO);
    for (int fila = 1; fila <= PlanoSantaAnita.FILAS_PASILLO; fila++) {
      huecos.add(new Ubicacion(TipoUbicacion.PASILLO, numero, Lado.IZQ, fila));
      huecos.add(new Ubicacion(TipoUbicacion.PASILLO, numero, Lado.DER, fila));
    }
    return huecos;
  }

  /** Huecos habilitados del cluster (1 a 3), en orden de llenado. */
  public static List<Ubicacion> huecosCluster(ConfiguracionSanta config, int cluster) {
    List<Ubicacion> deRampas = new ArrayList<>();
    for (ConfiguracionSanta.Rampa rampa : config.rampas()) {
      if (rampa.cluster() != null && rampa.cluster() == cluster) {
        deRampas.addAll(huecosRampa(rampa.numero(), rampa.posiciones()));
      }
    }
    List<Ubicacion> dePasillos = new ArrayList<>();
    for (ConfiguracionSanta.Pasillo pasillo : config.pasillos()) {
      if (pasillo.cluster() != null && pasillo.cluster() == cluster) {
        dePasillos.addAll(huecosPasillo(pasillo.numero()));
      }
    }
    List<Ubicacion> huecos = new ArrayList<>(deRampas.size() + dePasillos.size());
    if (config.orden(cluster) == OrdenLlenado.RAMPAS_PRIMERO) {
      huecos.addAll(deRampas);
      huecos.addAll(dePasillos);
    } else {
      huecos.addAll(dePasillos);
      huecos.addAll(deRampas);
    }
    return huecos;
  }

  /**
   * Ubicación de cada paleta, en el orden del Excel (el mismo de {@link #enOrden(List)}); null =
   * sin ubicación.
   */
  public static List<Ubicacion> asignar(ConfiguracionSanta config, List<PaletaSanta> paletas) {
    List<PaletaSanta> ordenadas = enOrden(paletas);
    List<List<Ubicacion>> huecos = new ArrayList<>(PlanoSantaAnita.CLUSTERS);
    for (int c = 1; c <= PlanoSantaAnita.CLUSTERS; c++) {
      huecos.add(huecosCluster(config, c));
    }
    int[] usados = new int[PlanoSantaAnita.CLUSTERS];
    List<Ubicacion> ubicaciones = new ArrayList<>(ordenadas.size());
    for (PaletaSanta paleta : ordenadas) {
      int c = paleta.cluster();
      Ubicacion ubicacion = null;
      if (c >= 1 && c <= PlanoSantaAnita.CLUSTERS && usados[c - 1] < huecos.get(c - 1).size()) {
        ubicacion = huecos.get(c - 1).get(usados[c - 1]++);
      }
      ubicaciones.add(ubicacion);
    }
    return ubicaciones;
  }

  /** Paletas en el orden del Excel (fila ascendente; a igual fila, id). */
  public static List<PaletaSanta> enOrden(List<PaletaSanta> paletas) {
    return paletas.stream().sorted(ORDEN_EXCEL).toList();
  }

  /**
   * Simulación del envío {@code envio} (0 a 3) con la configuración dada.
   *
   * @param envioActual el envío guardado en el registro (solo se informa)
   */
  public static SimulacionResponse simular(
      Long registroId,
      int envio,
      int envioActual,
      ConfiguracionSanta config,
      List<PaletaSanta> paletas) {
    if (envio < 0 || envio > ENVIOS) {
      throw new IllegalArgumentException("El envío debe estar entre 0 y 3.");
    }
    List<PaletaSanta> ordenadas = enOrden(paletas);
    List<Ubicacion> ubicaciones = asignar(config, ordenadas);

    int[] paletasCluster = new int[PlanoSantaAnita.CLUSTERS];
    int[] ubicadasCluster = new int[PlanoSantaAnita.CLUSTERS];
    int[] bultosEnvios = new int[ENVIOS];
    int ubicadas = 0;
    int completas = 0;
    int enCurso = 0;
    int bultosTotal = 0;
    int bultosRecibidos = 0;
    List<PaletaSimulacion> salida = new ArrayList<>(ordenadas.size());
    for (int i = 0; i < ordenadas.size(); i++) {
      PaletaSanta paleta = ordenadas.get(i);
      Ubicacion ubicacion = ubicaciones.get(i);
      int recibidos = paleta.recibidos(envio);
      int total = paleta.total();
      EstadoPaleta estado = estado(recibidos, total);
      boolean enCluster = paleta.cluster() >= 1 && paleta.cluster() <= PlanoSantaAnita.CLUSTERS;
      if (enCluster) {
        paletasCluster[paleta.cluster() - 1]++;
      }
      if (ubicacion != null) {
        ubicadas++;
        ubicadasCluster[paleta.cluster() - 1]++;
      }
      if (estado == EstadoPaleta.COMPLETA) {
        completas++;
      } else if (estado == EstadoPaleta.EN_CURSO) {
        enCurso++;
      }
      bultosEnvios[0] += paleta.envio1();
      bultosEnvios[1] += paleta.envio2();
      bultosEnvios[2] += paleta.envio3();
      bultosTotal += total;
      bultosRecibidos += recibidos;
      salida.add(
          new PaletaSimulacion(
              paleta.id(),
              paleta.ruta(),
              paleta.local(),
              paleta.cluster(),
              List.of(paleta.envio1(), paleta.envio2(), paleta.envio3()),
              recibidos,
              total,
              estado,
              ubicacion));
    }

    List<ClusterSimulacion> clusters = new ArrayList<>(PlanoSantaAnita.CLUSTERS);
    for (int c = 1; c <= PlanoSantaAnita.CLUSTERS; c++) {
      clusters.add(
          new ClusterSimulacion(
              c,
              paletasCluster[c - 1],
              posiciones(config, c),
              ubicadasCluster[c - 1],
              paletasCluster[c - 1] - ubicadasCluster[c - 1],
              rampasDe(config, c),
              pasillosDe(config, c)));
    }
    int capacidadHora = config.capacidadHora();
    KpisSimulacion kpis =
        new KpisSimulacion(
            ordenadas.size(),
            ubicadas,
            ordenadas.size() - ubicadas,
            completas,
            enCurso,
            bultosTotal,
            bultosRecibidos,
            List.of(bultosEnvios[0], bultosEnvios[1], bultosEnvios[2]),
            capacidadHora,
            minutosRestantes(bultosTotal - bultosRecibidos, capacidadHora));
    return new SimulacionResponse(registroId, envio, envioActual, kpis, clusters, salida);
  }

  /** COMPLETA si ya llegó todo, EN_CURSO si llegó algo, si no PENDIENTE. */
  public static EstadoPaleta estado(int recibidos, int total) {
    if (recibidos >= total) {
      return EstadoPaleta.COMPLETA;
    }
    return recibidos > 0 ? EstadoPaleta.EN_CURSO : EstadoPaleta.PENDIENTE;
  }

  /** (pendientes / capacidadHora) × 60 con 1 decimal (mitad hacia arriba); 0 sin capacidad. */
  public static double minutosRestantes(long pendientes, int capacidadHora) {
    if (capacidadHora <= 0 || pendientes <= 0) {
      return 0;
    }
    return BigDecimal.valueOf(pendientes * 60)
        .divide(BigDecimal.valueOf(capacidadHora), 1, RoundingMode.HALF_UP)
        .doubleValue();
  }

  /** Huecos habilitados del cluster: posiciones de sus rampas más 24 por pasillo. */
  static int posiciones(ConfiguracionSanta config, int cluster) {
    int posiciones = 0;
    for (ConfiguracionSanta.Rampa rampa : config.rampas()) {
      if (rampa.cluster() != null && rampa.cluster() == cluster) {
        posiciones += rampa.posiciones();
      }
    }
    for (ConfiguracionSanta.Pasillo pasillo : config.pasillos()) {
      if (pasillo.cluster() != null && pasillo.cluster() == cluster) {
        posiciones += PlanoSantaAnita.POSICIONES_PASILLO;
      }
    }
    return posiciones;
  }

  private static List<Integer> rampasDe(ConfiguracionSanta config, int cluster) {
    return config.rampas().stream()
        .filter((r) -> r.cluster() != null && r.cluster() == cluster)
        .map(ConfiguracionSanta.Rampa::numero)
        .toList();
  }

  private static List<Integer> pasillosDe(ConfiguracionSanta config, int cluster) {
    return config.pasillos().stream()
        .filter((p) -> p.cluster() != null && p.cluster() == cluster)
        .map(ConfiguracionSanta.Pasillo::numero)
        .toList();
  }
}
