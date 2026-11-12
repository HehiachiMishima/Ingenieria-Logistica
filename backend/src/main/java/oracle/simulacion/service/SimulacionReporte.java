package oracle.simulacion.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import oracle.common.response.PaginaResponse;
import oracle.simulacion.response.CargaSimulacionResponse;
import oracle.simulacion.response.ChuteComparativoResponse;
import oracle.simulacion.response.ChuteParametroResponse;
import oracle.simulacion.response.ClusterComparativoResponse;
import oracle.simulacion.response.ClusterParametroResponse;
import oracle.simulacion.model.Balanceo;
import oracle.simulacion.response.ConfiguracionSimulacionResponse;
import oracle.simulacion.response.DashboardSimulacionResponse;
import oracle.simulacion.response.DotacionChuteResponse;
import oracle.simulacion.response.GrupoClusterResponse;
import oracle.simulacion.response.HistorialSimulacionResponse;
import oracle.simulacion.response.OlaSimulacionResponse;
import oracle.simulacion.response.PaginaRutasResponse;
import oracle.simulacion.response.RutaSimulacionResponse;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.XDDFColor;
import org.apache.poi.xddf.usermodel.XDDFLineProperties;
import org.apache.poi.xddf.usermodel.XDDFSolidFillProperties;
import org.apache.poi.xddf.usermodel.chart.AxisCrossBetween;
import org.apache.poi.xddf.usermodel.chart.AxisCrosses;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.BarDirection;
import org.apache.poi.xddf.usermodel.chart.BarGrouping;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.LegendPosition;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTBarChart;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTDLbls;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTNumFmt;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTValAx;
import org.openxmlformats.schemas.drawingml.x2006.chart.STDLblPos;
import org.springframework.stereotype.Service;

/**
 * Exportación a Excel de una simulación: lo mismo que se ve en Configuración
 * (las cuadrículas de rutas por cluster y chute con sus totales en semáforo) y
 * en el Dashboard (antes y después, por ola y por chute).
 *
 * Un solo archivo, de lo general a lo detallado: «Resumen» (indicadores,
 * cierre de cada ola y su gráfico, conclusiones y guía de lectura),
 * «Comparativo clusters» y «Comparativo por cluster» (antes vs. después con sus
 * gráficos, como el PDF), «Chutes», «Operarios por ola», «Configuración» (las
 * cuadrículas), «Rutas» e «Historial». Los gráficos son nativos de Excel y
 * leen las celdas de su tabla.
 *
 * Los datos se muestran enteros y redondeados hacia arriba, como en la
 * pantalla. Solo lee: reutiliza las consultas (y su caché) de la configuración
 * y del dashboard, así el archivo coincide con lo que el usuario ve.
 */
@Service
public class SimulacionReporte {

  private static final int PAGINA_RUTAS = 1_000;
  private static final int PAGINA_HISTORIAL = 100;
  private static final ZoneId ZONA = ZoneId.of("America/Lima");
  private static final DateTimeFormatter FECHA =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.forLanguageTag("es-PE"));
  /** Tiempo «sin operarios» del servicio (1E+30): no es un valor real. */
  private static final BigDecimal SIN_OPERARIOS = new BigDecimal("1E+29");

  private final SimulacionConfiguracionService servicio;

  public SimulacionReporte(SimulacionConfiguracionService servicio) {
    this.servicio = servicio;
  }

  /** Archivo .xlsx completo del registro: resumen, comparativos con gráficos y detalle. */
  public byte[] excel(Long archivoId) {
    return excel(archivoId, Balanceo.LXV);
  }

  /** Libro de un balanceo: solo sus rutas (las suyas y las diarias), su avance y su historial. */
  public byte[] excel(Long archivoId, Balanceo balanceo) {
    ConfiguracionSimulacionResponse cfg = servicio.obtener(archivoId, balanceo).datos();
    DashboardSimulacionResponse dash = servicio.dashboard(archivoId, balanceo, null).datos();
    // La vista de cada cluster (su carga por chute antes vs. después), como el PDF.
    List<DashboardSimulacionResponse> porCluster =
        cfg.clusters().stream()
            .map((c) -> servicio.dashboard(archivoId, balanceo, c.id()).datos())
            .toList();
    List<RutaSimulacionResponse> rutas =
        todasLasRutas(archivoId).stream().filter((r) -> balanceo.incluye(r.frecuencia())).toList();
    List<HistorialSimulacionResponse> historial = todoElHistorial(archivoId, balanceo);
    return new Libro(cfg, dash, rutas, historial, porCluster).escribir();
  }

  /** «simulacion-proyeccion-enero.xlsx». */
  public String nombreArchivo(Long archivoId, Balanceo balanceo) {
    String nombre = nombreArchivo(archivoId);
    return nombre.replaceFirst("\\.xlsx$", "-" + balanceo.name().toLowerCase(Locale.ROOT) + ".xlsx");
  }

  public String nombreArchivo(Long archivoId) {
    String nombre = servicio.obtener(archivoId).datos().archivo().nombreRegistro();
    String base =
        Normalizer.normalize(nombre == null ? "registro" : nombre, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
    return "simulacion-" + (base.isEmpty() ? archivoId : base) + ".xlsx";
  }

  private List<RutaSimulacionResponse> todasLasRutas(Long archivoId) {
    List<RutaSimulacionResponse> rutas = new ArrayList<>();
    Long despues = null;
    do {
      PaginaRutasResponse pagina =
          servicio.rutas(archivoId, despues, PAGINA_RUTAS, null, null, null).datos();
      rutas.addAll(pagina.contenido());
      despues = pagina.siguiente();
    } while (despues != null);
    return rutas;
  }

  private List<HistorialSimulacionResponse> todoElHistorial(Long archivoId, Balanceo balanceo) {
    List<HistorialSimulacionResponse> cambios = new ArrayList<>();
    int pagina = 1;
    PaginaResponse<HistorialSimulacionResponse> actual;
    do {
      actual =
          servicio.historialCambios(archivoId, balanceo, null, pagina, PAGINA_HISTORIAL).datos();
      cambios.addAll(actual.contenido());
      pagina++;
    } while (pagina <= actual.totalPaginas());
    return cambios;
  }

  // ------------------------------------------------------------------

  /** Arma el libro con los datos ya leídos (sin acceso a la base). */
  static final class Libro {

    private static final Comparator<String> NATURAL = SimulacionConfiguracionService::compararNatural;
    private static final String[] CONCEPTOS = {
      "Operarios", "Bultos", "Líneas", "Unidades", "Locales (COD)"
    };

    private final ConfiguracionSimulacionResponse cfg;
    private final DashboardSimulacionResponse dash;
    private final List<RutaSimulacionResponse> rutas;
    private final List<HistorialSimulacionResponse> historial;
    /** Vista del dashboard de cada cluster, en el orden de cfg.clusters(). */
    private final List<DashboardSimulacionResponse> porCluster;
    private final Map<Long, String> nombreChute = new HashMap<>();
    /** Chutes con rutas en algún cluster, en orden natural: las columnas de las cuadrículas. */
    private final List<Long> columnas;
    /** carga[cluster][chute]. */
    private final Map<Long, Map<Long, CargaSimulacionResponse>> carga = new LinkedHashMap<>();

    private XSSFWorkbook libro;
    private Estilos e;

    Libro(
        ConfiguracionSimulacionResponse cfg,
        DashboardSimulacionResponse dash,
        List<RutaSimulacionResponse> rutas,
        List<HistorialSimulacionResponse> historial,
        List<DashboardSimulacionResponse> porCluster) {
      this.cfg = cfg;
      this.dash = dash;
      this.rutas = rutas;
      this.historial = historial;
      this.porCluster = porCluster;
      for (ChuteParametroResponse chute : cfg.chutes()) {
        nombreChute.put(chute.id(), chute.nombre());
      }
      TreeSet<Long> conCarga =
          new TreeSet<>(Comparator.comparing((Long id) -> nombre(id), PlanoChutes.ORDEN).thenComparing(id -> id));
      for (CargaSimulacionResponse fila : cfg.carga()) {
        if (fila.rutas() > 0) {
          conCarga.add(fila.chuteId());
          carga.computeIfAbsent(fila.clusterId(), (id) -> new HashMap<>()).put(fila.chuteId(), fila);
        }
      }
      for (RutaSimulacionResponse ruta : rutas) {
        nombreChute.putIfAbsent(ruta.chuteId(), ruta.chute());
        conCarga.add(ruta.chuteId());
      }
      this.columnas = List.copyOf(conCarga);
    }

    /**
     * Un solo libro, de lo general a lo detallado: resumen con el cierre de cada
     * ola, comparativos antes vs. después (con su gráfico, como el PDF), las
     * cuadrículas de Configuración y el detalle de chutes, rutas y cambios.
     */
    byte[] escribir() {
      try (XSSFWorkbook nuevo = new XSSFWorkbook();
          ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
        libro = nuevo;
        e = new Estilos(libro);
        hojaResumen();
        hojaComparativoClusters();
        hojaComparativoPorCluster();
        hojaChutes();
        hojaOperarios();
        hojaConfiguracion();
        hojaGrupos();
        hojaRutas();
        hojaHistorial();
        libro.write(salida);
        return salida.toByteArray();
      } catch (IOException error) {
        throw new UncheckedIOException("No se pudo generar el Excel de la simulación.", error);
      }
    }

    // ---------------- Resumen ejecutivo ----------------

    private void hojaResumen() {
      Sheet hoja = libro.createSheet("Resumen");
      apaisada(hoja);
      int[] anchos = {6, 14, 20, 11, 11, 10, 12, 15, 15, 12, 9, 11, 11, 11, 12};
      for (int i = 0; i < anchos.length; i++) {
        hoja.setColumnWidth(i, anchos[i] * 256);
      }
      int f = 0;
      f = titulo(hoja, f, "Reporte ejecutivo de simulación", anchos.length);
      var archivo = cfg.archivo();
      f = texto(hoja, f, archivo.nombreRegistro() + " · " + estadoRegistro(archivo.estado())
          + " · generado el " + FECHA.format(Instant.now().atZone(ZONA)), e.subtitulo);
      f++;

      // Parámetros e indicadores en dos bloques de pares etiqueta/valor.
      f = seccion(hoja, f, "Parámetros e indicadores", anchos.length);
      BigDecimal original = valor(dash.kpis().tiempoOriginal());
      BigDecimal balanceado = valor(dash.kpis().tiempoBalanceado());
      long cumplen = dash.clusters().stream().filter((c) -> conLimite(c) && !c.excede()).count();
      long medibles = dash.clusters().stream().filter(Libro::conLimite).count();
      // Cierre establecido (límite) vs. conseguido de las olas que tienen ambos: la mejora.
      BigDecimal establecido = dash.clusters().stream().filter(Libro::conLimite)
          .map((c) -> valor(c.limiteHoras())).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal conseguido = dash.clusters().stream().filter(Libro::conLimite)
          .map((c) -> valor(c.tiempoBalanceado())).reduce(BigDecimal.ZERO, BigDecimal::add);
      Long mejoraCierre = mejora(establecido, conseguido);
      Object[][] pares = {
        {"Velocidad (bultos/h por operario)", entero(cfg.velocidadBase())},
        {"Máximo por chute", cfg.operariosPorChute() == null ? "Sin tope" : cfg.operariosPorChute()},
        {"Rutas", archivo.rutas()},
        {"Chutes", archivo.chutes()},
        {"Bultos", entero(archivo.bultos())},
        {"Unidades", entero(archivo.unidades())},
        {"Líneas", entero(archivo.lineas())},
        {"Locales (COD)", (long) archivo.cod()},
        {"Cierre establecido", medibles == 0 ? "—" : horas(establecido)},
        {"Cierre conseguido", medibles == 0 ? "—" : horas(conseguido)},
        {"Mejora del tiempo", mejoraCierre == null ? "—" : (mejoraCierre > 0 ? "+" : "") + mejoraCierre + " %"},
        {"Rutas movidas", (long) dash.kpis().rutasMovidas()},
        {"Clusters que cumplen su límite", medibles == 0 ? "—" : cumplen + " de " + medibles},
        {"Clusters cerrados", cerrados() + " de " + cfg.clusters().size()},
      };
      for (int i = 0; i < pares.length; i += 2) {
        Row fila = hoja.createRow(f++);
        fila.setHeightInPoints(20);
        par(fila, 1, (String) pares[i][0], pares[i][1]);
        if (i + 1 < pares.length) {
          par(fila, 8, (String) pares[i + 1][0], pares[i + 1][1]);
        }
      }
      f++;

      // Resultado por ola.
      f = seccion(hoja, f, "Resultado por ola", anchos.length);
      String[] cabecera = {
        "Ola", "Cluster", "Operarios", "Chutes", "Rutas", "Bultos", "Cierre establecido",
        "Cierre conseguido", "Uso", "Cumple", "Entraron", "Salieron", "Dif. bultos"
      };
      f = cabecera(hoja, f, 0, cabecera);
      int inicioTabla = f;
      Map<Long, ClusterComparativoResponse> comparativo = new HashMap<>();
      dash.clusters().forEach((c) -> comparativo.put(c.id(), c));
      int ola = 1;
      for (ClusterParametroResponse cluster : cfg.clusters()) {
        ClusterComparativoResponse c = comparativo.get(cluster.id());
        Map<Long, CargaSimulacionResponse> suya = carga.getOrDefault(cluster.id(), Map.of());
        long rutasCluster = suya.values().stream().mapToLong(CargaSimulacionResponse::rutas).sum();
        BigDecimal bultos = suya.values().stream().map(CargaSimulacionResponse::bultos)
            .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        Row fila = hoja.createRow(f++);
        int col = 0;
        celda(fila, col++, (long) ola++, e.centro);
        celda(fila, col++, cluster.nombre(), e.fuerte);
        celda(fila, col++, cluster.operariosOla() == null ? "—" : (Object) (long) cluster.operariosOla(), e.numero);
        celda(fila, col++, (long) suya.size(), e.numero);
        celda(fila, col++, rutasCluster, e.numero);
        celda(fila, col++, entero(bultos), e.numero);
        BigDecimal limite = valor(cluster.limiteHoras());
        boolean medible = c != null && conLimite(c);
        // Como hora de Excel ([h]:mm): se lee igual que en pantalla y el gráfico la usa.
        hora(fila, col++, limite.signum() > 0 ? limite : null);
        hora(fila, col++, c == null || sinOperarios(c.tiempoBalanceado()) ? null : c.tiempoBalanceado());
        celda(fila, col++, medible ? porcentaje(uso(c.tiempoBalanceado(), limite)) : "—", e.derecha);
        if (medible) {
          celda(fila, col++, c.excede() ? "No" : "Sí", c.excede() ? e.rojo : e.verde);
        } else {
          celda(fila, col++, "—", e.centro);
        }
        celda(fila, col++, c == null ? 0L : (long) c.rutasEntraron(), e.numero);
        celda(fila, col++, c == null ? 0L : (long) c.rutasSalieron(), e.numero);
        Integer dif = dispersion(suya);
        celda(fila, col, dif == null ? "—" : porcentaje((long) dif), e.derecha);
      }
      int finTabla = f - 1;
      if (f > inicioTabla) {
        hoja.setAutoFilter(new CellRangeAddress(inicioTabla - 1, f - 1, 0, cabecera.length - 1));
      }
      f++;

      // Gráfico del cierre de cada ola (columnas «Cierre establecido» y «Cierre conseguido»).
      if (finTabla >= inicioTabla) {
        f = seccion(hoja, f, "Cierre de cada ola: establecido vs. conseguido", anchos.length);
        f = grafico(
            hoja,
            f,
            0,
            anchos.length,
            16,
            "Cierre de cada ola: establecido vs. conseguido",
            new CellRangeAddress(inicioTabla, finTabla, 1, 1),
            List.of(
                new Serie("Cierre establecido", new CellRangeAddress(inicioTabla, finTabla, 6, 6), "94A3B8"),
                new Serie("Cierre conseguido", new CellRangeAddress(inicioTabla, finTabla, 7, 7), "7C3AED")),
            "[h]:mm");
      }

      // Conclusiones en frases cortas.
      f = seccion(hoja, f, "Conclusiones", anchos.length);
      if (mejoraCierre != null) {
        f = texto(hoja, f, "• Cierre conseguido de " + horas(conseguido) + " frente a " + horas(establecido)
            + " establecido (" + Math.abs(mejoraCierre) + " % " + (mejoraCierre >= 0 ? "por debajo" : "por encima")
            + "; " + medibles + " ola(s) con límite y operarios).", e.texto);
      }
      for (String frase : conclusiones(original, balanceado, cumplen, medibles)) {
        f = texto(hoja, f, "• " + frase, e.texto);
      }
      f++;

      // Guía de lectura: qué hay en cada hoja.
      f = seccion(hoja, f, "Cómo leer este archivo", anchos.length);
      String[][] guia = {
        {"Resumen", "Indicadores, resultado de cada ola y su cierre establecido vs. conseguido."},
        {"Comparativo clusters", "Cada cluster antes (Excel) y después (hoy): rutas, bultos, unidades, líneas y COD."},
        {"Comparativo por cluster", "Cada cluster en sus chutes, antes vs. después, con su gráfico de bultos."},
        {"Chutes", "Todos los chutes con carga: operarios, rutas, carga y tiempo antes vs. después."},
        {"Operarios por ola", "Cuántos operarios tuvo cada chute en cada ola."},
        {"Configuración", "Las cuadrículas de rutas por cluster y chute, con los totales en semáforo."},
        {"Grupos", "Los grupos de rutas de cada cluster: su prioridad, su carga y su tramo de chutes."},
        {"Rutas", "Cada ruta: dónde estaba en el Excel y dónde quedó."},
        {"Historial", "Todos los cambios: balanceos, movimientos a mano y operarios asignados."},
      };
      for (String[] linea : guia) {
        Row fila = hoja.createRow(f++);
        celda(fila, 0, linea[0], e.fuerte);
        hoja.addMergedRegion(new CellRangeAddress(fila.getRowNum(), fila.getRowNum(), 0, 2));
        celda(fila, 3, linea[1], e.texto);
        hoja.addMergedRegion(new CellRangeAddress(fila.getRowNum(), fila.getRowNum(), 3, anchos.length - 1));
      }
      hoja.createFreezePane(0, 3);
    }

    // ---------------- Comparativos antes vs. después (como el PDF) ----------------

    private static final String[] METRICAS = {"Rutas", "Bultos", "Unidades", "Líneas", "COD"};

    /** Cabecera de dos filas: métrica (Antes · Después · Var.) y, si van, entradas y salidas. */
    private int cabeceraComparativa(Sheet hoja, int f, String primera, boolean traspasos) {
      Row arriba = hoja.createRow(f);
      Row abajo = hoja.createRow(f + 1);
      arriba.setHeightInPoints(20);
      abajo.setHeightInPoints(18);
      celda(arriba, 0, primera, e.cabecera);
      celda(abajo, 0, "", e.cabecera);
      hoja.addMergedRegion(new CellRangeAddress(f, f + 1, 0, 0));
      int col = 1;
      for (String metrica : METRICAS) {
        for (int i = 0; i < 3; i++) {
          celda(arriba, col + i, i == 0 ? metrica : "", e.cabecera);
        }
        hoja.addMergedRegion(new CellRangeAddress(f, f, col, col + 2));
        celda(abajo, col, "Antes", e.cabecera);
        celda(abajo, col + 1, "Después", e.cabecera);
        celda(abajo, col + 2, "Var.", e.cabecera);
        col += 3;
      }
      if (traspasos) {
        for (String titulo : List.of("Entraron", "Salieron")) {
          celda(arriba, col, titulo, e.cabecera);
          celda(abajo, col, "", e.cabecera);
          hoja.addMergedRegion(new CellRangeAddress(f, f + 1, col, col));
          col++;
        }
      }
      return f + 2;
    }

    /** Una fila del comparativo: antes (atenuado), después (en negrita) y la variación. */
    private void filaComparativa(Row fila, String etiqueta, CellStyle estiloEtiqueta, long[] antes, long[] despues) {
      celda(fila, 0, etiqueta, estiloEtiqueta);
      int col = 1;
      for (int i = 0; i < METRICAS.length; i++) {
        celda(fila, col++, antes[i], e.antes);
        celda(fila, col++, despues[i], e.despues);
        if (antes[i] > 0) {
          celda(fila, col++, (double) (despues[i] - antes[i]) / antes[i], e.variacion);
        } else {
          celda(fila, col++, despues[i] > 0 ? "nuevo" : "—", e.centro);
        }
      }
    }

    private static long[] antesCluster(ClusterComparativoResponse c) {
      return new long[] {
        c.rutasOriginal(), entero(c.bultosOriginal()), entero(c.unidadesOriginal()),
        entero(c.lineasOriginal()), c.codOriginal()
      };
    }

    private static long[] despuesCluster(ClusterComparativoResponse c) {
      return new long[] {
        c.rutasBalanceado(), entero(c.bultosBalanceado()), entero(c.unidadesBalanceado()),
        entero(c.lineasBalanceado()), c.codBalanceado()
      };
    }

    private static long[] antesChute(ChuteComparativoResponse c) {
      return new long[] {
        c.rutasOriginal(), entero(c.bultosOriginal()), entero(c.unidadesOriginal()),
        entero(c.lineasOriginal()), c.codOriginal()
      };
    }

    private static long[] despuesChute(ChuteComparativoResponse c) {
      return new long[] {
        c.rutasBalanceado(), entero(c.bultosBalanceado()), entero(c.unidadesBalanceado()),
        entero(c.lineasBalanceado()), c.codBalanceado()
      };
    }

    private static long[] sumar(long[] total, long[] fila) {
      for (int i = 0; i < total.length; i++) {
        total[i] += fila[i];
      }
      return total;
    }

    /** Todos los clusters antes (Excel) vs. después (hoy), con entradas y salidas y su gráfico. */
    private void hojaComparativoClusters() {
      Sheet hoja = libro.createSheet("Comparativo clusters");
      apaisada(hoja);
      hoja.setColumnWidth(0, 16 * 256);
      for (int i = 1; i <= METRICAS.length * 3 + 2; i++) {
        hoja.setColumnWidth(i, 11 * 256);
      }
      int ancho = METRICAS.length * 3 + 3;
      int f = titulo(hoja, 0, "Comparativo de clusters: antes vs. después", ancho);
      f = texto(hoja, f, "«Antes» es la carga del Excel; «después», la de hoy (incluye las rutas cambiadas de"
          + " cluster). Var. = cambio porcentual.", e.subtitulo);
      f++;
      f = cabeceraComparativa(hoja, f, "Cluster", true);
      int inicio = f;
      long[] totalAntes = new long[METRICAS.length];
      long[] totalDespues = new long[METRICAS.length];
      for (ClusterComparativoResponse c : dash.clusters()) {
        Row fila = hoja.createRow(f++);
        filaComparativa(fila, c.cluster(), e.fuerte, antesCluster(c), despuesCluster(c));
        celda(fila, METRICAS.length * 3 + 1, c.rutasEntraron() == 0 ? "—" : (Object) ("+" + c.rutasEntraron()), e.centro);
        celda(fila, METRICAS.length * 3 + 2, c.rutasSalieron() == 0 ? "—" : (Object) ("−" + c.rutasSalieron()), e.centro);
        sumar(totalAntes, antesCluster(c));
        sumar(totalDespues, despuesCluster(c));
      }
      int fin = f - 1;
      if (fin >= inicio) {
        Row total = hoja.createRow(f++);
        filaComparativa(total, "Total", e.etiquetaTotal, totalAntes, totalDespues);
        f++;
        f = seccion(hoja, f, "Bultos por cluster: antes vs. después", ancho);
        f = grafico(
            hoja, f, 0, ancho, 16, "Bultos por cluster: antes vs. después",
            new CellRangeAddress(inicio, fin, 0, 0),
            List.of(
                new Serie("Bultos antes", new CellRangeAddress(inicio, fin, 4, 4), "94A3B8"),
                new Serie("Bultos después", new CellRangeAddress(inicio, fin, 5, 5), "1D63ED")),
            "#,##0");
        f = grafico(
            hoja, f + 1, 0, ancho, 16, "Rutas por cluster: antes vs. después",
            new CellRangeAddress(inicio, fin, 0, 0),
            List.of(
                new Serie("Rutas antes", new CellRangeAddress(inicio, fin, 1, 1), "94A3B8"),
                new Serie("Rutas después", new CellRangeAddress(inicio, fin, 2, 2), "059669")),
            "#,##0");
      }
      hoja.createFreezePane(1, inicio);
    }

    /** Cada cluster en sus chutes (vista del cluster en el dashboard): cuadro y gráfico. */
    private void hojaComparativoPorCluster() {
      Sheet hoja = libro.createSheet("Comparativo por cluster");
      apaisada(hoja);
      hoja.setColumnWidth(0, 14 * 256);
      for (int i = 1; i <= METRICAS.length * 3; i++) {
        hoja.setColumnWidth(i, 11 * 256);
      }
      int ancho = METRICAS.length * 3 + 1;
      int f = titulo(hoja, 0, "Comparativo por cluster: antes vs. después", ancho);
      f = texto(hoja, f, "Carga de cada cluster en sus chutes al cargar el Excel y después del balanceo.",
          e.subtitulo);
      f++;
      for (int i = 0; i < cfg.clusters().size(); i++) {
        ClusterParametroResponse cluster = cfg.clusters().get(i);
        DashboardSimulacionResponse vista = i < porCluster.size() ? porCluster.get(i) : null;
        List<ChuteComparativoResponse> chutes = new ArrayList<>();
        if (vista != null) {
          vista.chutes().stream()
              .filter((c) -> valor(c.bultosOriginal()).signum() > 0 || valor(c.bultosBalanceado()).signum() > 0)
              .sorted(Comparator.comparing(ChuteComparativoResponse::chute, PlanoChutes.ORDEN))
              .forEach(chutes::add);
        }
        int movidas = dash.olas().stream().filter((o) -> o.clusterId().equals(cluster.id()))
            .mapToInt(OlaSimulacionResponse::rutasMovidas).sum();
        f = seccion(hoja, f, cluster.nombre() + " · " + estadoCluster(cluster.estado()) + " · "
            + chutes.size() + " chutes · " + movidas + " ruta(s) movidas", ancho);
        if (chutes.isEmpty()) {
          f = texto(hoja, f, "Sin carga en este cluster.", e.subtitulo) + 1;
          continue;
        }
        f = cabeceraComparativa(hoja, f, "Chute", false);
        int inicio = f;
        long[] totalAntes = new long[METRICAS.length];
        long[] totalDespues = new long[METRICAS.length];
        for (ChuteComparativoResponse c : chutes) {
          filaComparativa(hoja.createRow(f++), etiquetaChute(c.chute()), e.fuerte, antesChute(c), despuesChute(c));
          sumar(totalAntes, antesChute(c));
          sumar(totalDespues, despuesChute(c));
        }
        int fin = f - 1;
        filaComparativa(hoja.createRow(f++), "Total", e.etiquetaTotal, totalAntes, totalDespues);
        f = grafico(
            hoja, f + 1, 0, ancho, 15, cluster.nombre() + ": bultos por chute, antes vs. después",
            new CellRangeAddress(inicio, fin, 0, 0),
            List.of(
                new Serie("Bultos antes", new CellRangeAddress(inicio, fin, 4, 4), "94A3B8"),
                new Serie("Bultos después", new CellRangeAddress(inicio, fin, 5, 5), "059669")),
            "#,##0");
        f++;
      }
    }

    /** «CHUTE 5» para las tablas, aunque el catálogo lo guarde como «5». */
    private static String etiquetaChute(String nombre) {
      return nombre != null && nombre.trim().toUpperCase(Locale.ROOT).startsWith("CH") ? nombre : "CHUTE " + nombre;
    }

    // ---------------- Gráficos nativos de Excel ----------------

    /** Una serie del gráfico: su nombre, el rango de valores y su color (hex). */
    private record Serie(String nombre, CellRangeAddress valores, String color) {}

    /**
     * Gráfico de columnas agrupadas (como los del PDF) anclado debajo de una
     * tabla: categorías y valores apuntan a celdas de la hoja, así el gráfico se
     * actualiza si alguien edita la tabla. Devuelve la fila siguiente al gráfico.
     */
    private int grafico(
        Sheet hoja, int fila, int col, int columnas, int filas, String titulo,
        CellRangeAddress categorias, List<Serie> series, String formato) {
      XSSFDrawing dibujo = ((XSSFSheet) hoja).createDrawingPatriarch();
      XSSFClientAnchor ancla = dibujo.createAnchor(0, 0, 0, 0, col, fila, col + columnas, fila + filas);
      XSSFChart chart = dibujo.createChart(ancla);
      chart.setTitleText(titulo);
      chart.setTitleOverlay(false);
      chart.getOrAddLegend().setPosition(LegendPosition.TOP);
      XDDFCategoryAxis x = chart.createCategoryAxis(AxisPosition.BOTTOM);
      XDDFValueAxis y = chart.createValueAxis(AxisPosition.LEFT);
      y.setCrosses(AxisCrosses.AUTO_ZERO);
      y.setCrossBetween(AxisCrossBetween.BETWEEN);
      y.getOrAddMajorGridProperties()
          .setLineProperties(new XDDFLineProperties(new XDDFSolidFillProperties(XDDFColor.from(rgb("E2E8F0")))));
      XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange((XSSFSheet) hoja, categorias);
      XDDFBarChartData datos = (XDDFBarChartData) chart.createData(ChartTypes.BAR, x, y);
      datos.setBarDirection(BarDirection.COL);
      datos.setBarGrouping(BarGrouping.CLUSTERED);
      datos.setVaryColors(false);
      datos.setGapWidth(80);
      for (Serie serie : series) {
        XDDFNumericalDataSource<Double> valores =
            XDDFDataSourcesFactory.fromNumericCellRange((XSSFSheet) hoja, serie.valores());
        XDDFBarChartData.Series s = (XDDFBarChartData.Series) datos.addSeries(cats, valores);
        s.setTitle(serie.nombre(), null);
        s.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(rgb(serie.color()))));
      }
      chart.plot(datos);
      // Cifra sobre cada barra y formato del eje (horas como [h]:mm o miles con coma).
      CTBarChart barras = chart.getCTChart().getPlotArea().getBarChartArray(0);
      CTDLbls etiquetas = barras.addNewDLbls();
      CTNumFmt formatoEtiqueta = etiquetas.addNewNumFmt();
      formatoEtiqueta.setFormatCode(formato);
      formatoEtiqueta.setSourceLinked(false);
      etiquetas.addNewDLblPos().setVal(STDLblPos.OUT_END);
      etiquetas.addNewShowLegendKey().setVal(false);
      etiquetas.addNewShowVal().setVal(true);
      etiquetas.addNewShowCatName().setVal(false);
      etiquetas.addNewShowSerName().setVal(false);
      etiquetas.addNewShowPercent().setVal(false);
      etiquetas.addNewShowBubbleSize().setVal(false);
      CTValAx eje = chart.getCTChart().getPlotArea().getValAxArray(0);
      CTNumFmt formatoEje = eje.isSetNumFmt() ? eje.getNumFmt() : eje.addNewNumFmt();
      formatoEje.setFormatCode(formato);
      formatoEje.setSourceLinked(false);
      return fila + filas + 1;
    }

    private static byte[] rgb(String hex) {
      int v = Integer.parseInt(hex, 16);
      return new byte[] {(byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    /** Horas como hora de Excel (fracción de día, formato [h]:mm); «—» si no hay. */
    private void hora(Row fila, int col, BigDecimal horas) {
      if (horas == null || horas.signum() <= 0 || sinOperarios(horas)) {
        celda(fila, col, "—", e.derecha);
        return;
      }
      Cell celda = fila.createCell(col);
      celda.setCellValue(horas.doubleValue() / 24d);
      celda.setCellStyle(e.hora);
    }

    private List<String> conclusiones(
        BigDecimal original, BigDecimal balanceado, long cumplen, long medibles) {
      List<String> frases = new ArrayList<>();
      if (original.signum() > 0 && balanceado.signum() > 0) {
        frases.add("Frente a la proyección del Excel, el tiempo pasa de " + horas(original) + " a "
            + horas(balanceado) + ".");
      }
      if (medibles > 0) {
        frases.add(cumplen + " de " + medibles + " clusters cumplen su límite de horas.");
      }
      dash.clusters().stream()
          .filter(Libro::conLimite)
          .filter(ClusterComparativoResponse::excede)
          .forEach((c) -> frases.add(c.cluster() + " excede su límite: " + horas(c.tiempoBalanceado())
              + " de " + horas(c.limiteHoras()) + "."));
      dash.olas().stream()
          .filter((o) -> o.tiempoCierre() != null && !sinOperarios(o.tiempoCierre()))
          .max(Comparator.comparing(OlaSimulacionResponse::tiempoCierre))
          .ifPresent((o) -> frases.add("La ola más larga es " + o.cluster() + " ("
              + horas(o.tiempoCierre()) + ")."));
      frases.add(dash.kpis().rutasMovidas() + " ruta(s) cambiaron de chute o de cluster respecto del Excel.");
      if (!"FINALIZADO".equals(cfg.archivo().estado())) {
        frases.add("La simulación aún no termina: " + cerrados() + " de " + cfg.clusters().size()
            + " clusters cerrados.");
      }
      return frases;
    }

    // ---------------- Cuadrículas de configuración ----------------

    private void hojaConfiguracion() {
      Sheet hoja = libro.createSheet("Configuración");
      apaisada(hoja);
      hoja.setColumnWidth(0, 20 * 256);
      for (int i = 0; i < columnas.size(); i++) {
        hoja.setColumnWidth(i + 1, 15 * 256);
      }
      int total = columnas.size() + 1;
      int f = titulo(hoja, 0, "Configuración: rutas por cluster y chute", total);
      f = texto(hoja, f, "Totales por chute con semáforo por línea: rojo = lo más alto, verde = "
          + "intermedio, amarillo = lo más bajo.", e.subtitulo);
      f++;

      Map<Long, List<RutaSimulacionResponse>> porCelda = new HashMap<>();
      for (RutaSimulacionResponse ruta : rutas) {
        porCelda.computeIfAbsent(clave(ruta.clusterId(), ruta.chuteId()), (k) -> new ArrayList<>()).add(ruta);
      }
      porCelda.values().forEach((lista) -> lista.sort(
          Comparator.comparing(RutaSimulacionResponse::codigoRuta, NATURAL)
              .thenComparing(RutaSimulacionResponse::id)));

      for (ClusterParametroResponse cluster : cfg.clusters()) {
        Map<Long, CargaSimulacionResponse> suya = carga.getOrDefault(cluster.id(), Map.of());
        long rutasCluster = suya.values().stream().mapToLong(CargaSimulacionResponse::rutas).sum();
        BigDecimal bultos = suya.values().stream().map(CargaSimulacionResponse::bultos)
            .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        String cabeceraCluster = cluster.nombre() + " · " + estadoCluster(cluster.estado())
            + " · " + rutasCluster + " rutas · " + formatoEntero(bultos) + " bultos"
            + (cluster.operariosOla() == null ? "" : " · " + cluster.operariosOla() + " operarios")
            + (valor(cluster.limiteHoras()).signum() > 0 ? " · límite " + horas(cluster.limiteHoras()) : "");
        f = seccion(hoja, f, cabeceraCluster, total);
        f = cabeceraChutes(hoja, f, "Rutas");
        int alto = 1;
        for (Long chute : columnas) {
          alto = Math.max(alto, porCelda.getOrDefault(clave(cluster.id(), chute), List.of()).size());
        }
        for (int r = 0; r < alto; r++) {
          Row fila = hoja.createRow(f++);
          celda(fila, 0, r == 0 ? "Rutas (código · tipo)" : "", e.etiqueta);
          for (int c = 0; c < columnas.size(); c++) {
            List<RutaSimulacionResponse> lista =
                porCelda.getOrDefault(clave(cluster.id(), columnas.get(c)), List.of());
            if (r < lista.size()) {
              RutaSimulacionResponse ruta = lista.get(r);
              boolean movida = !Objects.equals(ruta.chuteId(), ruta.chuteOriginalId())
                  || !Objects.equals(ruta.clusterId(), ruta.clusterOriginalId());
              celda(fila, c + 1, ruta.codigoRuta() + " · " + ruta.tipo(), movida ? e.rutaMovida : e.ruta);
            } else {
              celda(fila, c + 1, "", e.vacia);
            }
          }
        }
        f = totales(hoja, f, (chute) -> suya.get(chute), false);
        f++;
      }

      // Total general: suma de los clusters; operarios = el máximo de sus olas.
      Map<Long, CargaSimulacionResponse> general = new HashMap<>();
      for (Map<Long, CargaSimulacionResponse> suya : carga.values()) {
        suya.forEach((chute, fila) -> general.merge(chute, fila, Libro::sumar));
      }
      f = seccion(hoja, f, "Total general", total);
      f = cabeceraChutes(hoja, f, "Concepto");
      totales(hoja, f, general::get, true);
      hoja.createFreezePane(1, 0);
    }

    private int cabeceraChutes(Sheet hoja, int f, String primera) {
      Row fila = hoja.createRow(f);
      fila.setHeightInPoints(20);
      celda(fila, 0, primera, e.cabecera);
      for (int c = 0; c < columnas.size(); c++) {
        celda(fila, c + 1, nombre(columnas.get(c)), e.cabecera);
      }
      return f + 1;
    }

    /** Filas de totales por chute, cada una con su semáforo. */
    private int totales(
        Sheet hoja,
        int f,
        java.util.function.Function<Long, CargaSimulacionResponse> fila,
        boolean general) {
      for (int concepto = 0; concepto < CONCEPTOS.length; concepto++) {
        long[] valores = new long[columnas.size()];
        for (int c = 0; c < columnas.size(); c++) {
          CargaSimulacionResponse dato = fila.apply(columnas.get(c));
          valores[c] = dato == null ? 0 : valorConcepto(dato, concepto);
        }
        double[] niveles = niveles(valores);
        Row r = hoja.createRow(f++);
        String etiqueta = general && concepto == 0 ? "Máx. operarios por ola" : "Total " + CONCEPTOS[concepto].toLowerCase(Locale.ROOT);
        celda(r, 0, etiqueta, e.etiquetaTotal);
        for (int c = 0; c < columnas.size(); c++) {
          if (valores[c] <= 0) {
            celda(r, c + 1, "", e.vacia);
          } else {
            CellStyle estilo = niveles[c] < 1.0 / 3 ? e.semBajo : niveles[c] < 2.0 / 3 ? e.semMedio : e.semAlto;
            celda(r, c + 1, valores[c], estilo);
          }
        }
      }
      return f;
    }

    private static long valorConcepto(CargaSimulacionResponse fila, int concepto) {
      return switch (concepto) {
        case 0 -> fila.operarios() == null ? 0 : fila.operarios();
        case 1 -> entero(fila.bultos());
        case 2 -> entero(fila.lineas());
        case 3 -> entero(fila.unidades());
        default -> fila.cod();
      };
    }

    /** Suma dos cargas del mismo chute; los operarios toman el máximo de sus olas. */
    private static CargaSimulacionResponse sumar(CargaSimulacionResponse a, CargaSimulacionResponse b) {
      // Sin ternario con Math.max: mezclar Integer e int desempaqueta un null (NPE).
      Integer operarios;
      if (a.operarios() == null) {
        operarios = b.operarios();
      } else if (b.operarios() == null) {
        operarios = a.operarios();
      } else {
        operarios = Integer.valueOf(Math.max(a.operarios(), b.operarios()));
      }
      return new CargaSimulacionResponse(
          null,
          a.chuteId(),
          a.rutas() + b.rutas(),
          valor(a.bultos()).add(valor(b.bultos())),
          valor(a.unidades()).add(valor(b.unidades())),
          valor(a.lineas()).add(valor(b.lineas())),
          a.cod() + b.cod(),
          operarios);
    }

    /** Puesto de cada valor en su fila (0 = el menor … 1 = el mayor), igual que la pantalla. */
    static double[] niveles(long[] valores) {
      TreeSet<Long> distintos = new TreeSet<>();
      for (long v : valores) {
        if (v > 0) {
          distintos.add(v);
        }
      }
      List<Long> orden = new ArrayList<>(distintos);
      double[] niveles = new double[valores.length];
      for (int i = 0; i < valores.length; i++) {
        if (valores[i] <= 0) {
          niveles[i] = -1;
        } else if (orden.size() == 1) {
          niveles[i] = 0.5;
        } else {
          niveles[i] = (double) orden.indexOf(valores[i]) / (orden.size() - 1);
        }
      }
      return niveles;
    }

    // ---------------- Hojas del detallado ----------------

    private void hojaChutes() {
      Sheet hoja = libro.createSheet("Chutes");
      String[] cabecera = {
        "Chute", "Clusters", "Operarios antes", "Operarios después", "Rutas antes", "Rutas después",
        "Salieron", "Llegaron", "Bultos antes", "Bultos después", "Unidades antes",
        "Unidades después", "Líneas antes", "Líneas después", "COD antes", "COD después",
        "Tiempo antes", "Tiempo después", "Estado"
      };
      int f = titulo(hoja, 0, "Detalle por chute: antes y después", cabecera.length);
      f = cabecera(hoja, f + 1, 0, cabecera);
      int inicio = f;
      List<ChuteComparativoResponse> chutes = new ArrayList<>(dash.chutes());
      chutes.sort(Comparator.comparing(ChuteComparativoResponse::chute, PlanoChutes.ORDEN));
      for (ChuteComparativoResponse c : chutes) {
        Row fila = hoja.createRow(f++);
        int col = 0;
        celda(fila, col++, etiquetaChute(c.chute()), e.fuerte);
        celda(fila, col++, String.join(", ", c.clustersBalanceado() == null ? List.of() : c.clustersBalanceado()), e.texto);
        celda(fila, col++, (long) c.operariosOriginal(), e.numero);
        celda(fila, col++, (long) c.operariosBalanceado(), e.numero);
        celda(fila, col++, (long) c.rutasOriginal(), e.numero);
        celda(fila, col++, (long) c.rutasBalanceado(), e.numero);
        celda(fila, col++, (long) c.rutasSalieron(), e.numero);
        celda(fila, col++, (long) c.rutasLlegaron(), e.numero);
        celda(fila, col++, entero(c.bultosOriginal()), e.numero);
        celda(fila, col++, entero(c.bultosBalanceado()), e.numero);
        celda(fila, col++, entero(c.unidadesOriginal()), e.numero);
        celda(fila, col++, entero(c.unidadesBalanceado()), e.numero);
        celda(fila, col++, entero(c.lineasOriginal()), e.numero);
        celda(fila, col++, entero(c.lineasBalanceado()), e.numero);
        celda(fila, col++, (long) c.codOriginal(), e.numero);
        celda(fila, col++, (long) c.codBalanceado(), e.numero);
        celda(fila, col++, horas(c.tiempoOriginal()), e.derecha);
        celda(fila, col++, horas(c.tiempoBalanceado()), e.derecha);
        celda(fila, col, c.estado() == null ? "" : c.estado(), e.texto);
      }
      cerrarTabla(hoja, inicio, f, cabecera.length, 14);
      if (f > inicio) {
        int g = seccion(hoja, f + 1, "Bultos por chute (todos los clusters): antes vs. después", 12);
        g = grafico(
            hoja, g, 0, 12, 16, "Bultos por chute: antes vs. después",
            new CellRangeAddress(inicio, f - 1, 0, 0),
            List.of(
                new Serie("Bultos antes", new CellRangeAddress(inicio, f - 1, 8, 8), "94A3B8"),
                new Serie("Bultos después", new CellRangeAddress(inicio, f - 1, 9, 9), "059669")),
            "#,##0");
        grafico(
            hoja, g + 1, 0, 12, 16, "Operarios por chute: antes vs. después",
            new CellRangeAddress(inicio, f - 1, 0, 0),
            List.of(
                new Serie("Operarios antes", new CellRangeAddress(inicio, f - 1, 2, 2), "94A3B8"),
                new Serie("Operarios después", new CellRangeAddress(inicio, f - 1, 3, 3), "7C3AED")),
            "#,##0");
      }
    }

    /** Grupos de cada cluster en orden de prioridad, con su carga y el tramo del último balanceo. */
    private void hojaGrupos() {
      Sheet hoja = libro.createSheet("Grupos");
      String[] cabecera = {"Cluster", "Prioridad", "Grupo", "Rutas", "Bultos", "Tramo de chutes"};
      int f = titulo(hoja, 0, "Grupos de rutas: prioridad y tramo de cada uno", cabecera.length);
      f = texto(hoja, f, "El balanceo automático reparte cada grupo solo en su tramo; a mano, una ruta puede ir a cualquier chute.", e.texto);
      f = cabecera(hoja, f + 1, 0, cabecera);
      int inicio = f;
      for (ClusterParametroResponse cluster : cfg.clusters()) {
        List<GrupoClusterResponse> grupos = cluster.grupos() == null ? List.of() : cluster.grupos();
        for (GrupoClusterResponse g : grupos) {
          Row fila = hoja.createRow(f++);
          celda(fila, 0, cluster.nombre(), e.fuerte);
          celda(fila, 1, g.prioridad() == null ? "—" : (Object) (long) g.prioridad(), e.centro);
          celda(fila, 2, g.nombre(), e.texto);
          celda(fila, 3, g.rutas(), e.numero);
          celda(fila, 4, entero(g.bultos()), e.numero);
          celda(fila, 5, tramo(g), e.texto);
        }
      }
      cerrarTabla(hoja, inicio, f, cabecera.length, 18);
    }

    private static String tramo(GrupoClusterResponse g) {
      if (g.chuteDesde() == null) {
        return "Sin balancear";
      }
      return Objects.equals(g.chuteDesdeId(), g.chuteHastaId())
          ? g.chuteDesde()
          : g.chuteDesde() + " → " + g.chuteHasta();
    }

    private void hojaRutas() {
      Sheet hoja = libro.createSheet("Rutas");
      String[] cabecera = {
        "Código", "Tipo", "Grupo", "Frecuencia", "Cluster (Excel)", "Chute (Excel)", "Cluster final", "Chute final",
        "Cambió de chute", "Cambió de cluster", "Bultos", "Unidades", "Líneas", "COD"
      };
      int f = titulo(hoja, 0, "Detalle de rutas: dónde estaban y dónde quedaron", cabecera.length);
      f = cabecera(hoja, f + 1, 0, cabecera);
      int inicio = f;
      List<RutaSimulacionResponse> orden = new ArrayList<>(rutas);
      orden.sort(Comparator.comparing(RutaSimulacionResponse::cluster, NATURAL)
          .thenComparing(RutaSimulacionResponse::chute, PlanoChutes.ORDEN)
          .thenComparing(RutaSimulacionResponse::codigoRuta, NATURAL));
      for (RutaSimulacionResponse r : orden) {
        boolean otroChute = !Objects.equals(r.chuteId(), r.chuteOriginalId());
        boolean otroCluster = !Objects.equals(r.clusterId(), r.clusterOriginalId());
        Row fila = hoja.createRow(f++);
        int col = 0;
        celda(fila, col++, r.codigoRuta(), e.fuerte);
        celda(fila, col++, r.tipo(), e.centro);
        celda(fila, col++, r.grupo(), e.texto);
        celda(fila, col++, SimulacionExcel.textoFrecuencia(r.frecuencia()), e.centro);
        celda(fila, col++, r.clusterOriginal(), e.texto);
        celda(fila, col++, r.chuteOriginal(), e.texto);
        celda(fila, col++, r.cluster(), otroCluster ? e.resaltado : e.texto);
        celda(fila, col++, r.chute(), otroChute ? e.resaltado : e.texto);
        celda(fila, col++, otroChute ? "Sí" : "No", e.centro);
        celda(fila, col++, otroCluster ? "Sí" : "No", e.centro);
        celda(fila, col++, entero(r.totalBultos()), e.numero);
        celda(fila, col++, entero(r.totalUnidades()), e.numero);
        celda(fila, col++, entero(r.totalLineas()), e.numero);
        celda(fila, col, r.conteoCod() == null ? 0L : (long) r.conteoCod(), e.numero);
      }
      cerrarTabla(hoja, inicio, f, cabecera.length, 14);
    }

    private void hojaOperarios() {
      Sheet hoja = libro.createSheet("Operarios por ola");
      List<String> olas = dash.olas().stream().map(OlaSimulacionResponse::cluster).toList();
      String[] cabecera = new String[olas.size() + 1];
      cabecera[0] = "Chute";
      for (int i = 0; i < olas.size(); i++) {
        cabecera[i + 1] = olas.get(i);
      }
      int f = titulo(hoja, 0, "Operarios de cada chute en cada ola", cabecera.length);
      f = cabecera(hoja, f + 1, 0, cabecera);
      int inicio = f;
      long[] sumas = new long[olas.size()];
      for (DotacionChuteResponse d : dash.dotacion()) {
        Row fila = hoja.createRow(f++);
        celda(fila, 0, d.chute(), e.fuerte);
        for (int i = 0; i < olas.size(); i++) {
          int valor = d.operarios() != null && i < d.operarios().size() && d.operarios().get(i) != null
              ? d.operarios().get(i) : 0;
          sumas[i] += valor;
          celda(fila, i + 1, valor == 0 ? "" : (Object) (long) valor, valor == 0 ? e.vacia : e.numero);
        }
      }
      Row total = hoja.createRow(f++);
      celda(total, 0, "Total de la ola", e.etiquetaTotal);
      for (int i = 0; i < sumas.length; i++) {
        celda(total, i + 1, sumas[i], e.totalNumero);
      }
      cerrarTabla(hoja, inicio, f - 1, cabecera.length, 12);
      if (!olas.isEmpty() && f - 1 > inicio) {
        // Categorías: los nombres de las olas (cabecera); valores: la fila «Total de la ola».
        int g = seccion(hoja, f + 1, "Operarios por ola", Math.max(8, cabecera.length));
        grafico(
            hoja, g, 0, Math.max(8, cabecera.length), 15, "Operarios por ola",
            new CellRangeAddress(inicio - 1, inicio - 1, 1, olas.size()),
            List.of(new Serie("Operarios", new CellRangeAddress(f - 1, f - 1, 1, olas.size()), "1D63ED")),
            "#,##0");
      }
    }

    private void hojaHistorial() {
      Sheet hoja = libro.createSheet("Historial");
      String[] cabecera = {
        "Fecha", "Acción", "Motivo", "Ruta", "Cluster", "Tipo", "Bultos", "Chute origen",
        "Chute destino", "Cluster origen", "Cluster destino", "Operarios antes", "Operarios después"
      };
      int f = titulo(hoja, 0, "Historial de cambios", cabecera.length);
      f = cabecera(hoja, f + 1, 0, cabecera);
      int inicio = f;
      for (HistorialSimulacionResponse h : historial) {
        Row fila = hoja.createRow(f++);
        int col = 0;
        celda(fila, col++, h.creadoEn() == null ? "" : FECHA.format(h.creadoEn().atZone(ZONA)), e.texto);
        celda(fila, col++, accion(h.tipoAccion()), e.texto);
        celda(fila, col++, h.motivo() == null ? "" : h.motivo(), e.texto);
        celda(fila, col++, nulo(h.ruta()), e.fuerte);
        celda(fila, col++, nulo(h.cluster()), e.texto);
        celda(fila, col++, nulo(h.tipo()), e.centro);
        celda(fila, col++, h.bultos() == null ? "" : (Object) entero(h.bultos()), e.numero);
        celda(fila, col++, nulo(h.chuteOrigen() != null ? h.chuteOrigen() : h.chuteModificado()), e.texto);
        celda(fila, col++, nulo(h.chuteDestino()), e.texto);
        celda(fila, col++, nulo(h.clusterOrigen()), e.texto);
        celda(fila, col++, nulo(h.clusterDestino()), e.texto);
        celda(fila, col++, h.operariosOrigen() == null ? "" : (Object) (long) h.operariosOrigen(), e.numero);
        celda(fila, col, h.operariosDestino() == null ? "" : (Object) (long) h.operariosDestino(), e.numero);
      }
      cerrarTabla(hoja, inicio, f, cabecera.length, 15);
    }

    // ---------------- Utilidades de hoja ----------------

    private void apaisada(Sheet hoja) {
      PrintSetup impresion = hoja.getPrintSetup();
      impresion.setLandscape(true);
      impresion.setPaperSize(PrintSetup.A4_PAPERSIZE);
      hoja.setFitToPage(true);
      impresion.setFitWidth((short) 1);
      impresion.setFitHeight((short) 0);
      hoja.setDisplayGridlines(false);
    }

    private void cerrarTabla(Sheet hoja, int inicio, int fin, int columnas, int ancho) {
      for (int i = 0; i < columnas; i++) {
        hoja.setColumnWidth(i, ancho * 256);
      }
      if (fin > inicio) {
        hoja.setAutoFilter(new CellRangeAddress(inicio - 1, fin - 1, 0, columnas - 1));
      }
      hoja.createFreezePane(1, inicio);
      apaisada(hoja);
    }

    private int titulo(Sheet hoja, int f, String texto, int columnas) {
      Row fila = hoja.createRow(f);
      fila.setHeightInPoints(28);
      celda(fila, 0, texto, e.titulo);
      if (columnas > 1) {
        hoja.addMergedRegion(new CellRangeAddress(f, f, 0, columnas - 1));
      }
      return f + 1;
    }

    private int seccion(Sheet hoja, int f, String texto, int columnas) {
      Row fila = hoja.createRow(f);
      fila.setHeightInPoints(22);
      for (int i = 0; i < Math.max(1, columnas); i++) {
        celda(fila, i, i == 0 ? texto : "", e.seccion);
      }
      if (columnas > 1) {
        hoja.addMergedRegion(new CellRangeAddress(f, f, 0, columnas - 1));
      }
      return f + 1;
    }

    private int texto(Sheet hoja, int f, String texto, CellStyle estilo) {
      celda(hoja.createRow(f), 0, texto, estilo);
      return f + 1;
    }

    private int cabecera(Sheet hoja, int f, int desde, String[] textos) {
      Row fila = hoja.createRow(f);
      fila.setHeightInPoints(22);
      for (int i = 0; i < textos.length; i++) {
        celda(fila, desde + i, textos[i], e.cabecera);
      }
      return f + 1;
    }

    private void par(Row fila, int col, String etiqueta, Object valor) {
      celda(fila, col, etiqueta, e.etiqueta);
      fila.getSheet().addMergedRegion(new CellRangeAddress(fila.getRowNum(), fila.getRowNum(), col, col + 3));
      celda(fila, col + 4, valor, valor instanceof Number ? e.kpi : e.kpiTexto);
      fila.getSheet().addMergedRegion(new CellRangeAddress(fila.getRowNum(), fila.getRowNum(), col + 4, col + 5));
    }

    private static void celda(Row fila, int col, Object valor, CellStyle estilo) {
      Cell celda = fila.createCell(col);
      if (valor instanceof Number numero) {
        celda.setCellValue(numero.doubleValue());
      } else {
        celda.setCellValue(valor == null ? "" : valor.toString());
      }
      celda.setCellStyle(estilo);
    }

    private String nombre(Long chuteId) {
      String nombre = nombreChute.get(chuteId);
      if (nombre == null) {
        return "Chute " + chuteId;
      }
      return nombre.trim().toLowerCase(Locale.ROOT).startsWith("chute") ? nombre : "CHUTE " + nombre;
    }

    private long cerrados() {
      return cfg.clusters().stream().filter((c) -> "CERRADO".equals(c.estado())).count();
    }

    private static boolean conLimite(ClusterComparativoResponse c) {
      return valor(c.limiteHoras()).signum() > 0
          && c.tiempoBalanceado() != null
          && !sinOperarios(c.tiempoBalanceado());
    }

    /** Diferencia de bultos entre los chutes del cluster, en %: (máx − mín) / promedio. */
    private static Integer dispersion(Map<Long, CargaSimulacionResponse> suya) {
      List<BigDecimal> valores = suya.values().stream().map(CargaSimulacionResponse::bultos)
          .filter((v) -> v != null && v.signum() > 0).toList();
      if (valores.size() < 2) {
        return null;
      }
      BigDecimal suma = valores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal promedio = suma.divide(BigDecimal.valueOf(valores.size()), 6, RoundingMode.HALF_UP);
      BigDecimal rango = valores.stream().max(BigDecimal::compareTo).orElseThrow()
          .subtract(valores.stream().min(BigDecimal::compareTo).orElseThrow());
      return rango.multiply(BigDecimal.valueOf(100)).divide(promedio, 0, RoundingMode.HALF_UP).intValue();
    }

    private static long clave(Long cluster, Long chute) {
      return cluster * 1_000_003L + chute;
    }
  }

  // ------------------------------------------------------------------
  // Formatos (mismos criterios que la pantalla)
  // ------------------------------------------------------------------

  /** Entero hacia arriba: 199.1 → 200. */
  static long entero(BigDecimal valor) {
    return valor == null ? 0 : valor.setScale(0, RoundingMode.CEILING).longValueExact();
  }

  static String formatoEntero(BigDecimal valor) {
    return String.format(Locale.US, "%,d", entero(valor));
  }

  private static BigDecimal valor(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static boolean sinOperarios(BigDecimal horas) {
    return horas != null && horas.compareTo(SIN_OPERARIOS) >= 0;
  }

  /** «1h 25m», como `formatearHoras` del frontend. */
  static String horas(BigDecimal horas) {
    if (horas == null) {
      return "—";
    }
    if (sinOperarios(horas)) {
      return "Sin operarios";
    }
    if (horas.signum() <= 0) {
      return "0m";
    }
    long minutos = horas.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP).longValue();
    long h = minutos / 60;
    long m = minutos % 60;
    if (h == 0) {
      return m + "m";
    }
    return m == 0 ? h + "h" : h + "h " + m + "m";
  }

  private static Long mejora(BigDecimal antes, BigDecimal despues) {
    if (antes.signum() <= 0 || sinOperarios(antes) || sinOperarios(despues)) {
      return null;
    }
    return antes.subtract(despues).multiply(BigDecimal.valueOf(100))
        .divide(antes, 0, RoundingMode.HALF_UP).longValue();
  }

  private static Long uso(BigDecimal tiempo, BigDecimal limite) {
    if (tiempo == null || limite.signum() <= 0 || sinOperarios(tiempo)) {
      return null;
    }
    return tiempo.multiply(BigDecimal.valueOf(100)).divide(limite, 0, RoundingMode.CEILING).longValue();
  }

  private static String porcentaje(Long valor) {
    return valor == null ? "—" : valor + " %";
  }

  private static String nulo(String texto) {
    return texto == null ? "" : texto;
  }

  private static String estadoRegistro(String estado) {
    return switch (estado == null ? "" : estado) {
      case "FINALIZADO" -> "Simulación terminada";
      case "EN_PROCESO" -> "Simulación en proceso";
      default -> "Proyección";
    };
  }

  private static String estadoCluster(String estado) {
    return switch (estado == null ? "" : estado) {
      case "CERRADO" -> "Cerrado";
      case "LISTO" -> "Operarios asignados";
      case "RUTAS" -> "Rutas balanceadas";
      default -> "En espera";
    };
  }

  private static String accion(String tipo) {
    return switch (tipo == null ? "" : tipo) {
      case "MOVER_RUTA" -> "Mover ruta";
      case "CAMBIAR_CLUSTER" -> "Cambiar de cluster";
      case "ASIGNAR_OPERARIOS" -> "Asignar operarios";
      default -> tipo == null ? "" : tipo.replace('_', ' ').toLowerCase(Locale.ROOT);
    };
  }

  /** Estilos del libro, creados una vez (Excel admite pocos miles). */
  private static final class Estilos {
    final CellStyle titulo;
    final CellStyle subtitulo;
    final CellStyle seccion;
    final CellStyle cabecera;
    final CellStyle texto;
    final CellStyle fuerte;
    final CellStyle centro;
    final CellStyle derecha;
    final CellStyle numero;
    final CellStyle totalNumero;
    final CellStyle etiqueta;
    final CellStyle etiquetaTotal;
    final CellStyle kpi;
    final CellStyle kpiTexto;
    final CellStyle ruta;
    final CellStyle rutaMovida;
    final CellStyle vacia;
    final CellStyle resaltado;
    final CellStyle verde;
    final CellStyle rojo;
    final CellStyle semBajo;
    final CellStyle semMedio;
    final CellStyle semAlto;
    /** Horas como hora de Excel: «2:31» (2 h 31 min); suma bien y se grafica. */
    final CellStyle hora;
    /** Comparativo: «antes» atenuado, «después» en negrita y la variación con signo. */
    final CellStyle antes;
    final CellStyle despues;
    final CellStyle variacion;

    Estilos(XSSFWorkbook libro) {
      short miles = libro.createDataFormat().getFormat("#,##0");
      hora = estilo(libro, null, fuente(libro, 10, true, "0F172A"), HorizontalAlignment.RIGHT, true);
      hora.setDataFormat(libro.createDataFormat().getFormat("[h]:mm"));
      antes = estilo(libro, null, fuente(libro, 10, false, "64748B"), HorizontalAlignment.RIGHT, true);
      antes.setDataFormat(miles);
      despues = estilo(libro, null, fuente(libro, 10, true, "0F172A"), HorizontalAlignment.RIGHT, true);
      despues.setDataFormat(miles);
      variacion = estilo(libro, "F8FAFC", fuente(libro, 9, true, "1D63ED"), HorizontalAlignment.RIGHT, true);
      variacion.setDataFormat(libro.createDataFormat().getFormat("+0%;[Color10]-0%;0%"));
      titulo = estilo(libro, null, fuente(libro, 16, true, "0F1E3D"), HorizontalAlignment.LEFT, false);
      subtitulo = estilo(libro, null, fuente(libro, 10, false, "64748B"), HorizontalAlignment.LEFT, false);
      seccion = estilo(libro, "E2E8F0", fuente(libro, 11, true, "0F1E3D"), HorizontalAlignment.LEFT, false);
      cabecera = estilo(libro, "0F1E3D", fuente(libro, 10, true, "FFFFFF"), HorizontalAlignment.CENTER, true);
      texto = estilo(libro, null, fuente(libro, 10, false, "1E293B"), HorizontalAlignment.LEFT, true);
      fuerte = estilo(libro, null, fuente(libro, 10, true, "0F172A"), HorizontalAlignment.LEFT, true);
      centro = estilo(libro, null, fuente(libro, 10, false, "1E293B"), HorizontalAlignment.CENTER, true);
      derecha = estilo(libro, null, fuente(libro, 10, false, "1E293B"), HorizontalAlignment.RIGHT, true);
      numero = estilo(libro, null, fuente(libro, 10, false, "1E293B"), HorizontalAlignment.RIGHT, true);
      numero.setDataFormat(miles);
      totalNumero = estilo(libro, "F1F5F9", fuente(libro, 10, true, "0F172A"), HorizontalAlignment.RIGHT, true);
      totalNumero.setDataFormat(miles);
      etiqueta = estilo(libro, "F8FAFC", fuente(libro, 9, true, "475569"), HorizontalAlignment.LEFT, true);
      etiquetaTotal = estilo(libro, "F1F5F9", fuente(libro, 9, true, "334155"), HorizontalAlignment.LEFT, true);
      kpi = estilo(libro, null, fuente(libro, 12, true, "0F1E3D"), HorizontalAlignment.RIGHT, true);
      kpi.setDataFormat(miles);
      kpiTexto = estilo(libro, null, fuente(libro, 12, true, "0F1E3D"), HorizontalAlignment.RIGHT, true);
      ruta = estilo(libro, null, fuente(libro, 9, true, "0F172A"), HorizontalAlignment.LEFT, true);
      rutaMovida = estilo(libro, "EEF2FF", fuente(libro, 9, true, "3730A3"), HorizontalAlignment.LEFT, true);
      vacia = estilo(libro, null, fuente(libro, 9, false, "94A3B8"), HorizontalAlignment.CENTER, true);
      resaltado = estilo(libro, "EEF2FF", fuente(libro, 10, true, "3730A3"), HorizontalAlignment.LEFT, true);
      verde = estilo(libro, "D1FAE5", fuente(libro, 10, true, "065F46"), HorizontalAlignment.CENTER, true);
      rojo = estilo(libro, "FEE2E2", fuente(libro, 10, true, "991B1B"), HorizontalAlignment.CENTER, true);
      semBajo = estilo(libro, "FEF3C7", fuente(libro, 10, true, "92400E"), HorizontalAlignment.RIGHT, true);
      semBajo.setDataFormat(miles);
      semMedio = estilo(libro, "D1FAE5", fuente(libro, 10, true, "065F46"), HorizontalAlignment.RIGHT, true);
      semMedio.setDataFormat(miles);
      semAlto = estilo(libro, "FEE2E2", fuente(libro, 10, true, "991B1B"), HorizontalAlignment.RIGHT, true);
      semAlto.setDataFormat(miles);
    }

    private static XSSFFont fuente(XSSFWorkbook libro, int puntos, boolean negrita, String color) {
      XSSFFont fuente = libro.createFont();
      fuente.setFontName("Calibri");
      fuente.setFontHeightInPoints((short) puntos);
      fuente.setBold(negrita);
      fuente.setColor(color(color));
      return fuente;
    }

    private static XSSFCellStyle estilo(
        XSSFWorkbook libro, String fondo, XSSFFont fuente, HorizontalAlignment alineacion, boolean borde) {
      XSSFCellStyle estilo = libro.createCellStyle();
      estilo.setFont(fuente);
      estilo.setAlignment(alineacion);
      estilo.setVerticalAlignment(VerticalAlignment.CENTER);
      if (fondo != null) {
        estilo.setFillForegroundColor(color(fondo));
        estilo.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      }
      if (borde) {
        XSSFColor gris = color("E2E8F0");
        estilo.setBorderBottom(BorderStyle.THIN);
        estilo.setBorderTop(BorderStyle.THIN);
        estilo.setBorderLeft(BorderStyle.THIN);
        estilo.setBorderRight(BorderStyle.THIN);
        estilo.setBottomBorderColor(gris);
        estilo.setTopBorderColor(gris);
        estilo.setLeftBorderColor(gris);
        estilo.setRightBorderColor(gris);
      }
      return estilo;
    }

    private static XSSFColor color(String hex) {
      int rgb = Integer.parseInt(hex, 16);
      return new XSSFColor(
          new byte[] {(byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb}, null);
    }
  }
}
