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
import java.util.Arrays;
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
import oracle.simulacion.response.OlaSimulacionResponse;
import oracle.simulacion.response.RutaSimulacionResponse;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.ConditionalFormattingThreshold;
import org.apache.poi.ss.usermodel.DataBarFormatting;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.FontFormatting;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PatternFormatting;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
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
import org.apache.poi.xssf.usermodel.XSSFConditionalFormattingRule;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFSheetConditionalFormatting;
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
    return servicio.todasLasRutas(archivoId).datos();
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
    private EstilosReporte e;
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
        e = new EstilosReporte(libro);
        Sheet resumen = libro.createSheet("Resumen");
        Sheet tiempos = libro.createSheet("Tiempos y comparativos");
        Sheet configuracion = libro.createSheet("Configuración");
        Sheet total = libro.createSheet("Resumen total");
        datos = libro.createSheet("Datos");
        prepararDatos();
        hojaResumen(resumen);
        hojaTiempos(tiempos);
        hojaConfiguracion(configuracion);
        hojaResumenTotal(total);
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

    // ---------------- Hoja 3: Configuración (cada cluster con sus días, uno debajo del otro) ----------------

    private void hojaConfiguracion(Sheet hoja) {
      apaisada(hoja);
      int ancho = 1;
      for (ClusterParametroResponse c : clusters) {
        for (Dia d : disponibles) {
          ancho = Math.max(ancho, 1 + columnasDia(d, c.id()).size());
        }
      }
      ancho = Math.max(8, ancho);
      hoja.setColumnWidth(0, 24 * 256);
      for (int i = 1; i < ancho; i++) {
        hoja.setColumnWidth(i, 17 * 256);
      }
      int f = titulo(hoja, 0, "Configuración: rutas por cluster y chute", ancho);
      Row leyenda = hoja.createRow(f++);
      celda(leyenda, 0, "[grupo] código · bultos · D = diaria · ↻ = movida", e.leyenda);
      celda(leyenda, ancho - 3, "Alto", e.semAlto);
      celda(leyenda, ancho - 2, "Medio", e.semMedio);
      celda(leyenda, ancho - 1, "Bajo", e.semBajo);
      f++;
      // Solo los días con rutas de cada cluster; un cluster sin rutas en ningún día no se lista.
      for (ClusterParametroResponse c : clusters) {
        List<Dia> suyos = disponibles.stream().filter((d) -> !columnasDia(d, c.id()).isEmpty()).toList();
        if (suyos.isEmpty()) {
          continue;
        }
        f = seccion(hoja, f, c.nombre(), ancho);
        Map<String, String> colores = coloresGrupos(c);
        for (Dia d : suyos) {
          f = diaCluster(hoja, f, d, c, colores);
        }
        f++;
      }
      hoja.createFreezePane(1, 1);
    }

    /** Colores de los grupos del cluster (los de la planilla y el PDF), en orden natural. */
    private static Map<String, String> coloresGrupos(ClusterParametroResponse c) {
      List<String> nombres = new ArrayList<>();
      if (c.grupos() != null) {
        c.grupos().forEach((g) -> nombres.add(g.nombre()));
      }
      nombres.sort(NATURAL);
      Map<String, String> colores = new LinkedHashMap<>();
      for (int i = 0; i < nombres.size(); i++) {
        colores.put(nombres.get(i), PALETA_GRUPOS[i % PALETA_GRUPOS.length]);
      }
      return colores;
    }

    /** Rutas del cluster en ese día (las suyas y las diarias), por chute y ordenadas por código. */
    private static Map<Long, List<RutaSimulacionResponse>> rutasCluster(Dia d, Long clusterId) {
      Map<Long, List<RutaSimulacionResponse>> porChute = new HashMap<>();
      for (RutaSimulacionResponse ruta : d.rutas()) {
        if (ruta.clusterId().equals(clusterId)) {
          porChute.computeIfAbsent(ruta.chuteId(), (k) -> new ArrayList<>()).add(ruta);
        }
      }
      porChute.values().forEach((lista) -> lista.sort(
          Comparator.comparing(RutaSimulacionResponse::codigoRuta, NATURAL).thenComparing(RutaSimulacionResponse::id)));
      return porChute;
    }

    /** Chutes del cluster en ese día (con carga o rutas), en orden natural. */
    private List<Long> columnasDia(Dia d, Long clusterId) {
      TreeSet<Long> chutes = ordenChutes();
      chutes.addAll(cargaCluster(d, clusterId).keySet());
      chutes.addAll(rutasCluster(d, clusterId).keySet());
      return List.copyOf(chutes);
    }

    /** Chutes con rutas en algún día (de todos los clusters), en orden natural. */
    private List<Long> todosLosChutes() {
      TreeSet<Long> chutes = ordenChutes();
      for (Dia d : disponibles) {
        for (CargaSimulacionResponse fila : d.cfg().carga()) {
          if (fila.rutas() > 0) {
            chutes.add(fila.chuteId());
          }
        }
      }
      return List.copyOf(chutes);
    }

    private TreeSet<Long> ordenChutes() {
      return new TreeSet<>(Comparator.comparing((Long id) -> nombre(id), PlanoChutes.ORDEN).thenComparing((id) -> id));
    }

    /**
     * Un día (con rutas) de un cluster: su banda (día, rutas, bultos y cierre), los chutes con sus
     * rutas y los totales por chute con su semáforo.
     */
    private int diaCluster(Sheet hoja, int f, Dia d, ClusterParametroResponse c, Map<String, String> colores) {
      Long id = c.id();
      Map<Long, CargaSimulacionResponse> carga = cargaCluster(d, id);
      Map<Long, List<RutaSimulacionResponse>> porChute = rutasCluster(d, id);
      List<Long> columnas = columnasDia(d, id);
      Row banda = hoja.createRow(f++);
      banda.setHeightInPoints(20);
      celda(banda, 0, d.balanceo().etiqueta(), d.balanceo() == Balanceo.MJS ? e.diaMjs : e.diaLxv);
      long rutas = carga.values().stream().mapToLong(CargaSimulacionResponse::rutas).sum();
      BigDecimal bultos = carga.values().stream().map(CargaSimulacionResponse::bultos)
          .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
      ClusterComparativoResponse comp = comparativo(d, id);
      String cierre = comp != null && conLimite(comp)
          ? " · cierre " + horas(comp.limiteHoras()) + " → " + horas(comp.tiempoBalanceado()) + (comp.excede() ? " ✗" : " ✓")
          : "";
      celda(banda, 1, String.format(Locale.US, "%,d", rutas) + " rutas · " + formatoEntero(bultos) + " bultos" + cierre,
          e.subtitulo);

      f = cabeceraChutes(hoja, f, "Concepto", columnas);
      int alto = 1;
      for (Long chute : columnas) {
        alto = Math.max(alto, porChute.getOrDefault(chute, List.of()).size());
      }
      boolean conGrupos = !colores.isEmpty();
      for (int r = 0; r < alto; r++) {
        Row fila = hoja.createRow(f++);
        fila.setHeightInPoints(18);
        celda(fila, 0, r == 0 ? "Rutas" : "", e.etiqueta);
        for (int col = 0; col < columnas.size(); col++) {
          List<RutaSimulacionResponse> lista = porChute.getOrDefault(columnas.get(col), List.of());
          if (r < lista.size()) {
            RutaSimulacionResponse ruta = lista.get(r);
            String color = conGrupos && ruta.grupo() != null ? colores.get(ruta.grupo()) : null;
            celda(fila, col + 1, textoRuta(ruta, conGrupos), color == null ? e.ruta : estiloRuta(color));
          } else {
            celda(fila, col + 1, "", e.ruta);
          }
        }
      }
      for (int k = 0; k < CONCEPTOS.length; k++) {
        long[] valores = new long[columnas.size()];
        for (int col = 0; col < columnas.size(); col++) {
          CargaSimulacionResponse dato = carga.get(columnas.get(col));
          valores[col] = dato == null ? 0 : valorConcepto(dato, k);
        }
        f = filaTotal(hoja, f, CONCEPTOS[k], valores, null);
      }
      return f;
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

    private int cabeceraChutes(Sheet hoja, int f, String primera, List<Long> columnas) {
      Row fila = hoja.createRow(f);
      fila.setHeightInPoints(20);
      celda(fila, 0, primera, e.cabecera);
      for (int c = 0; c < columnas.size(); c++) {
        celda(fila, c + 1, nombre(columnas.get(c)), e.cabecera);
      }
      return f + 1;
    }

    /** Fila de totales por chute con su semáforo (el puesto de cada valor en la fila) y, si va, su total. */
    private int filaTotal(Sheet hoja, int f, String etiqueta, long[] valores, Long total) {
      Row fila = hoja.createRow(f);
      celda(fila, 0, etiqueta, e.etiquetaTotal);
      double[] niveles = niveles(valores);
      for (int c = 0; c < valores.length; c++) {
        CellStyle estilo = switch (tono(niveles[c])) {
          case 1 -> e.semBajoNumero;
          case 2 -> e.semMedioNumero;
          case 3 -> e.semAltoNumero;
          default -> e.totalNumero;
        };
        celda(fila, c + 1, valores[c] > 0 ? (Object) valores[c] : "", estilo);
      }
      if (total != null) {
        celda(fila, valores.length + 1, total, e.despues);
      }
      return f + 1;
    }

    // ---------------- Hoja 4: Resumen total (los días sumados) ----------------

    /**
     * Totales de los días sumados, por chute y por cluster. Cada día suma sus rutas y las diarias,
     * así que una diaria cuenta en cada día que sale (es el volumen que se trabaja).
     */
    private void hojaResumenTotal(Sheet hoja) {
      apaisada(hoja);
      List<Long> chutes = todosLosChutes();
      int ancho = Math.max(9, chutes.size() + 2);
      hoja.setColumnWidth(0, 24 * 256);
      for (int i = 1; i < ancho; i++) {
        hoja.setColumnWidth(i, 13 * 256);
      }
      String dias = String.join(" + ", disponibles.stream().map((d) -> d.balanceo().etiqueta()).toList());
      int f = titulo(hoja, 0, "Resumen total · " + dias, ancho);
      f++;

      // Por chute: la carga de todos los clusters, sumando los días (sin decimales sueltos).
      BigDecimal[][] porChute = new BigDecimal[4][chutes.size()];
      for (int k = 0; k < 4; k++) {
        for (int c = 0; c < chutes.size(); c++) {
          porChute[k][c] = BigDecimal.ZERO;
        }
      }
      for (Dia d : disponibles) {
        for (CargaSimulacionResponse fila : d.cfg().carga()) {
          int c = chutes.indexOf(fila.chuteId());
          if (fila.rutas() > 0 && c >= 0) {
            for (int k = 0; k < 4; k++) {
              porChute[k][c] = porChute[k][c].add(crudo(fila, k + 1));
            }
          }
        }
      }
      long[] totales = new long[4];
      long[][] valores = new long[4][chutes.size()];
      for (int k = 0; k < 4; k++) {
        BigDecimal suma = BigDecimal.ZERO;
        for (int c = 0; c < chutes.size(); c++) {
          valores[k][c] = entero(porChute[k][c]);
          suma = suma.add(porChute[k][c]);
        }
        totales[k] = entero(suma);
      }

      // Indicadores: los cuatro totales.
      Row etiquetas = hoja.createRow(f++);
      Row cifras = hoja.createRow(f++);
      cifras.setHeightInPoints(26);
      for (int k = 0; k < 4; k++) {
        celda(etiquetas, k * 2, CONCEPTOS[k + 1], e.kpiEtiqueta);
        celda(etiquetas, k * 2 + 1, "", e.kpiEtiqueta);
        celda(cifras, k * 2, totales[k], e.kpiNumero);
        celda(cifras, k * 2 + 1, "", e.kpiNumero);
        hoja.addMergedRegion(new CellRangeAddress(etiquetas.getRowNum(), etiquetas.getRowNum(), k * 2, k * 2 + 1));
        hoja.addMergedRegion(new CellRangeAddress(cifras.getRowNum(), cifras.getRowNum(), k * 2, k * 2 + 1));
      }
      f++;

      f = seccion(hoja, f, "Totales por chute", ancho);
      Row cab = hoja.createRow(f++);
      cab.setHeightInPoints(20);
      celda(cab, 0, "Concepto", e.cabecera);
      for (int c = 0; c < chutes.size(); c++) {
        celda(cab, c + 1, nombre(chutes.get(c)), e.cabecera);
      }
      celda(cab, chutes.size() + 1, "Total", e.cabecera);
      for (int k = 0; k < 4; k++) {
        f = filaTotal(hoja, f, CONCEPTOS[k + 1], valores[k], totales[k]);
      }
      f++;

      // Por cluster: sus cifras y cuánto pesan en el total (con barra de datos).
      f = seccion(hoja, f, "Totales por cluster", ancho);
      f = cabecera(hoja, f, 0, new String[] {
        "Cluster", "Bultos", "%", "Líneas", "%", "Unidades", "%", "Locales", "%"
      });
      int inicio = f;
      for (ClusterParametroResponse c : clusters) {
        long[] suyos = new long[4];
        for (int k = 0; k < 4; k++) {
          BigDecimal suma = BigDecimal.ZERO;
          for (Dia d : disponibles) {
            for (CargaSimulacionResponse dato : cargaCluster(d, c.id()).values()) {
              suma = suma.add(crudo(dato, k + 1));
            }
          }
          suyos[k] = entero(suma);
        }
        // Un cluster sin carga en ningún día no se lista.
        if (Arrays.stream(suyos).allMatch((v) -> v == 0)) {
          continue;
        }
        Row fila = hoja.createRow(f++);
        celda(fila, 0, c.nombre(), e.fuerte);
        for (int k = 0; k < 4; k++) {
          celda(fila, 1 + k * 2, suyos[k], e.numero);
          celda(fila, 2 + k * 2, totales[k] > 0 ? (double) suyos[k] / totales[k] : 0, e.porcentaje);
        }
      }
      Row total = hoja.createRow(f++);
      celda(total, 0, "Total", e.etiquetaTotal);
      for (int k = 0; k < 4; k++) {
        celda(total, 1 + k * 2, totales[k], e.totalNumero);
        celda(total, 2 + k * 2, 1, e.porcentajeTotal);
      }
      if (f - 1 > inicio) {
        for (int k = 0; k < 4; k++) {
          barra(hoja, new CellRangeAddress(inicio, f - 2, 2 + k * 2, 2 + k * 2));
        }
      }
      hoja.createFreezePane(1, 0);
    }

    /** Valor sin redondear de un concepto (1 = bultos, 2 = líneas, 3 = unidades, 4 = locales). */
    private static BigDecimal crudo(CargaSimulacionResponse fila, int concepto) {
      return switch (concepto) {
        case 1 -> valor(fila.bultos());
        case 2 -> valor(fila.lineas());
        case 3 -> valor(fila.unidades());
        default -> BigDecimal.valueOf(fila.cod());
      };
    }

    /** Barra de datos (0 % a 100 %) en el color del acento: el peso de cada cluster. */
    private static void barra(Sheet hoja, CellRangeAddress zona) {
      XSSFSheetConditionalFormatting formatos = (XSSFSheetConditionalFormatting) hoja.getSheetConditionalFormatting();
      XSSFConditionalFormattingRule regla = formatos.createConditionalFormattingRule(EstilosReporte.color("93C5FD"));
      DataBarFormatting barra = regla.getDataBarFormatting();
      barra.getMinThreshold().setRangeType(ConditionalFormattingThreshold.RangeType.NUMBER);
      barra.getMinThreshold().setValue(0d);
      barra.getMaxThreshold().setRangeType(ConditionalFormattingThreshold.RangeType.NUMBER);
      barra.getMaxThreshold().setValue(1d);
      formatos.addConditionalFormatting(new CellRangeAddress[] {zona}, regla);
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
        relleno.setFillBackgroundColor(EstilosReporte.color(fondo));
        relleno.setFillPattern(PatternFormatting.SOLID_FOREGROUND);
      }
      if (letra != null) {
        FontFormatting fuente = regla.createFontFormatting();
        fuente.setFontColor(EstilosReporte.color(letra));
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

    /** Ruta de un grupo: fondo suave de su color (el de la planilla y el PDF) y el texto oscuro. */
    private CellStyle estiloRuta(String hex) {
      return estilosGrupo.computeIfAbsent(hex, (h) -> {
        XSSFCellStyle estilo = EstilosReporte.estilo(libro, mezcla(h, 0.18), EstilosReporte.fuente(libro, 9, true, "0F172A"),
            HorizontalAlignment.LEFT, true);
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
}
