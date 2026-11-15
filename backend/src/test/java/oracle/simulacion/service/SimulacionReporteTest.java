package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import oracle.simulacion.response.ArchivoSimulacionResponse;
import oracle.simulacion.response.ChuteParametroResponse;
import oracle.simulacion.response.ClusterParametroResponse;
import oracle.simulacion.response.ConfiguracionSimulacionResponse;
import oracle.simulacion.response.DistribucionResponse;
import oracle.simulacion.response.RutaSimulacionResponse;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** Exportación: la distribución de rutas por cluster y chute, y su Excel. */
class SimulacionReporteTest {

  private static final BigDecimal CIEN = new BigDecimal("100");

  @Test
  void lasTresFrecuenciasVanEnUnSoloBloquePorCluster() {
    DistribucionResponse d = distribucion();

    assertEquals(1, d.clusters().size(), "CL2 no tiene rutas: no se lista");
    DistribucionResponse.Cluster cl1 = d.clusters().getFirst();
    assertEquals("CL1", cl1.nombre());
    assertEquals(4, cl1.rutas());
    assertEquals(1, cl1.diarias());
    assertEquals(2, cl1.lxv());
    assertEquals(1, cl1.mjs());
    DistribucionResponse.Celda chute1 = cl1.celdas().getFirst();
    // Diarias primero, después L-X-V y M-J-S.
    assertEquals(
        List.of(
            new DistribucionResponse.Ruta("2028", "DIARIA"),
            new DistribucionResponse.Ruta("3001", "LXV"),
            new DistribucionResponse.Ruta("4001", "MJS")),
        chute1.rutas());
  }

  @Test
  void losBultosDeCadaDiaSumanLaDiariaYLasRutasDeEseDia() {
    DistribucionResponse.Cluster cl1 = distribucion().clusters().getFirst();
    DistribucionResponse.Celda chute1 = cl1.celdas().getFirst();

    assertEquals(119, chute1.bultosLxv(), "99,10 de la diaria + 20 de L-X-V");
    assertEquals(130, chute1.bultosMjs(), "99,10 de la diaria + 30,60 de M-J-S, redondeado");
    // Del cluster: se suma sin redondear y se redondea al final.
    assertEquals(169, cl1.bultosLxv());
    assertEquals(130, cl1.bultosMjs());
    DistribucionResponse.Totales t = distribucion().totales();
    assertEquals(1, t.clusters());
    assertEquals(4, t.rutas());
    assertEquals(2, t.chutesEnUso());
  }

  @Test
  void losChutesVanEnOrdenNaturalConLosBloqueadosEnSuLugar() {
    List<DistribucionResponse.Chute> chutes = distribucion().chutes();

    assertEquals(List.of("1", "2", "19", "20"), chutes.stream().map(DistribucionResponse.Chute::nombre).toList());
    assertNull(chutes.get(2).id(), "un bloqueado no tiene id");
    assertTrue(chutes.get(2).bloqueado());
    assertTrue(chutes.get(1).buff());
  }

  @Test
  void elExcelEsUnaSolaHojaConUnBloquePorCluster() throws Exception {
    try (XSSFWorkbook libro = abrir()) {
      assertEquals(1, libro.getNumberOfSheets());
      Sheet hoja = libro.getSheet("Distribución");
      assertNotNull(hoja);
      List<String> textos = textos(hoja);
      assertTrue(textos.contains("DISTRIBUCIÓN DE RUTAS POR CLUSTER Y CHUTE"), textos.toString());
      assertEquals(1, textos.stream().filter("CL1"::equals).count(), "un solo bloque para CL1");
      assertTrue(textos.contains("CHUTE 2"));
      assertTrue(textos.contains("BLOQ."));
    }
  }

  @Test
  void cadaRutaLlevaElColorDeSuFrecuencia() throws Exception {
    try (XSSFWorkbook libro = abrir()) {
      XSSFCell celda = buscar(libro.getSheet("Distribución"), "2028\n3001\n4001");
      assertNotNull(celda, "las tres rutas apiladas en la celda del chute 1");
      XSSFRichTextString texto = celda.getRichStringCellValue();
      assertEquals("FF" + DistribucionExcel.TINTA, color(texto, "2028"));
      assertEquals("FF" + DistribucionExcel.AZUL, color(texto, "3001"));
      assertEquals("FF" + DistribucionExcel.AMBAR, color(texto, "4001"));
    }
  }

  @Test
  void lasFilasDeBultosMuestranCadaDiaYUnGuionDondeElChuteNoTrabaja() throws Exception {
    try (XSSFWorkbook libro = abrir()) {
      Sheet hoja = libro.getSheet("Distribución");
      Row lxv = filaQueEmpieza(hoja, "BULTOS L-X-V");
      Row mjs = filaQueEmpieza(hoja, "BULTOS M-J-S");
      assertEquals(119, (int) lxv.getCell(1).getNumericCellValue());
      assertEquals(50, (int) lxv.getCell(2).getNumericCellValue());
      assertEquals(130, (int) mjs.getCell(1).getNumericCellValue());
      // El chute 2 solo tiene una L-X-V: en M-J-S no trabaja.
      assertEquals("—", mjs.getCell(2).getStringCellValue());
    }
  }

  @Test
  void conLogoElEncabezadoLoLleva() throws Exception {
    byte[] logo;
    try (var entrada = getClass().getClassLoader().getResourceAsStream("reporte/fp-logo.jpg")) {
      assertNotNull(entrada, "el logo está en los recursos");
      logo = entrada.readAllBytes();
    }
    try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(DistribucionExcel.escribir(distribucion(), logo)))) {
      assertEquals(1, libro.getAllPictures().size());
    }
  }

  @Test
  void laEtiquetaDelChuteSiempreDiceChute() {
    assertEquals("CHUTE 15", DistribucionExcel.etiquetaChute("15"));
    assertEquals("CHUTE 15", DistribucionExcel.etiquetaChute("Chute 15"));
  }

  @Test
  void unaFrecuenciaSinDatoCuentaComoDiaria() {
    assertEquals("DIARIA", SimulacionReporte.frecuencia(null));
    assertEquals("DIARIA", SimulacionReporte.frecuencia("L-M"));
    assertEquals("MJS", SimulacionReporte.frecuencia("MJS"));
  }

  // ---------------- Datos de ejemplo ----------------

  /** CL1: diaria + L-X-V + M-J-S en el chute 1 y una L-X-V en el 2. CL2 sin rutas. */
  private static DistribucionResponse distribucion() {
    List<RutaSimulacionResponse> rutas = new ArrayList<>();
    rutas.add(ruta(1L, "4001", 1L, "30.60", "MJS"));
    rutas.add(ruta(2L, "3001", 1L, "20", "LXV"));
    rutas.add(ruta(3L, "2028", 1L, "99.10", "DIARIA"));
    rutas.add(ruta(4L, "3002", 2L, "50", "LXV"));
    return SimulacionReporte.armar(configuracion(), rutas, Instant.parse("2026-11-14T15:00:00Z"));
  }

  private static XSSFWorkbook abrir() throws Exception {
    return new XSSFWorkbook(new ByteArrayInputStream(DistribucionExcel.escribir(distribucion(), null)));
  }

  private static ConfiguracionSimulacionResponse configuracion() {
    ArchivoSimulacionResponse archivo =
        new ArchivoSimulacionResponse(
            10L, "Proyección enero", "enero.xlsx", null, "#1d63ed", "FINALIZADO", 4, 2,
            new BigDecimal("199.70"), new BigDecimal("900"), new BigDecimal("60"), 12,
            Instant.now(), Instant.now(), 1L, 1L);
    return new ConfiguracionSimulacionResponse(
        archivo,
        CIEN,
        4,
        null,
        4,
        List.of(cluster(1L, "CL1"), cluster(2L, "CL2")),
        List.of(
            new ChuteParametroResponse(2L, "2", 1, List.of(1L), false, true),
            new ChuteParametroResponse(1L, "1", 1, List.of(1L), false, false)),
        List.of(),
        List.of("20", "19"));
  }

  private static ClusterParametroResponse cluster(Long id, String nombre) {
    return new ClusterParametroResponse(
        id, nombre, new BigDecimal("2"), "CERRADO", 40, 1L, 2L, 4, List.of(), "LXV",
        new ClusterParametroResponse.AvanceClusterResponse("RUTAS", "RUTAS", "RUTAS", 1, 1, 1), false);
  }

  private static RutaSimulacionResponse ruta(Long id, String codigo, Long chute, String bultos, String frecuencia) {
    return new RutaSimulacionResponse(id, chute, String.valueOf(chute), chute, String.valueOf(chute), 1L, "CL1", 1L,
        "CL1", false, codigo, "B2C", new BigDecimal(bultos), CIEN, BigDecimal.TEN, 4, "A", frecuencia, false);
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

  private static XSSFCell buscar(Sheet hoja, String texto) {
    for (Row fila : hoja) {
      for (Cell celda : fila) {
        if (celda.getCellType() == CellType.STRING && texto.equals(celda.getStringCellValue())) {
          return (XSSFCell) celda;
        }
      }
    }
    return null;
  }

  private static Row filaQueEmpieza(Sheet hoja, String inicio) {
    for (Row fila : hoja) {
      Cell primera = fila.getCell(0);
      if (primera != null && primera.getCellType() == CellType.STRING && primera.getStringCellValue().startsWith(inicio)) {
        return fila;
      }
    }
    throw new AssertionError("No está la fila " + inicio);
  }

  /** Color (ARGB) del tramo de texto que empieza con {@code parte}. */
  private static String color(XSSFRichTextString texto, String parte) {
    int inicio = texto.getString().indexOf(parte);
    return texto.getFontAtIndex(inicio).getXSSFColor().getARGBHex();
  }
}
