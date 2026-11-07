package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import oracle.simulacion.response.ArchivoSimulacionResponse;
import oracle.simulacion.response.CargaSimulacionResponse;
import oracle.simulacion.response.ChuteComparativoResponse;
import oracle.simulacion.response.ChuteParametroResponse;
import oracle.simulacion.response.ClusterComparativoResponse;
import oracle.simulacion.response.ClusterParametroResponse;
import oracle.simulacion.response.ConfiguracionSimulacionResponse;
import oracle.simulacion.response.DashboardSimulacionResponse;
import oracle.simulacion.response.DotacionChuteResponse;
import oracle.simulacion.response.HistorialSimulacionResponse;
import oracle.simulacion.response.OlaSimulacionResponse;
import oracle.simulacion.response.RutaSimulacionResponse;
import oracle.simulacion.response.SimulacionKpisResponse;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SimulacionReporteTest {

  private static final BigDecimal CIEN = new BigDecimal("100");

  @Test
  void ejecutivoTieneResumenYCuadriculas() throws Exception {
    try (Workbook libro = abrir(SimulacionReporte.Nivel.EJECUTIVO)) {
      assertEquals(2, libro.getNumberOfSheets());
      assertEquals("Resumen", libro.getSheetName(0));
      assertEquals("Configuración", libro.getSheetName(1));
      List<String> textos = textos(libro.getSheet("Resumen"));
      assertTrue(textos.contains("Reporte ejecutivo de simulación"), textos.toString());
      assertTrue(textos.contains("CL1"), textos.toString());
      assertTrue(textos.stream().anyMatch((t) -> t.startsWith("• 1 de 1 clusters cumplen")), textos.toString());
      // Límite 2 h y cierre conseguido 1 h 30 m: 25 % por debajo de lo establecido.
      assertTrue(textos.contains("+25 %"), textos.toString());
      assertTrue(textos.contains("Cierre establecido") && textos.contains("Cierre conseguido"), textos.toString());
      assertTrue(!textos.contains("Tiempo antes") && !textos.contains("Estado"), textos.toString());
    }
  }

  @Test
  void detalladoSumaChutesRutasOperariosEHistorial() throws Exception {
    try (Workbook libro = abrir(SimulacionReporte.Nivel.DETALLADO)) {
      assertEquals(
          List.of("Resumen", "Configuración", "Chutes", "Rutas", "Operarios por ola", "Historial"),
          List.of(
              libro.getSheetName(0),
              libro.getSheetName(1),
              libro.getSheetName(2),
              libro.getSheetName(3),
              libro.getSheetName(4),
              libro.getSheetName(5)));
      List<String> rutas = textos(libro.getSheet("Rutas"));
      assertTrue(rutas.contains("2028"), rutas.toString());
      assertTrue(rutas.contains("Sí"), "la ruta movida se marca: " + rutas);
    }
  }

  @Test
  void cuadriculaMuestraRutasYTotalesEnterosHaciaArriba() throws Exception {
    try (Workbook libro = abrir(SimulacionReporte.Nivel.EJECUTIVO)) {
      Sheet hoja = libro.getSheet("Configuración");
      List<String> textos = textos(hoja);
      assertTrue(textos.contains("2028 · B2C"), textos.toString());
      assertTrue(textos.contains("CHUTE 1"), textos.toString());
      // 199.10 bultos en CHUTE 1 se muestra como 200 (hacia arriba, sin decimales).
      assertTrue(numeros(hoja).contains(200.0), numeros(hoja).toString());
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
                new ClusterParametroResponse(1L, "CL1", new BigDecimal("4"), "RUTAS", 140, null, null, null),
                new ClusterParametroResponse(2L, "CL2", BigDecimal.ZERO, "PENDIENTE", 113, null, null, null)),
            base.chutes(),
            List.of(
                new CargaSimulacionResponse(1L, 1L, 1, new BigDecimal("100"), CIEN, BigDecimal.TEN, 2, null),
                new CargaSimulacionResponse(2L, 1L, 1, new BigDecimal("50.5"), CIEN, BigDecimal.TEN, 3, null)));
    DashboardSimulacionResponse dash =
        new DashboardSimulacionResponse(
            new SimulacionKpisResponse(null, null, 0), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), 2, null, List.of());

    for (SimulacionReporte.Nivel nivel : SimulacionReporte.Nivel.values()) {
      byte[] bytes = new SimulacionReporte.Libro(cfg, dash, List.of(), List.of()).escribir(nivel);
      try (Workbook libro = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
        // 100 + 50.5 = 150.5 → 151 en el total general.
        assertTrue(numeros(libro.getSheet("Configuración")).contains(151.0));
      }
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

  private static Workbook abrir(SimulacionReporte.Nivel nivel) throws Exception {
    byte[] bytes = new SimulacionReporte.Libro(configuracion(), dashboard(), rutas(), historial()).escribir(nivel);
    return new XSSFWorkbook(new ByteArrayInputStream(bytes));
  }

  private static ConfiguracionSimulacionResponse configuracion() {
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
        List.of(new ClusterParametroResponse(1L, "CL1", new BigDecimal("2"), "CERRADO", 40, 1L, 2L, 4)),
        List.of(
            new ChuteParametroResponse(1L, "1", 1, List.of(1L)),
            new ChuteParametroResponse(2L, "2", 1, List.of(1L))),
        List.of(
            new CargaSimulacionResponse(1L, 1L, 2, new BigDecimal("199.10"), new BigDecimal("600"),
                new BigDecimal("40"), 8, 3),
            new CargaSimulacionResponse(1L, 2L, 1, new BigDecimal("150"), new BigDecimal("300"),
                new BigDecimal("20"), 4, 1)));
  }

  private static DashboardSimulacionResponse dashboard() {
    return new DashboardSimulacionResponse(
        new SimulacionKpisResponse(new BigDecimal("2.5"), new BigDecimal("1.5"), 1),
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

  private static List<RutaSimulacionResponse> rutas() {
    List<RutaSimulacionResponse> rutas = new ArrayList<>();
    rutas.add(ruta(1L, "2028", 1L, 1L, new BigDecimal("99.10")));
    rutas.add(ruta(2L, "2001", 1L, 1L, new BigDecimal("100")));
    // Movida: venía de CHUTE 1 y quedó en CHUTE 2.
    rutas.add(ruta(3L, "2005", 2L, 1L, new BigDecimal("150")));
    return rutas;
  }

  private static RutaSimulacionResponse ruta(
      Long id, String codigo, Long chute, Long chuteOriginal, BigDecimal bultos) {
    return new RutaSimulacionResponse(id, chute, String.valueOf(chute), chuteOriginal,
        String.valueOf(chuteOriginal), 1L, "CL1", 1L, "CL1", false, codigo, "B2C", bultos,
        CIEN, BigDecimal.TEN, 4);
  }

  private static List<HistorialSimulacionResponse> historial() {
    return List.of(new HistorialSimulacionResponse(1L, null, "MOVER_RUTA", "MANUAL", 3L, "2005",
        "CL1", "B2C", new BigDecimal("150"), "1", "2", null, null, null, 1, 1, Instant.now(), 1L));
  }

  private static List<String> textos(Sheet hoja) {
    List<String> textos = new ArrayList<>();
    for (Row fila : hoja) {
      for (Cell celda : fila) {
        if (celda.getCellType() == CellType.STRING && !celda.getStringCellValue().isBlank()) {
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
        if (celda.getCellType() == CellType.NUMERIC) {
          numeros.add(celda.getNumericCellValue());
        }
      }
    }
    return numeros;
  }
}
