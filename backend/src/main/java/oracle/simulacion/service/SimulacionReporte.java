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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import oracle.simulacion.model.Balanceo;
import oracle.simulacion.response.CargaSimulacionResponse;
import oracle.simulacion.response.ChuteComparativoResponse;
import oracle.simulacion.response.ChuteParametroResponse;
import oracle.simulacion.response.ClusterComparativoResponse;
import oracle.simulacion.response.ClusterParametroResponse;
import oracle.simulacion.response.ConfiguracionSimulacionResponse;
import oracle.simulacion.response.DashboardSimulacionResponse;
import oracle.simulacion.response.GrupoClusterResponse;
import oracle.simulacion.response.OlaSimulacionResponse;
import oracle.simulacion.response.PaginaRutasResponse;
import oracle.simulacion.response.RutaSimulacionResponse;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.FontFormatting;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PatternFormatting;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
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
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTBarChart;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTDLbls;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTNumFmt;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTValAx;
import org.openxmlformats.schemas.drawingml.x2006.chart.STDLblPos;
import org.springframework.stereotype.Service;

/**
 * Exportación a Excel de una simulación: lo mismo que el PDF ejecutivo, en tres hojas (una por
 * página del PDF) y con un selector de frecuencia.
 *
 * <ul>
 *   <li>«Resumen»: el selector, los indicadores, el resultado por ola y las conclusiones.
 *   <li>«Tiempos y comparativos»: cierre establecido vs. conseguido, tiempo proyectado vs.
 *       balanceado (si el Excel trae dotación base), comparativo de clusters y bultos por chute.
 *   <li>«Configuración»: las cuadrículas de rutas por cluster y chute, con grupos, franja de
 *       tramos y totales en semáforo.
 * </ul>
 *
 * Cada día (L-X-V y M-J-S) tiene sus propios números: los dos se guardan en la hoja oculta
 * «Datos» y cada celda visible muestra, con INDEX, el del día elegido en el selector. Así nada se
 * suma dos veces (las diarias salen los dos días) y gráficos, semáforo y conclusiones siguen al
 * selector. El libro se entrega ya calculado con el primer día que tiene rutas, para que la vista
 * previa del Mac o del correo no salga vacía.
 *
 * Solo lee: reutiliza las consultas (y su caché) de la configuración y del dashboard, dos por día.
 */
@Service
public class SimulacionReporte {

  private static final int PAGINA_RUTAS = 1_000;
  private static final ZoneId ZONA = ZoneId.of("America/Lima");
  private static final DateTimeFormatter FECHA =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.forLanguageTag("es-PE"));
  /** Tiempo «sin operarios» del servicio (1E+30): no es un valor real. */
  private static final BigDecimal SIN_OPERARIOS = new BigDecimal("1E+29");

  private final SimulacionConfiguracionService servicio;

  public SimulacionReporte(SimulacionConfiguracionService servicio) {
    this.servicio = servicio;
  }

  /**
   * Un solo libro con los dos días: la configuración y el dashboard de cada uno (dos consultas por
   * día) y las rutas del registro, leídas una vez y repartidas por día.
   */
  public byte[] excel(Long archivoId) {
    List<RutaSimulacionResponse> todas = todasLasRutas(archivoId);
    List<Dia> dias = new ArrayList<>();
    for (Balanceo dia : Balanceo.DIAS) {
      dias.add(
          new Dia(
              dia,
              servicio.obtener(archivoId, dia).datos(),
              servicio.dashboard(archivoId, dia, null).datos(),
              todas.stream().filter((r) -> dia.incluye(r.frecuencia())).toList()));
    }
    return new Libro(dias).escribir();
  }

  /** «simulacion-proyeccion-enero.xlsx»: un solo archivo, sin el día en el nombre. */
  public String nombreArchivo(Long archivoId) {
    // Solo el nombre del registro: no hace falta calcular la configuración.
    String nombre = servicio.nombreRegistro(archivoId);
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

  /** Lo que se muestra de un día: su configuración, su dashboard y sus rutas (con las diarias). */
  record Dia(
      Balanceo balanceo,
      ConfiguracionSimulacionResponse cfg,
      DashboardSimulacionResponse dash,
      List<RutaSimulacionResponse> rutas) {

    /** El día tiene rutas (propias o diarias): se ofrece en el selector. */
    boolean conRutas() {
      return !rutas.isEmpty() || cfg.carga().stream().anyMatch((c) -> c.rutas() > 0);
    }
  }

  // ------------------------------------------------------------------

  /** Arma el libro con los datos ya leídos (sin acceso a la base). */
  static final class Libro {

    private static final Comparator<String> NATURAL = SimulacionConfiguracionService::compararNatural;
    /** Mismos colores de grupo que la planilla y el PDF (PALETA_GRUPOS del frontend). */
    private static final String[] PALETA_GRUPOS = {
      "7C3AED", "0891B2", "DB2777", "4F46E5", "0D9488", "C2410C", "9333EA", "0369A1"
    };
    private static final String[] CONCEPTOS = {
      "Total operarios", "Total bultos", "Total líneas", "Total unidades", "Total locales"
    };
    private static final String[] METRICAS = {"Rutas", "Bultos", "Unidades", "Líneas", "COD"};
    /** Celda del selector de frecuencia en «Resumen» (C2). */
    private static final String SELECTOR = "Resumen!$C$2";
    /** Celda de «Datos» con la columna del día elegido (1 = L-X-V, 2 = M-J-S). */
    private static final String DIA_ELEGIDO = "Datos!$A$1";
    private static final int CHUTES_POR_GRAFICO = 15;

    private final List<Dia> dias;
    /** Días que se ofrecen en el selector (con rutas); el primero es el que se entrega calculado. */
    private final List<Dia> disponibles;
    private final Map<Long, String> nombreChute = new HashMap<>();
    /** Clusters en el orden de las olas (el mismo en los dos días). */
    private final List<ClusterParametroResponse> clusters;

    private XSSFWorkbook libro;
    private Estilos e;
    private Sheet datos;
    private int filaDatos = 1;
    private final List<Grafico> graficos = new ArrayList<>();
    private final Map<String, CellStyle> estilosGrupo = new HashMap<>();

    Libro(List<Dia> dias) {
      if (dias.isEmpty()) {
        throw new IllegalArgumentException("El reporte necesita al menos un día.");
      }
      this.dias = List.copyOf(dias);
      List<Dia> conRutas = dias.stream().filter(Dia::conRutas).toList();
      this.disponibles = conRutas.isEmpty() ? List.of(dias.getFirst()) : conRutas;
      this.clusters = dias.getFirst().cfg().clusters();
      for (Dia dia : dias) {
        for (ChuteParametroResponse chute : dia.cfg().chutes()) {
          nombreChute.put(chute.id(), chute.nombre());
        }
        for (RutaSimulacionResponse ruta : dia.rutas()) {
          nombreChute.putIfAbsent(ruta.chuteId(), ruta.chute());
        }
      }
    }

    byte[] escribir() {
      try (XSSFWorkbook nuevo = new XSSFWorkbook();
          ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
        libro = nuevo;
        e = new Estilos(libro);
        Sheet resumen = libro.createSheet("Resumen");
        Sheet tiempos = libro.createSheet("Tiempos y comparativos");
        Sheet configuracion = libro.createSheet("Configuración");
        datos = libro.createSheet("Datos");
        prepararDatos();
        hojaResumen(resumen);
        hojaTiempos(tiempos);
        hojaConfiguracion(configuracion);
        libro.setSheetHidden(libro.getSheetIndex(datos), true);
        libro.setActiveSheet(0);
        // Calculado con el día por defecto: la vista previa (que no recalcula) no sale vacía.
        libro.getCreationHelper().createFormulaEvaluator().evaluateAll();
        // Los gráficos leen sus celdas ya calculadas.
        graficos.forEach(this::dibujar);
        libro.setForceFormulaRecalculation(true);
        libro.write(salida);
        return salida.toByteArray();
      } catch (IOException error) {
        throw new UncheckedIOException("No se pudo generar el Excel de la simulación.", error);
      }
    }

    // ---------------- Datos por día y selector ----------------

    /** Fila 1 de «Datos»: el día elegido (A1) y la etiqueta de cada día (B1, C1…). */
    private void prepararDatos() {
      Row cabecera = datos.createRow(0);
      String ultima = columna(dias.size());
      Cell elegido = cabecera.createCell(0);
      elegido.setCellFormula("IFERROR(MATCH(" + SELECTOR + ",$B$1:$" + ultima + "$1,0),1)");
      for (int i = 0; i < dias.size(); i++) {
        cabecera.createCell(i + 1).setCellValue(dias.get(i).balanceo().etiqueta());
      }
      datos.setColumnWidth(0, 30 * 256);
    }

    /**
     * Celda visible con el valor del día elegido: guarda el valor de cada día en una fila de
     * «Datos» y la celda lo toma con INDEX. Números como número; texto (o vacío) como texto.
     */
    private Cell porDia(Row fila, int col, Function<Dia, Object> valor, CellStyle estilo) {
      int r = filaDatos++;
      Row fuente = datos.createRow(r);
      fuente.createCell(0).setCellValue(fila.getSheet().getSheetName() + "!" + new CellReference(fila.getRowNum(), col).formatAsString());
      for (int i = 0; i < dias.size(); i++) {
        Object v = valor.apply(dias.get(i));
        Cell c = fuente.createCell(i + 1);
        if (v instanceof Number n) {
          c.setCellValue(n.doubleValue());
        } else {
          c.setCellValue(v == null ? "" : v.toString());
        }
      }
      Cell celda = fila.createCell(col);
      celda.setCellFormula(
          "INDEX(Datos!$B$" + (r + 1) + ":$" + columna(dias.size()) + "$" + (r + 1) + ",1," + DIA_ELEGIDO + ")");
      celda.setCellStyle(estilo);
      return celda;
    }

    /** Letra de la columna n (0 = A). */
    private static String columna(int n) {
      return CellReference.convertNumToColString(n);
    }

    /** Fila de aviso al pie del título de cada hoja: qué día se ve y dónde se cambia. */
    private int avisoFrecuencia(Sheet hoja, int f, int columnas) {
      Row fila = hoja.createRow(f);
      fila.setHeightInPoints(20);
      Cell c = fila.createCell(0);
      c.setCellFormula("\"Frecuencia: \"&" + SELECTOR + "&\"  ·  se cambia en la hoja Resumen\"");
      c.setCellStyle(e.aviso);
      if (columnas > 1) {
        hoja.addMergedRegion(new CellRangeAddress(f, f, 0, columnas - 1));
      }
      return f + 1;
    }

    // ---------------- Cálculos por día (los del PDF) ----------------

    /** Cierre establecido vs. conseguido de las olas con límite y operarios: base de la mejora. */
    private record Cierres(BigDecimal establecido, BigDecimal conseguido, int olas, Long mejora) {}

    private static Cierres cierres(Dia d) {
      BigDecimal establecido = BigDecimal.ZERO;
      BigDecimal conseguido = BigDecimal.ZERO;
      int olas = 0;
      for (ClusterComparativoResponse c : d.dash().clusters()) {
        if (conLimite(c)) {
          establecido = establecido.add(valor(c.limiteHoras()));
          conseguido = conseguido.add(valor(c.tiempoBalanceado()));
          olas++;
        }
      }
      return new Cierres(establecido, conseguido, olas, olas == 0 ? null : mejora(establecido, conseguido));
    }

    private static long totalRutas(Dia d) {
      return d.rutas().size();
    }

    private static long totalBultos(Dia d, Function<RutaSimulacionResponse, BigDecimal> campo) {
      return entero(d.rutas().stream().map(campo).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static long totalCod(Dia d) {
      return d.rutas().stream().mapToLong((r) -> r.conteoCod() == null ? 0 : r.conteoCod()).sum();
    }

    private static long medibles(Dia d) {
      return d.dash().clusters().stream().filter(Libro::conLimite).count();
    }

    private static long cumplen(Dia d) {
      return d.dash().clusters().stream().filter((c) -> conLimite(c) && !c.excede()).count();
    }

    private static ClusterComparativoResponse comparativo(Dia d, Long clusterId) {
      return d.dash().clusters().stream().filter((c) -> c.id().equals(clusterId)).findFirst().orElse(null);
    }

    private static ClusterParametroResponse cluster(Dia d, Long clusterId) {
      return d.cfg().clusters().stream().filter((c) -> c.id().equals(clusterId)).findFirst().orElse(null);
    }

    /** Carga del cluster en ese día, por chute (solo los que tienen rutas). */
    private static Map<Long, CargaSimulacionResponse> cargaCluster(Dia d, Long clusterId) {
      Map<Long, CargaSimulacionResponse> suya = new LinkedHashMap<>();
      for (CargaSimulacionResponse fila : d.cfg().carga()) {
        if (fila.clusterId().equals(clusterId) && fila.rutas() > 0) {
          suya.put(fila.chuteId(), fila);
        }
      }
      return suya;
    }

    /** Las conclusiones del PDF, en el mismo orden. */
    private static List<String> conclusiones(Dia d) {
      List<String> frases = new ArrayList<>();
      Cierres cierres = cierres(d);
      if (cierres.mejora() != null) {
        frases.add("Cierre conseguido de " + horas(cierres.conseguido()) + " frente a " + horas(cierres.establecido())
            + " establecido (" + Math.abs(cierres.mejora()) + " % " + (cierres.mejora() >= 0 ? "por debajo" : "por encima")
            + "; " + cierres.olas() + " ola(s) con límite y operarios).");
      }
      BigDecimal antes = d.dash().kpis().tiempoOriginal();
      BigDecimal despues = d.dash().kpis().tiempoBalanceado();
      if (antes != null && despues != null && antes.signum() > 0 && !sinOperarios(antes) && !sinOperarios(despues)) {
        frases.add("Frente a la proyección del Excel, el tiempo pasa de " + horas(antes) + " a " + horas(despues) + ".");
      }
      d.dash().clusters().stream()
          .filter(Libro::conLimite)
          .filter(ClusterComparativoResponse::excede)
          .forEach((c) -> frases.add(c.cluster() + " excede su límite: " + horas(c.tiempoBalanceado())
              + " de " + horas(c.limiteHoras()) + "."));
      d.dash().olas().stream()
          .filter((o) -> o.tiempoCierre() != null && !sinOperarios(o.tiempoCierre()))
          .max(Comparator.comparing(OlaSimulacionResponse::tiempoCierre))
          .ifPresent((o) -> frases.add("La ola más larga es " + o.cluster() + " (" + horas(o.tiempoCierre()) + ")."));
      frases.add(String.format(Locale.US, "%,d", d.dash().kpis().rutasMovidas())
          + " ruta(s) cambiaron de chute o de cluster respecto del Excel.");
      if (!"FINALIZADO".equals(d.cfg().archivo().estado())) {
        long cerrados = d.cfg().clusters().stream().filter((c) -> "CERRADO".equals(c.estado())).count();
        frases.add("La simulación aún no termina: " + cerrados + " de " + d.cfg().clusters().size()
            + " clusters cerrados.");
      }
      return frases;
    }

    // ---------------- Hoja 1: Resumen ----------------

    private void hojaResumen(Sheet hoja) {
      apaisada(hoja);
      int[] anchos = {7, 18, 11, 9, 9, 11, 15, 15, 9, 11, 10, 6};
      for (int i = 0; i < anchos.length; i++) {
        hoja.setColumnWidth(i, anchos[i] * 256);
      }
      int total = anchos.length;
      Dia base = disponibles.getFirst();
      var archivo = base.cfg().archivo();
      int f = titulo(hoja, 0, "Reporte ejecutivo de simulación · " + archivo.nombreRegistro(), total);

      // Selector de frecuencia: lista con los días que tienen rutas.
      Row filaSelector = hoja.createRow(f);
      filaSelector.setHeightInPoints(24);
      celda(filaSelector, 0, "Frecuencia", e.etiquetaSelector);
      hoja.addMergedRegion(new CellRangeAddress(f, f, 0, 1));
      celda(filaSelector, 1, "", e.etiquetaSelector);
      celda(filaSelector, 2, base.balanceo().etiqueta(), e.selector);
      celda(filaSelector, 3, "◀ elige L-X-V o M-J-S: todo el libro cambia (cada día tiene sus propios números)", e.pista);
      XSSFDataValidationHelper ayuda = new XSSFDataValidationHelper((XSSFSheet) hoja);
      DataValidationConstraint opciones =
          ayuda.createExplicitListConstraint(disponibles.stream().map((d) -> d.balanceo().etiqueta()).toArray(String[]::new));
      DataValidation validacion = ayuda.createValidation(opciones, new CellRangeAddressList(f, f, 2, 2));
      // En XSSF, «suprimir» la flecha es lo que la muestra.
      validacion.setSuppressDropDownArrow(true);
      validacion.setShowErrorBox(true);
      validacion.createErrorBox("Frecuencia", "Elige una de la lista.");
      validacion.setShowPromptBox(true);
      validacion.createPromptBox("Frecuencia", "Elige el día: los números del libro cambian a ese día.");
      hoja.addValidationData(validacion);
      f++;

      // Portada: estado, rutas del día, chutes, velocidad y tope por chute.
      Row meta = hoja.createRow(f++);
      porDia(meta, 0, (d) -> estadoRegistro(d.cfg().archivo().estado()) + " · "
          + String.format(Locale.US, "%,d", totalRutas(d)) + " rutas de " + d.balanceo().etiqueta() + " · "
          + d.cfg().archivo().chutes() + " chutes · velocidad " + formatoEntero(d.cfg().velocidadBase())
          + " bultos/h por operario · "
          + (d.cfg().operariosPorChute() == null ? "sin tope por chute" : "máx. " + d.cfg().operariosPorChute() + " por chute")
          + " · generado el " + FECHA.format(Instant.now().atZone(ZONA)), e.subtitulo);
      if (!"FINALIZADO".equals(archivo.estado())) {
        f = texto(hoja, f, "⚠ La simulación aún no termina: los datos son parciales.", e.alerta);
      }
      f++;

      // Indicadores: los seis del PDF, tres por fila.
      f = seccion(hoja, f, "Indicadores", total);
      List<Kpi> kpis = List.of(
          new Kpi("Cierre conseguido",
              (d) -> cierres(d).olas() == 0 ? "—" : horas(cierres(d).conseguido()),
              (d) -> cierres(d).olas() == 0 ? "Se calcula cuando cada ola tiene operarios"
                  : "Establecido " + horas(cierres(d).establecido()) + " · " + cierres(d).olas() + " ola(s)"),
          new Kpi("Mejora del tiempo",
              (d) -> cierres(d).mejora() == null ? "—" : (cierres(d).mejora() > 0 ? "+" : "") + cierres(d).mejora() + " %",
              (d) -> cierres(d).mejora() == null ? "Cierre conseguido frente al establecido"
                  : cierres(d).mejora() >= 0 ? "Cierre conseguido por debajo del establecido"
                      : "Cierre conseguido por encima del establecido"),
          new Kpi("Clusters que cumplen",
              (d) -> medibles(d) == 0 ? "—" : cumplen(d) + " de " + medibles(d),
              (d) -> "Contra su límite de horas"),
          new Kpi("Rutas movidas",
              (d) -> String.format(Locale.US, "%,d", d.dash().kpis().rutasMovidas()),
              (d) -> "De " + String.format(Locale.US, "%,d", totalRutas(d)) + " rutas de " + d.balanceo().etiqueta()),
          new Kpi("Bultos",
              (d) -> String.format(Locale.US, "%,d", totalBultos(d, RutaSimulacionResponse::totalBultos)),
              (d) -> String.format(Locale.US, "%,d", totalBultos(d, RutaSimulacionResponse::totalUnidades)) + " unidades · "
                  + String.format(Locale.US, "%,d", totalBultos(d, RutaSimulacionResponse::totalLineas)) + " líneas"),
          new Kpi("Chutes",
              (d) -> String.format(Locale.US, "%,d", d.cfg().archivo().chutes()),
              (d) -> String.format(Locale.US, "%,d", totalCod(d)) + " locales (COD)"));
      for (int i = 0; i < kpis.size(); i += 3) {
        Row etiquetas = hoja.createRow(f);
        Row valores = hoja.createRow(f + 1);
        Row detalles = hoja.createRow(f + 2);
        etiquetas.setHeightInPoints(18);
        valores.setHeightInPoints(28);
        detalles.setHeightInPoints(18);
        for (int k = 0; k < 3 && i + k < kpis.size(); k++) {
          Kpi kpi = kpis.get(i + k);
          int col = k * 4;
          celda(etiquetas, col, kpi.etiqueta(), e.kpiEtiqueta);
          porDia(valores, col, kpi.valor(), e.kpi);
          porDia(detalles, col, kpi.detalle(), e.kpiDetalle);
          for (int r = 0; r < 3; r++) {
            Row fila = hoja.getRow(f + r);
            for (int c = col + 1; c < col + 4; c++) {
              celda(fila, c, "", r == 0 ? e.kpiEtiqueta : r == 1 ? e.kpi : e.kpiDetalle);
            }
            hoja.addMergedRegion(new CellRangeAddress(f + r, f + r, col, col + 3));
          }
        }
        f += 4;
      }

      // Resultado por ola: las columnas del PDF.
      f = seccion(hoja, f, "Resultado por ola", total);
      String[] cabecera = {
        "Ola", "Cluster", "Operarios", "Chutes", "Rutas", "Bultos", "Cierre establecido",
        "Cierre conseguido", "Uso", "Dif. bultos", "Cumple"
      };
      f = cabecera(hoja, f, 0, cabecera);
      int inicio = f;
      int ola = 1;
      for (ClusterParametroResponse c : clusters) {
        Long id = c.id();
        Row fila = hoja.createRow(f++);
        fila.setHeightInPoints(20);
        celda(fila, 0, (long) ola++, e.centro);
        celda(fila, 1, c.nombre(), e.fuerte);
        porDia(fila, 2, (d) -> {
          ClusterParametroResponse suyo = cluster(d, id);
          return suyo == null || suyo.operariosOla() == null ? "—" : (Object) (long) suyo.operariosOla();
        }, e.numero);
        porDia(fila, 3, (d) -> (long) cargaCluster(d, id).size(), e.numero);
        porDia(fila, 4, (d) -> cargaCluster(d, id).values().stream().mapToLong(CargaSimulacionResponse::rutas).sum(), e.numero);
        porDia(fila, 5, (d) -> entero(cargaCluster(d, id).values().stream().map(CargaSimulacionResponse::bultos)
            .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)), e.numero);
        // Como hora de Excel ([h]:mm): se lee igual que en pantalla.
        porDia(fila, 6, (d) -> {
          ClusterParametroResponse suyo = cluster(d, id);
          BigDecimal limite = suyo == null ? BigDecimal.ZERO : valor(suyo.limiteHoras());
          return limite.signum() > 0 ? (Object) (limite.doubleValue() / 24d) : "—";
        }, e.hora);
        porDia(fila, 7, (d) -> {
          ClusterComparativoResponse cc = comparativo(d, id);
          return cc == null || cc.tiempoBalanceado() == null || sinOperarios(cc.tiempoBalanceado())
                  || cc.tiempoBalanceado().signum() <= 0
              ? "—" : (Object) (cc.tiempoBalanceado().doubleValue() / 24d);
        }, e.horaFuerte);
        porDia(fila, 8, (d) -> {
          ClusterComparativoResponse cc = comparativo(d, id);
          ClusterParametroResponse suyo = cluster(d, id);
          return cc != null && suyo != null && conLimite(cc)
              ? porcentaje(uso(cc.tiempoBalanceado(), valor(suyo.limiteHoras()))) : "—";
        }, e.derecha);
        porDia(fila, 9, (d) -> {
          Integer dif = dispersion(cargaCluster(d, id));
          return dif == null ? "—" : dif + " %";
        }, e.derecha);
        porDia(fila, 10, (d) -> {
          ClusterComparativoResponse cc = comparativo(d, id);
          return cc != null && conLimite(cc) ? (cc.excede() ? "No" : "Sí") : "—";
        }, e.centro);
      }
      if (f > inicio) {
        // «Cumple»: verde o rojo según el día elegido.
        CellRangeAddress[] cumple = {new CellRangeAddress(inicio, f - 1, 10, 10)};
        String ref = "K" + (inicio + 1);
        regla(hoja, cumple, ref + "=\"Sí\"", "D1FAE5", "065F46", true);
        regla(hoja, cumple, ref + "=\"No\"", "FEE2E2", "991B1B", true);
      }
      f++;

      // Conclusiones del día elegido (cada día tiene las suyas).
      f = seccion(hoja, f, "Conclusiones", total);
      int frases = dias.stream().mapToInt((d) -> conclusiones(d).size()).max().orElse(0);
      for (int i = 0; i < frases; i++) {
        int n = i;
        Row fila = hoja.createRow(f++);
        fila.setHeightInPoints(18);
        porDia(fila, 0, (d) -> {
          List<String> lista = conclusiones(d);
          return n < lista.size() ? "• " + lista.get(n) : "";
        }, e.texto);
      }
      hoja.createFreezePane(0, 2);
    }

    /** Indicador del resumen: etiqueta fija, valor y detalle del día elegido. */
    private record Kpi(String etiqueta, Function<Dia, Object> valor, Function<Dia, Object> detalle) {}

    // ---------------- Hoja 2: Tiempos y comparativos ----------------

    private void hojaTiempos(Sheet hoja) {
      apaisada(hoja);
      int total = METRICAS.length * 3 + 3;
      hoja.setColumnWidth(0, 18 * 256);
      for (int i = 1; i < total; i++) {
        hoja.setColumnWidth(i, 11 * 256);
      }
      int f = titulo(hoja, 0, "Tiempos y comparativos", total);
      f = avisoFrecuencia(hoja, f, total);
      f++;

      // Cierre de cada ola: establecido vs. conseguido (cluster, límite y tiempo como horas).
      f = seccion(hoja, f, "Cierre de cada ola: establecido vs. conseguido", total);
      int grafico = f;
      f += 17;
      f = cabecera(hoja, f, 0, new String[] {"Cluster", "Cierre establecido", "Cierre conseguido"});
      int inicio = f;
      for (ClusterParametroResponse c : clusters) {
        Long id = c.id();
        Row fila = hoja.createRow(f++);
        celda(fila, 0, c.nombre(), e.fuerte);
        porDia(fila, 1, (d) -> {
          ClusterParametroResponse suyo = cluster(d, id);
          return suyo == null ? 0d : valor(suyo.limiteHoras()).doubleValue() / 24d;
        }, e.hora);
        porDia(fila, 2, (d) -> horaGrafico(comparativo(d, id) == null ? null : comparativo(d, id).tiempoBalanceado()), e.horaFuerte);
      }
      if (f > inicio) {
        graficos.add(new Grafico(hoja, grafico, total, 16, "Cierre de cada ola: establecido vs. conseguido",
            new CellRangeAddress(inicio, f - 1, 0, 0),
            List.of(new Serie("Cierre establecido", new CellRangeAddress(inicio, f - 1, 1, 1), "94A3B8"),
                new Serie("Cierre conseguido", new CellRangeAddress(inicio, f - 1, 2, 2), "7C3AED")),
            "[h]:mm"));
      }
      f++;

      // Tiempo de cada ola: proyección vs. balanceado, solo si el Excel trae dotación base.
      boolean hayTiemposAntes = dias.stream().flatMap((d) -> d.dash().clusters().stream())
          .anyMatch((c) -> c.tiempoOriginal() != null && !sinOperarios(c.tiempoOriginal()));
      if (hayTiemposAntes) {
        f = seccion(hoja, f, "Tiempo de cada ola: proyección vs. balanceado", total);
        grafico = f;
        f += 17;
        f = cabecera(hoja, f, 0, new String[] {"Cluster", "Proyección del Excel", "Después del balanceo"});
        inicio = f;
        for (ClusterParametroResponse c : clusters) {
          Long id = c.id();
          Row fila = hoja.createRow(f++);
          celda(fila, 0, c.nombre(), e.fuerte);
          porDia(fila, 1, (d) -> horaGrafico(comparativo(d, id) == null ? null : comparativo(d, id).tiempoOriginal()), e.hora);
          porDia(fila, 2, (d) -> horaGrafico(comparativo(d, id) == null ? null : comparativo(d, id).tiempoBalanceado()), e.horaFuerte);
        }
        graficos.add(new Grafico(hoja, grafico, total, 16, "Tiempo de cada ola: proyección vs. balanceado",
            new CellRangeAddress(inicio, f - 1, 0, 0),
            List.of(new Serie("Proyección del Excel", new CellRangeAddress(inicio, f - 1, 1, 1), "94A3B8"),
                new Serie("Después del balanceo", new CellRangeAddress(inicio, f - 1, 2, 2), "1D63ED")),
            "[h]:mm"));
        f++;
      }

      // Comparativo de clusters: gráfico de bultos y cuadro con las cinco métricas.
      f = seccion(hoja, f, "Comparativo de clusters: antes vs. después", total);
      f = texto(hoja, f, "«Antes» es la carga del Excel; «después», la de hoy (incluye las rutas cambiadas de"
          + " cluster). Var. = cambio porcentual.", e.subtitulo);
      grafico = f;
      f += 16;
      f = cabeceraComparativa(hoja, f, "Cluster", true);
      inicio = f;
      for (ClusterParametroResponse c : clusters) {
        Long id = c.id();
        Row fila = hoja.createRow(f++);
        celda(fila, 0, c.nombre(), e.fuerte);
        filaComparativa(fila, (d) -> comparativo(d, id) == null ? new long[METRICAS.length] : antesCluster(comparativo(d, id)),
            (d) -> comparativo(d, id) == null ? new long[METRICAS.length] : despuesCluster(comparativo(d, id)));
        porDia(fila, METRICAS.length * 3 + 1, (d) -> {
          ClusterComparativoResponse cc = comparativo(d, id);
          return cc == null || cc.rutasEntraron() == 0 ? "—" : "+" + cc.rutasEntraron();
        }, e.subeCentro);
        porDia(fila, METRICAS.length * 3 + 2, (d) -> {
          ClusterComparativoResponse cc = comparativo(d, id);
          return cc == null || cc.rutasSalieron() == 0 ? "—" : "−" + cc.rutasSalieron();
        }, e.bajaCentro);
      }
      int fin = f - 1;
      if (fin >= inicio) {
        Row totalFila = hoja.createRow(f++);
        celda(totalFila, 0, "Total", e.etiquetaTotal);
        filaComparativa(totalFila, (d) -> sumaFilas(d.dash().clusters().stream().map(Libro::antesCluster).toList()),
            (d) -> sumaFilas(d.dash().clusters().stream().map(Libro::despuesCluster).toList()));
        graficos.add(new Grafico(hoja, grafico, total, 15, "Bultos por cluster: antes vs. después",
            new CellRangeAddress(inicio, fin, 0, 0),
            List.of(new Serie("Bultos antes", new CellRangeAddress(inicio, fin, 4, 4), "94A3B8"),
                new Serie("Bultos después", new CellRangeAddress(inicio, fin, 5, 5), "1D63ED")),
            "#,##0"));
      }
      f++;

      // Bultos por chute (todos los clusters): gráficos de hasta 15 chutes, parejos, como el PDF.
      f = seccion(hoja, f, "Bultos por chute (todos los clusters): antes vs. después", total);
      List<String> chutes = new ArrayList<>(dias.stream().flatMap((d) -> d.dash().chutes().stream())
          .map(ChuteComparativoResponse::chute).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
      chutes.sort(PlanoChutes.ORDEN);
      if (chutes.isEmpty()) {
        texto(hoja, f, "Sin chutes con carga.", e.subtitulo);
        return;
      }
      int porGrafico = (int) Math.ceil(chutes.size() / Math.ceil(chutes.size() / (double) CHUTES_POR_GRAFICO));
      int partes = (int) Math.ceil(chutes.size() / (double) porGrafico);
      int primerGrafico = f;
      f += partes * 16;
      f = cabecera(hoja, f, 0, new String[] {"Chute", "Bultos antes", "Bultos después"});
      inicio = f;
      for (String chute : chutes) {
        Row fila = hoja.createRow(f++);
        celda(fila, 0, etiquetaChute(chute), e.fuerte);
        porDia(fila, 1, (d) -> (double) entero(chuteDe(d, chute) == null ? null : chuteDe(d, chute).bultosOriginal()), e.antes);
        porDia(fila, 2, (d) -> (double) entero(chuteDe(d, chute) == null ? null : chuteDe(d, chute).bultosBalanceado()), e.despues);
      }
      for (int p = 0; p < partes; p++) {
        int desde = inicio + p * porGrafico;
        int hasta = Math.min(inicio + chutes.size(), desde + porGrafico) - 1;
        graficos.add(new Grafico(hoja, primerGrafico + p * 16, total, 15,
            partes == 1 ? "Bultos por chute: antes vs. después" : "Bultos por chute (" + (p + 1) + " de " + partes + ")",
            new CellRangeAddress(desde, hasta, 0, 0),
            List.of(new Serie("Antes", new CellRangeAddress(desde, hasta, 1, 1), "94A3B8"),
                new Serie("Después", new CellRangeAddress(desde, hasta, 2, 2), "059669")),
            "#,##0"));
      }
      hoja.createFreezePane(0, 2);
    }

    private static ChuteComparativoResponse chuteDe(Dia d, String chute) {
      return d.dash().chutes().stream().filter((c) -> chute.equals(c.chute())).findFirst().orElse(null);
    }

    /** Horas para un gráfico: fracción de día, o 0 si no hay tiempo calculable. */
    private static double horaGrafico(BigDecimal horas) {
      return horas == null || sinOperarios(horas) || horas.signum() <= 0 ? 0d : horas.doubleValue() / 24d;
    }

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

    /** Las cinco métricas del día elegido: antes (atenuado), después (en negrita) y la variación. */
    private void filaComparativa(Row fila, Function<Dia, long[]> antes, Function<Dia, long[]> despues) {
      int col = 1;
      for (int i = 0; i < METRICAS.length; i++) {
        int m = i;
        porDia(fila, col++, (d) -> (double) antes.apply(d)[m], e.antes);
        porDia(fila, col++, (d) -> (double) despues.apply(d)[m], e.despues);
        porDia(fila, col++, (d) -> {
          long a = antes.apply(d)[m];
          long b = despues.apply(d)[m];
          return a > 0 ? (Object) ((double) (b - a) / a) : b > 0 ? "nuevo" : "—";
        }, e.variacion);
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

    private static long[] sumaFilas(List<long[]> filas) {
      long[] total = new long[METRICAS.length];
      for (long[] fila : filas) {
        for (int i = 0; i < total.length; i++) {
          total[i] += fila[i];
        }
      }
      return total;
    }

    // ---------------- Hoja 3: Configuración ----------------

    private void hojaConfiguracion(Sheet hoja) {
      apaisada(hoja);
      List<Long> todas = columnasDe(null);
      int ancho = Math.max(8, todas.size() + 1);
      hoja.setColumnWidth(0, 22 * 256);
      for (int i = 1; i < ancho; i++) {
        hoja.setColumnWidth(i, 18 * 256);
      }
      int f = titulo(hoja, 0, "Configuración: rutas por cluster y chute", ancho);
      f = avisoFrecuencia(hoja, f, ancho);
      f++;

      // Leyenda: cómo leer cada ruta y el semáforo.
      f = texto(hoja, f, "Cómo leer cada ruta:  [grupo] CÓDIGO · bultos · D   —   fondo de color = grupo · "
          + "D = diaria (sale todos los días) · ↻ = movida respecto del Excel · franja «Grupo» = tramo de chutes de "
          + "cada grupo. Solo se ven las rutas que salen el día elegido.", e.leyenda);
      Row semaforo = hoja.createRow(f++);
      celda(semaforo, 0, "Totales con semáforo por línea:", e.leyenda);
      celda(semaforo, 1, "más alto", e.semAlto);
      celda(semaforo, 2, "intermedio", e.semMedio);
      celda(semaforo, 3, "más bajo", e.semBajo);
      f++;

      for (ClusterParametroResponse c : clusters) {
        f = bloqueCluster(hoja, f, c, ancho);
        f++;
      }

      // Total general: suma de los clusters; operarios = el máximo de sus olas.
      f = seccion(hoja, f, "Total general · todos los clusters · operarios = máximo de sus olas", ancho);
      f = cabeceraChutes(hoja, f, "Concepto", todas);
      totales(hoja, f, todas, (d, chute) -> {
        CargaSimulacionResponse unida = null;
        for (CargaSimulacionResponse fila : d.cfg().carga()) {
          if (fila.chuteId().equals(chute) && fila.rutas() > 0) {
            unida = unida == null ? fila : sumar(unida, fila);
          }
        }
        return unida;
      }, true);
      hoja.createFreezePane(1, 2);
    }

    /** Chutes con rutas en algún día, en orden natural; con cluster, solo los suyos. */
    private List<Long> columnasDe(Long clusterId) {
      TreeSet<Long> conCarga =
          new TreeSet<>(Comparator.comparing((Long id) -> nombre(id), PlanoChutes.ORDEN).thenComparing((id) -> id));
      for (Dia d : dias) {
        for (CargaSimulacionResponse fila : d.cfg().carga()) {
          if (fila.rutas() > 0 && (clusterId == null || fila.clusterId().equals(clusterId))) {
            conCarga.add(fila.chuteId());
          }
        }
        for (RutaSimulacionResponse ruta : d.rutas()) {
          if (clusterId == null || ruta.clusterId().equals(clusterId)) {
            conCarga.add(ruta.chuteId());
          }
        }
      }
      return List.copyOf(conCarga);
    }

    /** Cuadrícula de un cluster: cabecera con grupos, franja de tramos, rutas del día y totales. */
    private int bloqueCluster(Sheet hoja, int f, ClusterParametroResponse c, int ancho) {
      Long id = c.id();
      List<Long> columnas = columnasDe(id);
      // Cabecera: estado, rutas, bultos, operarios y límite del día elegido.
      Row cab = hoja.createRow(f);
      cab.setHeightInPoints(22);
      porDia(cab, 0, (d) -> {
        ClusterParametroResponse suyo = cluster(d, id);
        Map<Long, CargaSimulacionResponse> carga = cargaCluster(d, id);
        long rutas = carga.values().stream().mapToLong(CargaSimulacionResponse::rutas).sum();
        BigDecimal bultos = carga.values().stream().map(CargaSimulacionResponse::bultos)
            .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return c.nombre() + " · " + estadoCluster(suyo == null ? c.estado() : suyo.estado()) + " · "
            + String.format(Locale.US, "%,d", rutas) + " rutas · " + formatoEntero(bultos) + " bultos"
            + (suyo == null || suyo.operariosOla() == null ? "" : " · " + suyo.operariosOla() + " operarios")
            + (suyo != null && valor(suyo.limiteHoras()).signum() > 0 ? " · límite " + horas(suyo.limiteHoras()) : "");
      }, e.seccion);
      for (int i = 1; i < ancho; i++) {
        celda(cab, i, "", e.seccion);
      }
      hoja.addMergedRegion(new CellRangeAddress(f, f, 0, ancho - 1));
      f++;

      // Grupos: su color (el de la planilla y el PDF), su tramo y sus rutas del día.
      List<GrupoClusterResponse> grupos = new ArrayList<>(c.grupos() == null ? List.of() : c.grupos());
      grupos.sort(Comparator.comparing(GrupoClusterResponse::nombre, NATURAL));
      Map<String, String> colores = new LinkedHashMap<>();
      for (int i = 0; i < grupos.size(); i++) {
        colores.put(grupos.get(i).nombre(), PALETA_GRUPOS[i % PALETA_GRUPOS.length]);
      }
      for (GrupoClusterResponse g : grupos) {
        Row fila = hoja.createRow(f);
        celda(fila, 0, "Grupo " + g.nombre(), estiloGrupo(colores.get(g.nombre())));
        porDia(fila, 1, (d) -> {
          GrupoClusterResponse suyo = grupoDe(d, id, g.nombre());
          long rutas = suyo == null ? 0 : suyo.rutas();
          String tramo = tramo(suyo == null ? g : suyo);
          return (tramo == null ? "Sin tramo" : tramo) + " · " + String.format(Locale.US, "%,d", rutas)
              + (rutas == 1 ? " ruta" : " rutas");
        }, e.texto);
        int hasta = Math.max(2, Math.min(ancho - 1, 4));
        for (int i = 2; i <= hasta; i++) {
          celda(fila, i, "", e.texto);
        }
        hoja.addMergedRegion(new CellRangeAddress(f, f, 1, hasta));
        f++;
      }
      if (columnas.isEmpty()) {
        return texto(hoja, f, "Sin rutas en este cluster.", e.subtitulo);
      }
      f = cabeceraChutes(hoja, f, "Concepto", columnas);

      // Franja: los chutes seguidos del mismo grupo se unen en una celda de su color.
      if (grupos.stream().anyMatch((g) -> g.chuteDesde() != null)) {
        Row franja = hoja.createRow(f);
        franja.setHeightInPoints(16);
        celda(franja, 0, "Grupo", e.etiqueta);
        int col = 0;
        while (col < columnas.size()) {
          GrupoClusterResponse g = grupoDelChute(columnas.get(col), grupos);
          int fin = col;
          while (fin + 1 < columnas.size() && Objects.equals(grupoDelChute(columnas.get(fin + 1), grupos), g)) {
            fin++;
          }
          CellStyle estilo = g == null ? e.franjaLibre : estiloGrupo(colores.get(g.nombre()));
          for (int i = col; i <= fin; i++) {
            celda(franja, i + 1, i == col && g != null ? "Grupo " + g.nombre() : "", estilo);
          }
          if (fin > col) {
            hoja.addMergedRegion(new CellRangeAddress(f, f, col + 1, fin + 1));
          }
          col = fin + 1;
        }
        f++;
      }

      // Rutas: en cada chute, las del día elegido (las suyas y las diarias), una por fila.
      Map<Balanceo, Map<Long, List<RutaSimulacionResponse>>> rutasPorDia = new HashMap<>();
      int alto = 1;
      for (Dia d : dias) {
        Map<Long, List<RutaSimulacionResponse>> porChute = new HashMap<>();
        for (RutaSimulacionResponse ruta : d.rutas()) {
          if (ruta.clusterId().equals(id)) {
            porChute.computeIfAbsent(ruta.chuteId(), (k) -> new ArrayList<>()).add(ruta);
          }
        }
        porChute.values().forEach((lista) -> {
          lista.sort(Comparator.comparing(RutaSimulacionResponse::codigoRuta, NATURAL)
              .thenComparing(RutaSimulacionResponse::id));
        });
        for (List<RutaSimulacionResponse> lista : porChute.values()) {
          alto = Math.max(alto, lista.size());
        }
        rutasPorDia.put(d.balanceo(), porChute);
      }
      boolean conGrupos = !grupos.isEmpty();
      int inicioRutas = f;
      for (int r = 0; r < alto; r++) {
        int n = r;
        Row fila = hoja.createRow(f++);
        fila.setHeightInPoints(18);
        celda(fila, 0, r == 0 ? "Rutas" : "", e.etiqueta);
        for (int col = 0; col < columnas.size(); col++) {
          Long chute = columnas.get(col);
          porDia(fila, col + 1, (d) -> {
            List<RutaSimulacionResponse> lista = rutasPorDia.get(d.balanceo()).getOrDefault(chute, List.of());
            return n < lista.size() ? textoRuta(lista.get(n), conGrupos) : "";
          }, e.ruta);
        }
      }
      // Fondo del grupo (por el prefijo «[grupo]») y la movida en azul: siguen al día elegido.
      CellRangeAddress[] zona = {new CellRangeAddress(inicioRutas, f - 1, 1, columnas.size())};
      String celda = "B" + (inicioRutas + 1);
      regla(hoja, zona, "RIGHT(" + celda + ",1)=\"↻\"", null, "3730A3", true);
      for (Map.Entry<String, String> g : colores.entrySet()) {
        String prefijo = "[" + g.getKey() + "]";
        regla(hoja, zona, "LEFT(" + celda + "," + prefijo.length() + ")=\"" + prefijo.replace("\"", "\"\"") + "\"",
            mezcla(g.getValue(), 0.18), null, false);
      }

      return totales(hoja, f, columnas, (d, chute) -> cargaCluster(d, id).get(chute), false);
    }

    /** «[1] 9011 · 45 · D ↻»: grupo (si el cluster tiene), código, bultos, diaria y movida. */
    private static String textoRuta(RutaSimulacionResponse ruta, boolean conGrupos) {
      boolean diaria = Balanceo.diaria(ruta.frecuencia());
      boolean movida = !Objects.equals(ruta.chuteId(), ruta.chuteOriginalId())
          || !Objects.equals(ruta.clusterId(), ruta.clusterOriginalId());
      return (conGrupos && ruta.grupo() != null && !ruta.grupo().isBlank() ? "[" + ruta.grupo() + "] " : "")
          + ruta.codigoRuta() + " · " + formatoEntero(ruta.totalBultos())
          + (diaria ? " · D" : "") + (movida ? " ↻" : "");
    }

    private static GrupoClusterResponse grupoDe(Dia d, Long clusterId, String nombre) {
      ClusterParametroResponse suyo = cluster(d, clusterId);
      if (suyo == null || suyo.grupos() == null) {
        return null;
      }
      return suyo.grupos().stream().filter((g) -> nombre.equals(g.nombre())).findFirst().orElse(null);
    }

    /** «CHUTE 15 → CHUTE 17», «CHUTE 15» o null si las diarias aún no le dieron tramo. */
    private static String tramo(GrupoClusterResponse g) {
      if (g.chuteDesde() == null) {
        return null;
      }
      return !Objects.equals(g.chuteHastaId(), g.chuteDesdeId()) && g.chuteHasta() != null
          ? etiquetaChute(g.chuteDesde()) + " → " + etiquetaChute(g.chuteHasta())
          : etiquetaChute(g.chuteDesde());
    }

    /** Grupo cuyo tramo incluye el chute (los tramos son contiguos en orden natural), o null. */
    private GrupoClusterResponse grupoDelChute(Long chute, List<GrupoClusterResponse> grupos) {
      String nombre = nombre(chute);
      for (GrupoClusterResponse g : grupos) {
        if (g.chuteDesde() == null) {
          continue;
        }
        if (chute.equals(g.chuteDesdeId()) || chute.equals(g.chuteHastaId())) {
          return g;
        }
        String hasta = g.chuteHasta() == null ? g.chuteDesde() : g.chuteHasta();
        if (PlanoChutes.ORDEN.compare(nombre, etiquetaChute(g.chuteDesde())) > 0
            && PlanoChutes.ORDEN.compare(nombre, etiquetaChute(hasta)) < 0) {
          return g;
        }
      }
      return null;
    }

    private int cabeceraChutes(Sheet hoja, int f, String primera, List<Long> columnas) {
      Row fila = hoja.createRow(f);
      fila.setHeightInPoints(20);
      celda(fila, 0, primera, e.cabecera);
      for (int c = 0; c < columnas.size(); c++) {
        celda(fila, c + 1, nombre(columnas.get(c)), e.cabecera);
      }
      return f + 1;
    }

    /**
     * Filas de totales por chute del día elegido, con su semáforo. El tono de cada celda (el
     * puesto en su fila, como la pantalla) se calcula por día y va en una fila oculta debajo; el
     * formato condicional lo lee, así el color sigue al selector.
     */
    private int totales(
        Sheet hoja, int f, List<Long> columnas,
        java.util.function.BiFunction<Dia, Long, CargaSimulacionResponse> carga, boolean general) {
      for (int concepto = 0; concepto < CONCEPTOS.length; concepto++) {
        int k = concepto;
        Map<Balanceo, long[]> valores = new HashMap<>();
        Map<Balanceo, double[]> niveles = new HashMap<>();
        for (Dia d : dias) {
          long[] fila = new long[columnas.size()];
          for (int c = 0; c < columnas.size(); c++) {
            CargaSimulacionResponse dato = carga.apply(d, columnas.get(c));
            fila[c] = dato == null ? 0 : valorConcepto(dato, k);
          }
          valores.put(d.balanceo(), fila);
          niveles.put(d.balanceo(), niveles(fila));
        }
        Row r = hoja.createRow(f);
        Row tonos = hoja.createRow(f + 1);
        tonos.setZeroHeight(true);
        String etiqueta = general && concepto == 0 ? "Máx. operarios por ola" : CONCEPTOS[concepto];
        celda(r, 0, etiqueta, e.etiquetaTotal);
        for (int c = 0; c < columnas.size(); c++) {
          int col = c;
          porDia(r, c + 1, (d) -> {
            long v = valores.get(d.balanceo())[col];
            return v > 0 ? (Object) (double) v : "";
          }, e.totalNumero);
          porDia(tonos, c + 1, (d) -> (double) tono(niveles.get(d.balanceo())[col]), e.vacia);
        }
        if (!columnas.isEmpty()) {
          CellRangeAddress[] zona = {new CellRangeAddress(f, f, 1, columnas.size())};
          String abajo = "B" + (f + 2);
          regla(hoja, zona, abajo + "=3", "FEE2E2", "991B1B", true);
          regla(hoja, zona, abajo + "=2", "D1FAE5", "065F46", true);
          regla(hoja, zona, abajo + "=1", "FEF3C7", "92400E", true);
        }
        f += 2;
      }
      return f;
    }

    /** 0 = sin dato, 1 = lo más bajo (amarillo), 2 = intermedio (verde), 3 = lo más alto (rojo). */
    private static int tono(double nivel) {
      if (nivel < 0) {
        return 0;
      }
      return nivel < 1.0 / 3 ? 1 : nivel < 2.0 / 3 ? 2 : 3;
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

    // ---------------- Formato condicional ----------------

    /** Regla de formato condicional: relleno y color de letra cuando la fórmula es verdadera. */
    private static void regla(
        Sheet hoja, CellRangeAddress[] zona, String formula, String fondo, String letra, boolean negrita) {
      SheetConditionalFormatting formatos = hoja.getSheetConditionalFormatting();
      ConditionalFormattingRule regla = formatos.createConditionalFormattingRule(formula);
      if (fondo != null) {
        PatternFormatting relleno = regla.createPatternFormatting();
        relleno.setFillBackgroundColor(Estilos.color(fondo));
        relleno.setFillPattern(PatternFormatting.SOLID_FOREGROUND);
      }
      if (letra != null) {
        FontFormatting fuente = regla.createFontFormatting();
        fuente.setFontColor(Estilos.color(letra));
        fuente.setFontStyle(false, negrita);
      }
      formatos.addConditionalFormatting(zona, regla);
    }

    /** Color mezclado con blanco (p = proporción del color): el fondo suave del grupo. */
    private static String mezcla(String hex, double p) {
      int v = Integer.parseInt(hex, 16);
      int r = (int) Math.round(((v >> 16) & 0xFF) * p + 255 * (1 - p));
      int g = (int) Math.round(((v >> 8) & 0xFF) * p + 255 * (1 - p));
      int b = (int) Math.round((v & 0xFF) * p + 255 * (1 - p));
      return String.format("%02X%02X%02X", r, g, b);
    }

    /** Celda de un grupo: su color pleno y el texto en blanco (franja y nombre del grupo). */
    private CellStyle estiloGrupo(String hex) {
      return estilosGrupo.computeIfAbsent(hex, (h) -> {
        XSSFCellStyle estilo = Estilos.estilo(libro, h, Estilos.fuente(libro, 9, true, "FFFFFF"),
            HorizontalAlignment.CENTER, true);
        return estilo;
      });
    }

    // ---------------- Gráficos nativos de Excel ----------------

    /** Una serie del gráfico: su nombre, el rango de valores y su color (hex). */
    private record Serie(String nombre, CellRangeAddress valores, String color) {}

    /** Gráfico pendiente: se dibuja al final, cuando sus celdas ya están calculadas. */
    private record Grafico(
        Sheet hoja, int fila, int columnas, int filas, String titulo, CellRangeAddress categorias,
        List<Serie> series, String formato) {}

    /** Columnas agrupadas (como los del PDF): categorías y valores apuntan a celdas de la hoja. */
    private void dibujar(Grafico g) {
      XSSFDrawing dibujo = ((XSSFSheet) g.hoja()).createDrawingPatriarch();
      XSSFClientAnchor ancla = dibujo.createAnchor(0, 0, 0, 0, 0, g.fila(), g.columnas(), g.fila() + g.filas());
      XSSFChart chart = dibujo.createChart(ancla);
      chart.setTitleText(g.titulo());
      chart.setTitleOverlay(false);
      chart.getOrAddLegend().setPosition(LegendPosition.TOP);
      XDDFCategoryAxis x = chart.createCategoryAxis(AxisPosition.BOTTOM);
      XDDFValueAxis y = chart.createValueAxis(AxisPosition.LEFT);
      y.setCrosses(AxisCrosses.AUTO_ZERO);
      y.setCrossBetween(AxisCrossBetween.BETWEEN);
      y.getOrAddMajorGridProperties()
          .setLineProperties(new XDDFLineProperties(new XDDFSolidFillProperties(XDDFColor.from(rgb("E2E8F0")))));
      XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange((XSSFSheet) g.hoja(), g.categorias());
      XDDFBarChartData datosGrafico = (XDDFBarChartData) chart.createData(ChartTypes.BAR, x, y);
      datosGrafico.setBarDirection(BarDirection.COL);
      datosGrafico.setBarGrouping(BarGrouping.CLUSTERED);
      datosGrafico.setVaryColors(false);
      datosGrafico.setGapWidth(80);
      for (Serie serie : g.series()) {
        XDDFNumericalDataSource<Double> valores =
            XDDFDataSourcesFactory.fromNumericCellRange((XSSFSheet) g.hoja(), serie.valores());
        XDDFBarChartData.Series s = (XDDFBarChartData.Series) datosGrafico.addSeries(cats, valores);
        s.setTitle(serie.nombre(), null);
        s.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(rgb(serie.color()))));
      }
      chart.plot(datosGrafico);
      // Cifra sobre cada barra y formato del eje (horas como [h]:mm o miles con coma).
      CTBarChart barras = chart.getCTChart().getPlotArea().getBarChartArray(0);
      CTDLbls etiquetas = barras.addNewDLbls();
      CTNumFmt formatoEtiqueta = etiquetas.addNewNumFmt();
      formatoEtiqueta.setFormatCode(g.formato());
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
      formatoEje.setFormatCode(g.formato());
      formatoEje.setSourceLinked(false);
      // Solo las celdas visibles, como en Excel por defecto.
      if (!chart.getCTChart().isSetPlotVisOnly()) {
        chart.getCTChart().addNewPlotVisOnly();
      }
      chart.getCTChart().getPlotVisOnly().setVal(true);
    }

    private static byte[] rgb(String hex) {
      int v = Integer.parseInt(hex, 16);
      return new byte[] {(byte) (v >> 16), (byte) (v >> 8), (byte) v};
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

    /** «CHUTE 5» para las tablas, aunque el catálogo lo guarde como «5». */
    private static String etiquetaChute(String nombre) {
      return nombre != null && nombre.trim().toUpperCase(Locale.ROOT).startsWith("CH") ? nombre : "CHUTE " + nombre;
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

  /** Estilos del libro, creados una vez (Excel admite pocos miles). */
  private static final class Estilos {
    final CellStyle titulo;
    final CellStyle subtitulo;
    final CellStyle alerta;
    final CellStyle aviso;
    final CellStyle leyenda;
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
    final CellStyle etiquetaSelector;
    final CellStyle selector;
    final CellStyle pista;
    final CellStyle kpiEtiqueta;
    final CellStyle kpi;
    final CellStyle kpiDetalle;
    final CellStyle ruta;
    final CellStyle vacia;
    final CellStyle franjaLibre;
    final CellStyle subeCentro;
    final CellStyle bajaCentro;
    final CellStyle semBajo;
    final CellStyle semMedio;
    final CellStyle semAlto;
    /** Horas como hora de Excel: «2:31» (2 h 31 min); suma bien y se grafica. */
    final CellStyle hora;
    final CellStyle horaFuerte;
    /** Comparativo: «antes» atenuado, «después» en negrita y la variación con signo. */
    final CellStyle antes;
    final CellStyle despues;
    final CellStyle variacion;

    Estilos(XSSFWorkbook libro) {
      short miles = libro.createDataFormat().getFormat("#,##0");
      short formatoHora = libro.createDataFormat().getFormat("[h]:mm");
      hora = estilo(libro, null, fuente(libro, 10, false, "1E293B"), HorizontalAlignment.RIGHT, true);
      hora.setDataFormat(formatoHora);
      horaFuerte = estilo(libro, null, fuente(libro, 10, true, "0F172A"), HorizontalAlignment.RIGHT, true);
      horaFuerte.setDataFormat(formatoHora);
      antes = estilo(libro, null, fuente(libro, 10, false, "64748B"), HorizontalAlignment.RIGHT, true);
      antes.setDataFormat(miles);
      despues = estilo(libro, null, fuente(libro, 10, true, "0F172A"), HorizontalAlignment.RIGHT, true);
      despues.setDataFormat(miles);
      variacion = estilo(libro, "F8FAFC", fuente(libro, 9, true, "1D63ED"), HorizontalAlignment.RIGHT, true);
      variacion.setDataFormat(libro.createDataFormat().getFormat("+0%;[Color10]-0%;0%"));
      titulo = estilo(libro, null, fuente(libro, 16, true, "0F1E3D"), HorizontalAlignment.LEFT, false);
      subtitulo = estilo(libro, null, fuente(libro, 10, false, "64748B"), HorizontalAlignment.LEFT, false);
      alerta = estilo(libro, "FEF3C7", fuente(libro, 10, true, "92400E"), HorizontalAlignment.LEFT, false);
      aviso = estilo(libro, "EEF2FF", fuente(libro, 10, true, "3730A3"), HorizontalAlignment.LEFT, false);
      leyenda = estilo(libro, null, fuente(libro, 9, false, "475569"), HorizontalAlignment.LEFT, false);
      seccion = estilo(libro, "E2E8F0", fuente(libro, 11, true, "0F1E3D"), HorizontalAlignment.LEFT, false);
      cabecera = estilo(libro, "0F1E3D", fuente(libro, 10, true, "FFFFFF"), HorizontalAlignment.CENTER, true);
      cabecera.setWrapText(true);
      texto = estilo(libro, null, fuente(libro, 10, false, "1E293B"), HorizontalAlignment.LEFT, false);
      fuerte = estilo(libro, null, fuente(libro, 10, true, "0F172A"), HorizontalAlignment.LEFT, true);
      centro = estilo(libro, null, fuente(libro, 10, false, "1E293B"), HorizontalAlignment.CENTER, true);
      derecha = estilo(libro, null, fuente(libro, 10, false, "1E293B"), HorizontalAlignment.RIGHT, true);
      numero = estilo(libro, null, fuente(libro, 10, false, "1E293B"), HorizontalAlignment.RIGHT, true);
      numero.setDataFormat(miles);
      totalNumero = estilo(libro, "F8FAFC", fuente(libro, 10, true, "0F172A"), HorizontalAlignment.RIGHT, true);
      totalNumero.setDataFormat(miles);
      etiqueta = estilo(libro, "F8FAFC", fuente(libro, 9, true, "475569"), HorizontalAlignment.LEFT, true);
      etiquetaTotal = estilo(libro, "F1F5F9", fuente(libro, 9, true, "334155"), HorizontalAlignment.LEFT, true);
      etiquetaSelector = estilo(libro, "0F1E3D", fuente(libro, 11, true, "FFFFFF"), HorizontalAlignment.RIGHT, true);
      selector = estilo(libro, "FEF9C3", fuente(libro, 12, true, "0F1E3D"), HorizontalAlignment.CENTER, true);
      selector.setBorderBottom(BorderStyle.MEDIUM);
      selector.setBorderTop(BorderStyle.MEDIUM);
      selector.setBorderLeft(BorderStyle.MEDIUM);
      selector.setBorderRight(BorderStyle.MEDIUM);
      pista = estilo(libro, null, fuente(libro, 9, false, "64748B"), HorizontalAlignment.LEFT, false);
      kpiEtiqueta = estilo(libro, "F8FAFC", fuente(libro, 9, true, "64748B"), HorizontalAlignment.LEFT, false);
      kpi = estilo(libro, "F8FAFC", fuente(libro, 16, true, "0F1E3D"), HorizontalAlignment.LEFT, false);
      kpiDetalle = estilo(libro, "F8FAFC", fuente(libro, 9, false, "64748B"), HorizontalAlignment.LEFT, false);
      ruta = estilo(libro, null, fuente(libro, 9, true, "0F172A"), HorizontalAlignment.LEFT, true);
      vacia = estilo(libro, null, fuente(libro, 9, false, "94A3B8"), HorizontalAlignment.CENTER, false);
      franjaLibre = estilo(libro, "F1F5F9", fuente(libro, 9, false, "94A3B8"), HorizontalAlignment.CENTER, true);
      subeCentro = estilo(libro, null, fuente(libro, 10, true, "059669"), HorizontalAlignment.CENTER, true);
      bajaCentro = estilo(libro, null, fuente(libro, 10, true, "DC2626"), HorizontalAlignment.CENTER, true);
      semBajo = estilo(libro, "FEF3C7", fuente(libro, 10, true, "92400E"), HorizontalAlignment.CENTER, true);
      semMedio = estilo(libro, "D1FAE5", fuente(libro, 10, true, "065F46"), HorizontalAlignment.CENTER, true);
      semAlto = estilo(libro, "FEE2E2", fuente(libro, 10, true, "991B1B"), HorizontalAlignment.CENTER, true);
    }

    static XSSFFont fuente(XSSFWorkbook libro, int puntos, boolean negrita, String color) {
      XSSFFont fuente = libro.createFont();
      fuente.setFontName("Calibri");
      fuente.setFontHeightInPoints((short) puntos);
      fuente.setBold(negrita);
      fuente.setColor(color(color));
      return fuente;
    }

    static XSSFCellStyle estilo(
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

    static XSSFColor color(String hex) {
      int rgb = Integer.parseInt(hex, 16);
      return new XSSFColor(
          new byte[] {(byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb}, null);
    }
  }
}
