package oracle.simulacion.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import oracle.simulacion.model.Balanceo;
import oracle.simulacion.response.ChuteParametroResponse;
import oracle.simulacion.response.ClusterParametroResponse;
import oracle.simulacion.response.ConfiguracionSimulacionResponse;
import oracle.simulacion.response.DistribucionResponse;
import oracle.simulacion.response.RutaSimulacionResponse;
import org.springframework.stereotype.Service;

/**
 * Exportación de la simulación: la distribución de rutas por cluster y chute. La misma estructura
 * sale en Excel ({@link DistribucionExcel}) y en PDF (el frontend la imprime con el mismo diseño).
 *
 * <p>Un bloque por cluster, sin repetir por día: cada celda lleva los números de ruta con su
 * frecuencia (una diaria sale una sola vez) y, abajo, los bultos que el chute trabaja cada día
 * (diarias + rutas de ese día). Solo lee: la configuración (para clusters y chutes) y las rutas del
 * registro, una consulta cada una.
 */
@Service
public class SimulacionReporte {

  static final String DIARIA = "DIARIA";
  static final String LXV = "LXV";
  static final String MJS = "MJS";
  private static final List<String> FRECUENCIAS = List.of(DIARIA, LXV, MJS);
  private static final String LOGO = "reporte/fp-logo.jpg";

  private final SimulacionConfiguracionService servicio;

  public SimulacionReporte(SimulacionConfiguracionService servicio) {
    this.servicio = servicio;
  }

  /** La distribución del registro (para el PDF y para el Excel). */
  public DistribucionResponse distribucion(Long archivoId) {
    ConfiguracionSimulacionResponse cfg = servicio.obtener(archivoId, Balanceo.LXV).datos();
    List<RutaSimulacionResponse> rutas = servicio.todasLasRutas(archivoId).datos();
    return armar(cfg, rutas, Instant.now());
  }

  /** El Excel de la distribución. */
  public byte[] excel(Long archivoId) {
    return DistribucionExcel.escribir(distribucion(archivoId), logo());
  }

  /** «simulacion-proyeccion-enero.xlsx». */
  public String nombreArchivo(Long archivoId) {
    String nombre = servicio.nombreRegistro(archivoId);
    String base =
        Normalizer.normalize(nombre == null ? "registro" : nombre, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
    return "simulacion-" + (base.isEmpty() ? archivoId : base) + ".xlsx";
  }

  /** Logo de Farmacias Peruanas para el encabezado; null si no está en el classpath. */
  private static byte[] logo() {
    try (InputStream entrada = SimulacionReporte.class.getClassLoader().getResourceAsStream(LOGO)) {
      return entrada == null ? null : entrada.readAllBytes();
    } catch (IOException error) {
      throw new UncheckedIOException("No se pudo leer el logo del reporte.", error);
    }
  }

  /** DIARIA, LXV o MJS (días mezclados o sin dato cuentan como diaria). */
  static String frecuencia(String valor) {
    return LXV.equals(valor) || MJS.equals(valor) ? valor : DIARIA;
  }

  /** Arma la distribución con la configuración y las rutas ya leídas (sin acceso a la base). */
  static DistribucionResponse armar(
      ConfiguracionSimulacionResponse cfg, List<RutaSimulacionResponse> rutas, Instant generado) {
    // Chutes del plano en orden natural, con los bloqueados (sin id) en su lugar.
    List<DistribucionResponse.Chute> chutes = new ArrayList<>();
    Set<Long> conocidos = new HashSet<>();
    for (ChuteParametroResponse c : cfg.chutes()) {
      chutes.add(new DistribucionResponse.Chute(c.id(), c.nombre(), c.buff(), c.bloqueado()));
      conocidos.add(c.id());
    }
    for (String nombre : cfg.chutesBloqueados() == null ? List.<String>of() : cfg.chutesBloqueados()) {
      chutes.add(new DistribucionResponse.Chute(null, nombre, false, true));
    }
    for (RutaSimulacionResponse r : rutas) {
      if (r.chuteId() != null && conocidos.add(r.chuteId())) {
        chutes.add(new DistribucionResponse.Chute(r.chuteId(), r.chute(), false, false));
      }
    }
    chutes.sort(Comparator.comparing(DistribucionResponse.Chute::nombre, PlanoChutes.ORDEN));

    Map<Long, List<RutaSimulacionResponse>> porCluster = new LinkedHashMap<>();
    rutas.forEach((r) -> porCluster.computeIfAbsent(r.clusterId(), (k) -> new ArrayList<>()).add(r));
    Map<Long, Integer> posicion = new HashMap<>();
    for (int i = 0; i < chutes.size(); i++) {
      if (chutes.get(i).id() != null) {
        posicion.put(chutes.get(i).id(), i);
      }
    }

    List<DistribucionResponse.Cluster> clusters = new ArrayList<>();
    BigDecimal totalLxv = BigDecimal.ZERO;
    BigDecimal totalMjs = BigDecimal.ZERO;
    Set<Long> enUso = new HashSet<>();
    long totalRutas = 0;
    // Clusters en el orden de las olas; uno sin rutas no se lista.
    for (ClusterParametroResponse c : cfg.clusters()) {
      List<RutaSimulacionResponse> suyas = porCluster.getOrDefault(c.id(), List.of());
      if (suyas.isEmpty()) {
        continue;
      }
      Map<Long, List<RutaSimulacionResponse>> porChute = new HashMap<>();
      suyas.forEach((r) -> porChute.computeIfAbsent(r.chuteId(), (k) -> new ArrayList<>()).add(r));
      List<DistribucionResponse.Celda> celdas = new ArrayList<>();
      BigDecimal clusterLxv = BigDecimal.ZERO;
      BigDecimal clusterMjs = BigDecimal.ZERO;
      long[] porFrecuencia = new long[3];
      List<Long> ordenChutes = new ArrayList<>(porChute.keySet());
      ordenChutes.sort(Comparator.comparing((Long id) -> posicion.getOrDefault(id, Integer.MAX_VALUE)));
      for (Long chuteId : ordenChutes) {
        List<RutaSimulacionResponse> enChute = new ArrayList<>(porChute.get(chuteId));
        enChute.sort(
            Comparator.comparing((RutaSimulacionResponse r) -> FRECUENCIAS.indexOf(frecuencia(r.frecuencia())))
                .thenComparing(RutaSimulacionResponse::codigoRuta, SimulacionConfiguracionService::compararNatural)
                .thenComparing(RutaSimulacionResponse::id));
        BigDecimal lxv = BigDecimal.ZERO;
        BigDecimal mjs = BigDecimal.ZERO;
        List<DistribucionResponse.Ruta> lista = new ArrayList<>();
        for (RutaSimulacionResponse r : enChute) {
          String f = frecuencia(r.frecuencia());
          BigDecimal b = r.totalBultos() == null ? BigDecimal.ZERO : r.totalBultos();
          if (!MJS.equals(f)) {
            lxv = lxv.add(b);
          }
          if (!LXV.equals(f)) {
            mjs = mjs.add(b);
          }
          porFrecuencia[FRECUENCIAS.indexOf(f)]++;
          lista.add(new DistribucionResponse.Ruta(r.codigoRuta(), f));
        }
        celdas.add(new DistribucionResponse.Celda(chuteId, List.copyOf(lista), entero(lxv), entero(mjs)));
        clusterLxv = clusterLxv.add(lxv);
        clusterMjs = clusterMjs.add(mjs);
        if (chuteId != null) {
          enUso.add(chuteId);
        }
      }
      clusters.add(
          new DistribucionResponse.Cluster(
              c.id(),
              c.nombre(),
              suyas.size(),
              porFrecuencia[0],
              porFrecuencia[1],
              porFrecuencia[2],
              entero(clusterLxv),
              entero(clusterMjs),
              List.copyOf(celdas)));
      totalLxv = totalLxv.add(clusterLxv);
      totalMjs = totalMjs.add(clusterMjs);
      totalRutas += suyas.size();
    }
    return new DistribucionResponse(
        cfg.archivo() == null ? "" : cfg.archivo().nombreRegistro(),
        generado,
        List.copyOf(chutes),
        List.copyOf(clusters),
        new DistribucionResponse.Totales(clusters.size(), totalRutas, enUso.size(), entero(totalLxv), entero(totalMjs)));
  }

  private static long entero(BigDecimal valor) {
    return valor.setScale(0, RoundingMode.HALF_UP).longValue();
  }
}
