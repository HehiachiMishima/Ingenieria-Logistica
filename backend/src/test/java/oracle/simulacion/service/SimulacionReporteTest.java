package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import oracle.simulacion.response.ArchivoSimulacionResponse;
import oracle.simulacion.response.ChuteParametroResponse;
import oracle.simulacion.response.ClusterParametroResponse;
import oracle.simulacion.response.ConfiguracionSimulacionResponse;
import oracle.simulacion.response.DistribucionResponse;
import oracle.simulacion.response.RutaSimulacionResponse;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
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
        List.of("2028 DIARIA", "3001 LXV", "4001 MJS"),
        chute1.rutas().stream().map((r) -> r.codigo() + " " + r.frecuencia()).toList());
    DistribucionResponse.Ruta diaria = chute1.rutas().getFirst();
    assertEquals("A", diaria.grupo());
    assertEquals("#7c3aed", diaria.color(), "primer grupo del cluster: primer color de la paleta");
    assertEquals(0, new BigDecimal("99.10").compareTo(diaria.bultos()));
    assertEquals("B2C", diaria.tipo());
    assertEquals("B2B", chute1.rutas().getLast().tipo(), "cada ruta lleva su tipo");
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
  void unChuteManualVaConSuMarcaYSusRutasTambienEnElExcel() throws Exception {
    List<RutaSimulacionResponse> rutas =
        List.of(ruta(1L, "3001", 1L, "20", "LXV"), ruta(2L, "3022", 19L, "40", "LXV"));
    DistribucionResponse d =
        SimulacionReporte.armar(
            configuracionConManual(2), configuracionConManual(3), rutas, Instant.parse("2026-11-14T15:00:00Z"));

    DistribucionResponse.Chute ch19 =
        d.chutes().stream().filter((c) -> "19".equals(c.nombre())).findFirst().orElseThrow();
    assertTrue(ch19.manual());
    assertFalse(ch19.bloqueado());
    assertEquals(19L, ch19.id());
    assertTrue(
        d.clusters().getFirst().celdas().stream()
            .anyMatch((x) -> Long.valueOf(19L).equals(x.chuteId()) && x.rutas().size() == 1),
        "la ruta que se puso a mano en el 19 se ve en su chute");
    try (XSSFWorkbook libro =
        new XSSFWorkbook(new ByteArrayInputStream(DistribucionExcel.escribir(d, DistribucionExcel.Marcas.NINGUNA)))) {
      List<String> textos = textos(libro.getSheet("Distribución"));
      assertTrue(textos.contains("CHUTE 19 · MANUAL"), textos.toString());
      assertTrue(textos.stream().anyMatch((t) -> t.contains("3022")), textos.toString());
    }
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
  void cadaRutaEsUnaFichaConSuGrupoSuFrecuenciaYSusBultos() throws Exception {
    try (XSSFWorkbook libro = abrir()) {
      Sheet hoja = libro.getSheet("Distribución");
      // Dos líneas, como la planilla: tipo · días · bultos (99,10 hacia arriba) y grupo · código,
      // con espacios a la derecha que centran el código.
      XSSFCell diaria = buscar(hoja, "▐B2C  Diaria  ■ 100\nA  2028    ");
      assertNotNull(diaria, textos(hoja).toString());
      XSSFRichTextString texto = diaria.getRichStringCellValue();
      // El tipo: barra y texto en verde para B2C.
      assertEquals("FF" + DistribucionExcel.VERDE, color(texto, "▐"));
      assertEquals("FF" + DistribucionExcel.VERDE, color(texto, "B2C"));
      assertEquals("FF" + DistribucionExcel.TINTA, color(texto, "Diaria"));
      assertEquals("FF" + DistribucionExcel.CAFE, color(texto, " 100"));
      assertEquals("FF7C3AED", color(texto, "A  2028"), "número del grupo con su color");
      assertEquals("FF" + DistribucionExcel.TINTA, color(texto, "2028"));
      // Enmarcada en sus cuatro lados, centrada y en dos líneas.
      XSSFCellStyle estilo = diaria.getCellStyle();
      for (BorderStyle lado : List.of(estilo.getBorderTop(), estilo.getBorderRight(), estilo.getBorderBottom(), estilo.getBorderLeft())) {
        assertEquals(BorderStyle.MEDIUM, lado);
      }
      assertEquals("FF" + DistribucionExcel.MARCO, estilo.getLeftBorderXSSFColor().getARGBHex());
      assertEquals("FF" + DistribucionExcel.MARCO, estilo.getTopBorderXSSFColor().getARGBHex());
      assertEquals(HorizontalAlignment.CENTER, estilo.getAlignment());
      assertTrue(estilo.getWrapText(), "las dos líneas de la ficha");

      XSSFCell lxv = buscar(hoja, "▐B2C  L-X-V  ■ 20\nA  3001    ");
      XSSFCell mjs = buscar(hoja, "▐B2B  M-J-S  ■ 31\nA  4001    ");
      assertNotNull(lxv);
      assertNotNull(mjs);
      assertEquals("FF" + DistribucionExcel.AZUL, color(lxv.getRichStringCellValue(), "L-X-V"));
      assertEquals("FF" + DistribucionExcel.AZUL, color(lxv.getRichStringCellValue(), "3001"));
      assertEquals("FF" + DistribucionExcel.AMBAR, color(mjs.getRichStringCellValue(), "4001"));
      // B2B: barra dorada y texto azul marino (el dorado sobre blanco no se lee).
      assertEquals("FF" + DistribucionExcel.ORO, color(mjs.getRichStringCellValue(), "▐"));
      assertEquals("FF" + DistribucionExcel.NAVY, color(mjs.getRichStringCellValue(), "B2B"));
      // Las tres fichas del chute 1 van una debajo de otra, con una fila de respiro entre ellas.
      assertEquals(diaria.getColumnIndex(), mjs.getColumnIndex());
      assertEquals(diaria.getRowIndex() + 4, mjs.getRowIndex());
      assertEquals(DistribucionExcel.ALTO_FICHA, hoja.getRow(lxv.getRowIndex()).getHeightInPoints());
      assertEquals(DistribucionExcel.ALTO_RESPIRO, hoja.getRow(lxv.getRowIndex() + 1).getHeightInPoints());
    }
  }

  @Test
  void losGruposTomanLaPaletaEnOrdenNatural() {
    List<RutaSimulacionResponse> rutas = new ArrayList<>();
    rutas.add(ruta(1L, "3001", 1L, "10", "LXV", "10"));
    rutas.add(ruta(2L, "3002", 1L, "10", "LXV", "2"));
    rutas.add(ruta(3L, "3003", 2L, "10", "LXV", null));
    DistribucionResponse d = SimulacionReporte.armar(configuracion(2), configuracion(3), rutas, Instant.now());

    Map<String, DistribucionResponse.Ruta> porCodigo = new HashMap<>();
    d.clusters().getFirst().celdas().forEach((c) -> c.rutas().forEach((r) -> porCodigo.put(r.codigo(), r)));
    assertEquals("#7c3aed", porCodigo.get("3002").color(), "el grupo 2 va antes que el 10");
    assertEquals("#0891b2", porCodigo.get("3001").color());
    assertNull(porCodigo.get("3003").grupo(), "sin grupo no lleva color");
    assertNull(porCodigo.get("3003").color());
    // Sin grupo, la línea de la ruta es solo el código (ya va al centro).
    assertEquals("▐B2C  L-X-V  ■ 10\n3003", DistribucionExcel.textoFicha(porCodigo.get("3003")));
    // Sin tipo, la primera línea empieza en los días.
    assertEquals(
        "L-X-V  ■ 10\n3003",
        DistribucionExcel.textoFicha(new DistribucionResponse.Ruta("3003", "LXV", null, null, BigDecimal.TEN, null)));
  }

  @Test
  void lasFilasDeBultosMuestranCadaDiaYUnGuionDondeElChuteNoTrabaja() throws Exception {
    try (XSSFWorkbook libro = abrir()) {
      Sheet hoja = libro.getSheet("Distribución");
      assertEquals(2, filaQueEmpieza(hoja, "OPERARIOS L-X-V").getCell(1).getNumericCellValue());
      assertEquals(3, filaQueEmpieza(hoja, "OPERARIOS M-J-S").getCell(1).getNumericCellValue());
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
  void elEncabezadoLlevaElLogoYLaMarcaAtlas() throws Exception {
    var marcas = new DistribucionExcel.Marcas(recurso("reporte/fp-logo.jpg"), recurso("reporte/atlas-marca.png"));
    try (XSSFWorkbook libro =
        new XSSFWorkbook(new ByteArrayInputStream(DistribucionExcel.escribir(distribucion(), marcas)))) {
      assertEquals(2, libro.getAllPictures().size());
      // La fecha va en la banda ATLAS (si hay chutes suficientes) o tras el subtítulo.
      assertTrue(textos(libro.getSheet("Distribución")).stream().anyMatch((t) -> t.contains("Generado 14/11/2026 10:00")));
    }
  }

  private byte[] recurso(String ruta) throws Exception {
    try (var entrada = getClass().getClassLoader().getResourceAsStream(ruta)) {
      assertNotNull(entrada, ruta + " está en los recursos");
      return entrada.readAllBytes();
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
    rutas.add(ruta(1L, "4001", 1L, "30.60", "MJS", "A", "B2B"));
    rutas.add(ruta(2L, "3001", 1L, "20", "LXV"));
    rutas.add(ruta(3L, "2028", 1L, "99.10", "DIARIA"));
    rutas.add(ruta(4L, "3002", 2L, "50", "LXV"));
    return SimulacionReporte.armar(configuracion(2), configuracion(3), rutas, Instant.parse("2026-11-14T15:00:00Z"));
  }

  private static XSSFWorkbook abrir() throws Exception {
    return new XSSFWorkbook(
        new ByteArrayInputStream(DistribucionExcel.escribir(distribucion(), DistribucionExcel.Marcas.NINGUNA)));
  }

  private static ConfiguracionSimulacionResponse configuracion(int operarios) {
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
        List.of(new oracle.simulacion.response.CargaSimulacionResponse(1L, 1L, 1, CIEN, CIEN, CIEN, 1, operarios)),
        List.of("20", "19"));
  }

  /** Como {@link #configuracion}, con el 19 de uso manual (visible, con rutas) y sin bloqueados. */
  private static ConfiguracionSimulacionResponse configuracionConManual(int operarios) {
    ConfiguracionSimulacionResponse base = configuracion(operarios);
    return new ConfiguracionSimulacionResponse(
        base.archivo(),
        CIEN,
        4,
        null,
        4,
        base.clusters(),
        List.of(
            new ChuteParametroResponse(2L, "2", 1, List.of(1L), false, true),
            new ChuteParametroResponse(1L, "1", 1, List.of(1L), false, false),
            new ChuteParametroResponse(19L, "19", 1, List.of(1L), false, false, true)),
        base.carga(),
        List.of());
  }

  private static ClusterParametroResponse cluster(Long id, String nombre) {
    return new ClusterParametroResponse(
        id, nombre, new BigDecimal("2"), "CERRADO", 40, 1L, 2L, 4, List.of(), "LXV",
        new ClusterParametroResponse.AvanceClusterResponse("RUTAS", "RUTAS", "RUTAS", 1, 1, 1), false);
  }

  private static RutaSimulacionResponse ruta(Long id, String codigo, Long chute, String bultos, String frecuencia) {
    return ruta(id, codigo, chute, bultos, frecuencia, "A");
  }

  private static RutaSimulacionResponse ruta(
      Long id, String codigo, Long chute, String bultos, String frecuencia, String grupo) {
    return ruta(id, codigo, chute, bultos, frecuencia, grupo, "B2C");
  }

  private static RutaSimulacionResponse ruta(
      Long id, String codigo, Long chute, String bultos, String frecuencia, String grupo, String tipo) {
    return new RutaSimulacionResponse(id, chute, String.valueOf(chute), chute, String.valueOf(chute), 1L, "CL1", 1L,
        "CL1", false, codigo, tipo, new BigDecimal(bultos), CIEN, BigDecimal.TEN, 4, grupo, frecuencia, false);
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
