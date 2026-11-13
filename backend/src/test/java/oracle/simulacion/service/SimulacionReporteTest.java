package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import oracle.simulacion.model.Balanceo;
import oracle.simulacion.response.ArchivoSimulacionResponse;
import oracle.simulacion.response.CargaSimulacionResponse;
import oracle.simulacion.response.ChuteComparativoResponse;
import oracle.simulacion.response.ChuteParametroResponse;
import oracle.simulacion.response.ClusterComparativoResponse;
import oracle.simulacion.response.ClusterParametroResponse;
import oracle.simulacion.response.ConfiguracionSimulacionResponse;
import oracle.simulacion.response.DashboardSimulacionResponse;
import oracle.simulacion.response.DotacionChuteResponse;
import oracle.simulacion.response.GrupoClusterResponse;
import oracle.simulacion.response.OlaSimulacionResponse;
import oracle.simulacion.response.RutaSimulacionResponse;
import oracle.simulacion.response.SimulacionKpisResponse;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFDataValidation;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SimulacionReporteTest {

  private static final BigDecimal CIEN = new BigDecimal("100");

  @Test
  void hojasComoElPdfYDatosOculta() throws Exception {
    try (Workbook libro = abrir()) {
      List<String> hojas = new ArrayList<>();
      for (int i = 0; i < libro.getNumberOfSheets(); i++) {
        hojas.add(libro.getSheetName(i));
      }
      assertEquals(List.of("Resumen", "Tiempos y comparativos", "Configuración", "Resumen total", "Datos"), hojas);
      assertFalse(libro.isSheetHidden(0) || libro.isSheetHidden(1) || libro.isSheetHidden(2) || libro.isSheetHidden(3));
      assertTrue(libro.isSheetHidden(4), "los datos de los dos días van ocultos");
    }
  }

  @Test
  void selectorConLosDiasQueTienenRutas() throws Exception {
    try (XSSFWorkbook libro = (XSSFWorkbook) abrir()) {
      XSSFSheet resumen = libro.getSheet("Resumen");
      assertEquals("L-X-V", resumen.getRow(1).getCell(2).getStringCellValue());
      List<XSSFDataValidation> validaciones = resumen.getDataValidations();
      assertEquals(1, validaciones.size());
      assertArrayEquals(
          new String[] {"L-X-V", "M-J-S"},
          validaciones.get(0).getValidationConstraint().getExplicitListValues());
    }
  }

  @Test
  void unDiaSinRutasNoSeOfrece() throws Exception {
    ConfiguracionSimulacionResponse vacia = configuracion(List.of());
    byte[] bytes =
        new SimulacionReporte.Libro(
                List.of(
                    new SimulacionReporte.Dia(Balanceo.LXV, configuracion(), dashboard(1), rutas()),
                    new SimulacionReporte.Dia(Balanceo.MJS, vacia, dashboard(0), List.of())))
            .escribir();
    try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      assertArrayEquals(
          new String[] {"L-X-V"},
          libro.getSheet("Resumen").getDataValidations().get(0).getValidationConstraint().getExplicitListValues());
    }
  }

  @Test
  void losGraficosSonNativosYLeenSuTabla() throws Exception {
    try (XSSFWorkbook libro = (XSSFWorkbook) abrir()) {
      // Cierre, tiempo (el Excel trae dotación base), bultos por cluster y bultos por chute.
      assertEquals(0, graficos(libro, "Resumen"));
      assertEquals(4, graficos(libro, "Tiempos y comparativos"));
      XSSFChart cierre = libro.getSheet("Tiempos y comparativos").getDrawingPatriarch().getCharts().get(0);
      assertEquals("Cierre de cada ola: establecido vs. conseguido", cierre.getTitleText().getString());
      assertEquals(2, cierre.getChartSeries().get(0).getSeriesCount());
      assertTrue(cierre.getCTChart().getPlotVisOnly().getVal());
    }
  }

  @Test
  void elCierreSeGuardaComoHoraDeExcelYYaCalculado() throws Exception {
    try (Workbook libro = abrir()) {
      Sheet hoja = libro.getSheet("Resumen");
      Cell conseguido = null;
      for (Row fila : hoja) {
        Cell c = fila.getCell(7);
        if (c != null && c.getCellType() == CellType.FORMULA && c.getCachedFormulaResultType() == CellType.NUMERIC) {
          conseguido = c;
          break;
        }
      }
      // 1 h 30 m = 1.5 / 24 de día, con formato [h]:mm (se ve «1:30»), ya calculado al entregar.
      assertEquals(1.5 / 24, conseguido.getNumericCellValue(), 1e-9);
      assertEquals("[h]:mm", conseguido.getCellStyle().getDataFormatString());
    }
  }

  @Test
  void resumenConLosIndicadoresYConclusionesDelPdf() throws Exception {
    try (Workbook libro = abrir()) {
      List<String> textos = textos(libro.getSheet("Resumen"));
      assertTrue(textos.contains("Cierre conseguido") && textos.contains("Mejora del tiempo"), textos.toString());
      // Límite 2 h y cierre conseguido 1 h 30 m: 25 % por debajo de lo establecido.
      assertTrue(textos.contains("+25 %"), textos.toString());
      assertTrue(textos.contains("1 de 1"), textos.toString());
      assertTrue(textos.stream().anyMatch((t) -> t.startsWith("• Cierre conseguido de 1h 30m frente a 2h")), textos.toString());
      assertTrue(textos.contains("CL1"), textos.toString());
    }
  }

  @Test
  void elSelectorCambiaTodoAlOtroDia() throws Exception {
    try (XSSFWorkbook libro = (XSSFWorkbook) abrir()) {
      assertTrue(textos(libro.getSheet("Resumen")).contains("1"), "L-X-V: 1 ruta movida");
      libro.getSheet("Resumen").getRow(1).getCell(2).setCellValue("M-J-S");
      libro.getCreationHelper().createFormulaEvaluator().evaluateAll();
      List<String> resumen = textos(libro.getSheet("Resumen"));
      assertTrue(resumen.contains("7"), "M-J-S: 7 rutas movidas: " + resumen);
      // Configuración no depende del selector: muestra los dos días, uno debajo del otro.
      List<String> grilla = textos(libro.getSheet("Configuración"));
      assertTrue(grilla.contains("L-X-V") && grilla.contains("M-J-S"), grilla.toString());
      assertTrue(grilla.contains("3001 · 20"), grilla.toString());
    }
  }

  @Test
  void cuadriculaConRutasDelDiaYTotalesEnterosHaciaArriba() throws Exception {
    try (Workbook libro = abrir()) {
      Sheet hoja = libro.getSheet("Configuración");
      List<String> textos = textos(hoja);
      assertTrue(textos.contains("2028 · 100 · D"), textos.toString());
      // Movida: venía de CHUTE 1 y quedó en CHUTE 2.
      assertTrue(textos.contains("2005 · 150 · D ↻"), textos.toString());
      // Solo de L-X-V: sin la marca de diaria.
      assertTrue(textos.contains("3001 · 20"), textos.toString());
      assertTrue(textos.contains("CHUTE 1"), textos.toString());
      // 199.10 bultos en CHUTE 1 se muestra como 200 (hacia arriba, sin decimales).
      assertTrue(numeros(hoja).contains(200.0), numeros(hoja).toString());
    }
  }

  @Test
  void conGruposLaRutaLlevaSuGrupo() throws Exception {
    ConfiguracionSimulacionResponse base = configuracion();
    List<GrupoClusterResponse> grupos =
        List.of(new GrupoClusterResponse("A", 1, 3, new BigDecimal("349.10"), 1L, "1", 2L, "2"));
    ConfiguracionSimulacionResponse cfg =
        new ConfiguracionSimulacionResponse(
            base.archivo(), base.velocidadBase(), base.operariosMaximos(), base.operariosPorChute(),
            base.topePorChute(),
            List.of(new ClusterParametroResponse(1L, "CL1", new BigDecimal("2"), "CERRADO", 40, 1L, 2L, 4, grupos, "LXV", avance(), false)),
            base.chutes(), base.carga());
    byte[] bytes =
        new SimulacionReporte.Libro(List.of(new SimulacionReporte.Dia(Balanceo.LXV, cfg, dashboard(1), rutas())))
            .escribir();
    try (Workbook libro = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      List<String> textos = textos(libro.getSheet("Configuración"));
      assertTrue(textos.contains("[A] 2028 · 100 · D"), textos.toString());
    }
  }

  @Test
  void totalGeneralConDosClustersEnUnChuteSinOperarios() throws Exception {
    // Simulación en curso: CL1 y CL2 comparten CHUTE 1 y ninguna ola tiene operarios.
    // Antes, sumar las cargas desempaquetaba un null (NPE) y /exportar respondía 500.
    ConfiguracionSimulacionResponse base = configuracion();
    ConfiguracionSimulacionResponse cfg =
        new ConfiguracionSimulacionResponse(
            base.archivo(),
            base.velocidadBase(),
            base.operariosMaximos(),
            null,
            base.topePorChute(),
            List.of(
                new ClusterParametroResponse(1L, "CL1", new BigDecimal("4"), "RUTAS", 140, null, null, null, List.of(), "LXV", avance(), false),
                new ClusterParametroResponse(2L, "CL2", BigDecimal.ZERO, "PENDIENTE", 113, null, null, null, List.of(), "LXV", avance(), false)),
            base.chutes(),
            List.of(
                new CargaSimulacionResponse(1L, 1L, 1, new BigDecimal("100"), CIEN, BigDecimal.TEN, 2, null),
                new CargaSimulacionResponse(2L, 1L, 1, new BigDecimal("50.5"), CIEN, BigDecimal.TEN, 3, null)));
    DashboardSimulacionResponse dash =
        new DashboardSimulacionResponse(
            new SimulacionKpisResponse(null, null, 0), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), 2, null, List.of());

    byte[] bytes =
        new SimulacionReporte.Libro(List.of(new SimulacionReporte.Dia(Balanceo.LXV, cfg, dash, List.of()))).escribir();
    try (Workbook libro = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      // 100 + 50.5 = 150.5 → 151 en el resumen total; cada cluster, en su cuadrícula.
      assertTrue(numeros(libro.getSheet("Resumen total")).contains(151.0));
      assertTrue(numeros(libro.getSheet("Configuración")).contains(51.0));
    }
  }

  @Test
  void unDiaSinRutasDelClusterNoSeMuestra() throws Exception {
    ConfiguracionSimulacionResponse base = configuracion();
    List<ClusterParametroResponse> tres = List.of(
        new ClusterParametroResponse(1L, "CL1", new BigDecimal("2"), "CERRADO", 40, 1L, 2L, 4, List.of(), "LXV", avance(), false),
        new ClusterParametroResponse(2L, "CL2", new BigDecimal("2"), "CERRADO", 40, 1L, 2L, 4, List.of(), "LXV", avance(), false),
        new ClusterParametroResponse(3L, "CL3", new BigDecimal("2"), "CERRADO", 40, 1L, 2L, 4, List.of(), "LXV", avance(), false));
    // L-X-V: CL1 y CL2; M-J-S: solo CL2; CL3 no tiene rutas.
    CargaSimulacionResponse cl1 = new CargaSimulacionResponse(1L, 1L, 1, new BigDecimal("20"), CIEN, BigDecimal.TEN, 1, 1);
    CargaSimulacionResponse cl2 = new CargaSimulacionResponse(2L, 2L, 1, new BigDecimal("30"), CIEN, BigDecimal.TEN, 1, 1);
    ConfiguracionSimulacionResponse lxv = new ConfiguracionSimulacionResponse(base.archivo(), base.velocidadBase(),
        base.operariosMaximos(), base.operariosPorChute(), base.topePorChute(), tres, base.chutes(), List.of(cl1, cl2));
    ConfiguracionSimulacionResponse mjs = new ConfiguracionSimulacionResponse(base.archivo(), base.velocidadBase(),
        base.operariosMaximos(), base.operariosPorChute(), base.topePorChute(), tres, base.chutes(), List.of(cl2));
    byte[] bytes =
        new SimulacionReporte.Libro(
                List.of(
                    new SimulacionReporte.Dia(Balanceo.LXV, lxv, dashboard(1),
                        List.of(rutaDe(1L, "3001", 1L, "LXV"), rutaDe(2L, "4001", 2L, "LXV"))),
                    new SimulacionReporte.Dia(Balanceo.MJS, mjs, dashboard(1), List.of(rutaDe(3L, "5001", 2L, "MJS")))))
            .escribir();
    try (Workbook libro = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      List<String> grilla = textos(libro.getSheet("Configuración"));
      // CL1 solo en L-X-V; CL2 en los dos días; CL3 no aparece; ningún «Sin rutas».
      assertEquals(2, grilla.stream().filter("L-X-V"::equals).count(), grilla.toString());
      assertEquals(1, grilla.stream().filter("M-J-S"::equals).count(), grilla.toString());
      assertFalse(grilla.contains("CL3") || grilla.stream().anyMatch((t) -> t.startsWith("Sin rutas")), grilla.toString());
      assertFalse(textos(libro.getSheet("Resumen total")).contains("CL3"));
    }
  }

  @Test
  void resumenTotalSumaLosDosDiasPorChuteYPorCluster() throws Exception {
    try (Workbook libro = abrir()) {
      Sheet hoja = libro.getSheet("Resumen total");
      assertTrue(textos(hoja).contains("Resumen total · L-X-V + M-J-S"), textos(hoja).toString());
      List<Double> numeros = numeros(hoja);
      // CHUTE 1: 199.10 cada día → 398.2 → 399; CHUTE 2: 150 + 150; total 698.2 → 699.
      assertTrue(numeros.contains(399.0) && numeros.contains(300.0) && numeros.contains(699.0), numeros.toString());
      // El único cluster pesa el 100 %.
      assertTrue(numeros.contains(1.0), numeros.toString());
    }
  }

  @Test
  void semaforoPorPuesto() {
    double[] niveles = SimulacionReporte.Libro.niveles(new long[] {10, 0, 30, 20, 30});
    assertArrayEquals(new double[] {0, -1, 1, 0.5, 1}, niveles);
    assertArrayEquals(new double[] {0.5, 0.5}, SimulacionReporte.Libro.niveles(new long[] {7, 7}));
  }

  @Test
  void formatosComoLaPantalla() {
    assertEquals(200, SimulacionReporte.entero(new BigDecimal("199.10")));
    assertEquals(200, SimulacionReporte.entero(new BigDecimal("200.00")));
    assertEquals("1h 25m", SimulacionReporte.horas(new BigDecimal("1.4166")));
    assertEquals("45m", SimulacionReporte.horas(new BigDecimal("0.75")));
    assertEquals("2h", SimulacionReporte.horas(new BigDecimal("2")));
    assertEquals("Sin operarios", SimulacionReporte.horas(new BigDecimal("1E+30")));
  }

  // ---------------- Datos de ejemplo ----------------

  /** L-X-V con 1 ruta movida y una ruta solo suya; M-J-S con las diarias y 7 movidas. */
  private static Workbook abrir() throws Exception {
    List<RutaSimulacionResponse> lxv = new ArrayList<>(rutas());
    lxv.add(ruta(4L, "3001", 1L, 1L, new BigDecimal("20"), "LXV"));
    byte[] bytes =
        new SimulacionReporte.Libro(
                List.of(
                    new SimulacionReporte.Dia(Balanceo.LXV, configuracion(), dashboard(1), lxv),
                    new SimulacionReporte.Dia(Balanceo.MJS, configuracion(), dashboard(7), rutas())))
            .escribir();
    return new XSSFWorkbook(new ByteArrayInputStream(bytes));
  }

  private static ConfiguracionSimulacionResponse configuracion() {
    return configuracion(
        List.of(
            new CargaSimulacionResponse(1L, 1L, 2, new BigDecimal("199.10"), new BigDecimal("600"),
                new BigDecimal("40"), 8, 3),
            new CargaSimulacionResponse(1L, 2L, 1, new BigDecimal("150"), new BigDecimal("300"),
                new BigDecimal("20"), 4, 1)));
  }

  private static ConfiguracionSimulacionResponse configuracion(List<CargaSimulacionResponse> carga) {
    ArchivoSimulacionResponse archivo =
        new ArchivoSimulacionResponse(
            10L, "Proyección enero", "enero.xlsx", null, "#1d63ed", "FINALIZADO", 3, 2,
            new BigDecimal("349.10"), new BigDecimal("900"), new BigDecimal("60"), 12,
            Instant.now(), Instant.now(), 1L, 1L);
    return new ConfiguracionSimulacionResponse(
        archivo,
        CIEN,
        4,
        null,
        4,
        List.of(new ClusterParametroResponse(1L, "CL1", new BigDecimal("2"), "CERRADO", 40, 1L, 2L, 4, List.of(), "LXV", avance(), false)),
        List.of(
            new ChuteParametroResponse(1L, "1", 1, List.of(1L)),
            new ChuteParametroResponse(2L, "2", 1, List.of(1L))),
        carga);
  }

  private static DashboardSimulacionResponse dashboard(int movidas) {
    return new DashboardSimulacionResponse(
        new SimulacionKpisResponse(new BigDecimal("2.5"), new BigDecimal("1.5"), movidas),
        List.of(new ClusterComparativoResponse(1L, "CL1", new BigDecimal("2.5"), new BigDecimal("1.5"),
            new BigDecimal("2"), false, 3, 3, 0, 0, new BigDecimal("900"), new BigDecimal("900"),
            new BigDecimal("349.10"), new BigDecimal("349.10"), new BigDecimal("60"),
            new BigDecimal("60"), 12, 12)),
        List.of(new ChuteComparativoResponse(1L, "CHUTE 1", 1, 3, 3, 2, 1, 0,
            new BigDecimal("2.5"), new BigDecimal("0.67"), "OK", new BigDecimal("349.10"),
            new BigDecimal("199.10"), new BigDecimal("900"), new BigDecimal("600"),
            new BigDecimal("60"), new BigDecimal("40"), 12, 8, List.of("CL1"), List.of("CL1"),
            List.of("CL1"))),
        List.of(),
        List.of(),
        List.of(new OlaSimulacionResponse(1L, "CL1", "CERRADO", 1, new BigDecimal("2"),
            new BigDecimal("1.5"), new BigDecimal("349.10"), new BigDecimal("900"),
            new BigDecimal("60"), 12)),
        List.of(),
        4,
        null,
        List.of(new DotacionChuteResponse(1L, "CHUTE 1", List.of(3)),
            new DotacionChuteResponse(2L, "CHUTE 2", List.of(1))));
  }

  /** Diarias de los dos días. */
  private static List<RutaSimulacionResponse> rutas() {
    List<RutaSimulacionResponse> rutas = new ArrayList<>();
    rutas.add(ruta(1L, "2028", 1L, 1L, new BigDecimal("99.10"), "DIARIA"));
    rutas.add(ruta(2L, "2001", 1L, 1L, new BigDecimal("100"), "DIARIA"));
    // Movida: venía de CHUTE 1 y quedó en CHUTE 2.
    rutas.add(ruta(3L, "2005", 2L, 1L, new BigDecimal("150"), "DIARIA"));
    return rutas;
  }

  /** Ruta de un cluster en el chute del mismo número, sin grupo. */
  private static RutaSimulacionResponse rutaDe(Long id, String codigo, Long cluster, String frecuencia) {
    return new RutaSimulacionResponse(id, cluster, String.valueOf(cluster), cluster, String.valueOf(cluster), cluster,
        "CL" + cluster, cluster, "CL" + cluster, false, codigo, "B2C", new BigDecimal("10"), CIEN, BigDecimal.TEN, 1,
        null, frecuencia, false);
  }

  private static RutaSimulacionResponse ruta(
      Long id, String codigo, Long chute, Long chuteOriginal, BigDecimal bultos, String frecuencia) {
    return new RutaSimulacionResponse(id, chute, String.valueOf(chute), chuteOriginal,
        String.valueOf(chuteOriginal), 1L, "CL1", 1L, "CL1", false, codigo, "B2C", bultos,
        CIEN, BigDecimal.TEN, 4, "A", frecuencia, false);
  }

  private static int graficos(XSSFWorkbook libro, String hoja) {
    var dibujo = libro.getSheet(hoja).getDrawingPatriarch();
    return dibujo == null ? 0 : dibujo.getCharts().size();
  }

  /** Textos visibles: los escritos y los resultados ya calculados de las fórmulas. */
  private static List<String> textos(Sheet hoja) {
    List<String> textos = new ArrayList<>();
    for (Row fila : hoja) {
      for (Cell celda : fila) {
        CellType tipo =
            celda.getCellType() == CellType.FORMULA ? celda.getCachedFormulaResultType() : celda.getCellType();
        if (tipo == CellType.STRING && !celda.getStringCellValue().isBlank()) {
          textos.add(celda.getStringCellValue());
        }
      }
    }
    return textos;
  }

  private static List<Double> numeros(Sheet hoja) {
    List<Double> numeros = new ArrayList<>();
    for (Row fila : hoja) {
      for (Cell celda : fila) {
        CellType tipo =
            celda.getCellType() == CellType.FORMULA ? celda.getCachedFormulaResultType() : celda.getCellType();
        if (tipo == CellType.NUMERIC) {
          numeros.add(celda.getNumericCellValue());
        }
      }
    }
    return numeros;
  }

  private static ClusterParametroResponse.AvanceClusterResponse avance() {
    return new ClusterParametroResponse.AvanceClusterResponse("RUTAS", "RUTAS", "RUTAS", 1, 1, 1);
  }
}
