package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import oracle.simulacion.model.TipoRuta;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import oracle.common.exception.ConflictoException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class SimulacionExcelTest {

  @Test
  void agrupaPorRutaYSumaBultosUnidadesYLineas() throws Exception {
    byte[] contenido;
    try (XSSFWorkbook libro = new XSSFWorkbook();
        ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
      Sheet hoja = libro.createSheet("Proyección");
      String[] cabecera = {
        "COD.",
        "RUTA",
        "CLUSTER",
        "CHUTE",
        "BULTOS PROMEDIO",
        "UNIDADES PROMEDIO",
        "LÍNEAS PROMEDIO",
        "TIPO"
      };
      Row filaCabecera = hoja.createRow(0);
      for (int i = 0; i < cabecera.length; i++) {
        filaCabecera.createCell(i).setCellValue(cabecera[i]);
      }
      escribirFila(hoja, 1, "1", "3030", "CL1", "CHUTE 1", 10, 100, 20, "B2C");
      escribirFila(hoja, 2, "2", "3030", "CL1", "CHUTE 1", 5, 50, 10, "B2C");
      escribirFila(hoja, 3, "3", "3012", "CL2", "CHUTE 2", 8, 80, 16, "B2B");
      libro.write(salida);
      contenido = salida.toByteArray();
    }

    MockMultipartFile archivo =
        new MockMultipartFile(
            "archivo",
            "proyeccion.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            contenido);

    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(archivo);

    assertEquals(2, analisis.rutas().size());
    assertEquals("23.00", analisis.totalBultos().toPlainString());

    SimulacionExcel.FilaRuta ruta3030 =
        analisis.rutas().stream()
            .filter((ruta) -> ruta.codigoRuta().equals("3030"))
            .findFirst()
            .orElseThrow();
    assertEquals("15.00", ruta3030.bultos().toPlainString());
    assertEquals("150.00", ruta3030.unidades().toPlainString());
    assertEquals("30.00", ruta3030.lineas().toPlainString());
    assertEquals(2, ruta3030.conteoCod());
    assertEquals(TipoRuta.B2C, ruta3030.tipo());

    SimulacionExcel.FilaRuta ruta3012 =
        analisis.rutas().stream()
            .filter((ruta) -> ruta.codigoRuta().equals("3012"))
            .findFirst()
            .orElseThrow();
    assertEquals(TipoRuta.B2B, ruta3012.tipo());
  }

  @Test
  void permiteLaMismaRutaConTiposDistintos() {
    String csv =
        "RUTA,CLUSTER,CHUTE,TIPO,BULTOS\n"
            + "3030,CL1,CHUTE 1,B2C,12\n"
            + "3030,CL1,CHUTE 1,B2B,8\n";

    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(csv(csv));

    assertEquals(2, analisis.rutas().size());
    assertEquals("20.00", analisis.totalBultos().toPlainString());
    assertTrue(
        analisis.rutas().stream().anyMatch((ruta) -> ruta.tipo() == TipoRuta.B2C),
        "Debe conservar la ruta B2C");
    assertTrue(
        analisis.rutas().stream().anyMatch((ruta) -> ruta.tipo() == TipoRuta.B2B),
        "Debe conservar la ruta B2B");
  }

  @Test
  void sumaLasUnidadesDeUnaFilaSinBultosASuRuta() {
    String csv =
        "RUTA,CLUSTER,CHUTE,TIPO,BULTOS,UNIDADES\n"
            + "3030,CL1,CHUTE 1,B2C,10,100\n"
            + "3030,CL1,CHUTE 1,B2C,0,50\n"
            + "3031,CL1,CHUTE 1,B2C,0,40\n";

    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(csv(csv));

    // 3031 no tiene bultos: no hay carga que balancear y se descarta.
    assertEquals(1, analisis.rutas().size());
    assertEquals("150.00", analisis.rutas().get(0).unidades().toPlainString());
  }

  @Test
  void aceptaElTipoConEspacioNoSeparable() {
    String csv = "RUTA,CLUSTER,CHUTE,TIPO,BULTOS\n3030,CL1,CHUTE 1,B2C\u00A0,5\n";

    assertEquals(TipoRuta.B2C, SimulacionExcel.analizar(csv(csv)).rutas().get(0).tipo());
  }

  @Test
  void rechazaUnTipoDistintoDeB2CYB2B() {
    String csv = "RUTA,CLUSTER,CHUTE,TIPO,BULTOS\n3030,CL1,CHUTE 1,B2X,5\n";

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> SimulacionExcel.analizar(csv(csv)));

    assertTrue(error.getMessage().contains("TIPO"), error.getMessage());
    assertTrue(error.getMessage().contains("B2C"), error.getMessage());
  }

  @Test
  void aceptaCsvConEncabezadosSinTildes() {
    String csv =
        "COD,RUTA,CLUSTER,CHUTE,TIPO,BULTOS,UNIDADES,LINEAS\n"
            + "1,3030,CL1,CHUTE 1,B2C,12,120,24\n"
            + "2,3031,CL1,CHUTE 1,B2B,8,80,16\n";
    MockMultipartFile archivo =
        new MockMultipartFile("archivo", "proyeccion.csv", "text/csv", csv.getBytes());

    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(archivo);

    assertEquals(2, analisis.rutas().size());
    assertEquals("20.00", analisis.totalBultos().toPlainString());
  }

  @Test
  void aceptaCsvExportadoPorExcelConBom() {
    String csv =
        "\uFEFFRUTA,CLUSTER,CHUTE,TIPO,BULTOS\n"
            + "3030,CL1,CHUTE 1,B2C,12\n";
    MockMultipartFile archivo =
        new MockMultipartFile("archivo", "proyeccion.csv", "text/csv", csv.getBytes());

    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(archivo);

    assertEquals(1, analisis.rutas().size());
    assertEquals("12.00", analisis.totalBultos().toPlainString());
  }

  @Test
  void noConfundeElSeparadorDeMilesConDecimales() throws Exception {
    byte[] contenido;
    try (XSSFWorkbook libro = new XSSFWorkbook();
        ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
      Sheet hoja = libro.createSheet("Proyección");
      Row cabecera = hoja.createRow(0);
      String[] columnas = {"COD.", "RUTA", "CLUSTER", "CHUTE", "TIPO", "BULTOS PROMEDIO"};
      for (int i = 0; i < columnas.length; i++) {
        cabecera.createCell(i).setCellValue(columnas[i]);
      }
      Row fila = hoja.createRow(1);
      fila.createCell(0).setCellValue(1);
      fila.createCell(1).setCellValue("3030");
      fila.createCell(2).setCellValue("CL1");
      fila.createCell(3).setCellValue("CHUTE 1");
      fila.createCell(4).setCellValue("B2C");
      var estilo = libro.createCellStyle();
      estilo.setDataFormat(libro.createDataFormat().getFormat("#,##0"));
      var bultos = fila.createCell(5);
      bultos.setCellValue(1500);
      bultos.setCellStyle(estilo);
      libro.write(salida);
      contenido = salida.toByteArray();
    }

    MockMultipartFile archivo =
        new MockMultipartFile(
            "archivo",
            "proyeccion.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            contenido);

    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(archivo);

    assertEquals("1500.00", analisis.rutas().get(0).bultos().toPlainString());
    assertEquals("1500.00", analisis.totalBultos().toPlainString());
  }

  @Test
  void interpretaMilesConComaEnCsvConPuntoYComa() {
    String csv =
        "RUTA;CLUSTER;CHUTE;TIPO;BULTOS\n"
            + "3030;CL1;CHUTE 1;B2C;1,500\n"
            + "3031;CL1;CHUTE 1;B2B;\"12,5\"\n";

    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(csv(csv));

    assertEquals("1500.00", analisis.rutas().get(0).bultos().toPlainString());
    assertEquals("12.50", analisis.rutas().get(1).bultos().toPlainString());
  }

  @Test
  void rechazaValoresNoNumericosIndicandoLaFila() {
    String csv = "RUTA,CLUSTER,CHUTE,TIPO,BULTOS\n3030,CL1,CHUTE 1,B2C,doce\n";

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> SimulacionExcel.analizar(csv(csv)));

    assertTrue(error.getMessage().contains("Fila 2"));
  }

  @Test
  void rechazaUnaRutaConDosClusters() {
    String csv =
        "RUTA,CLUSTER,CHUTE,TIPO,BULTOS\n"
            + "3030,CL1,CHUTE 1,B2C,5\n"
            + "3030,CL2,CHUTE 1,B2C,5\n";

    assertThrows(ConflictoException.class, () -> SimulacionExcel.analizar(csv(csv)));
  }

  @Test
  void cuentaCodsDistintosYAceptaComillasConSaltoDeLinea() {
    String csv =
        "COD,RUTA,CLUSTER,CHUTE,TIPO,BULTOS\n"
            + "A1,3030,\"CL\n1\",CHUTE 1,B2C,5\n"
            + "A1,3030,\"CL\n1\",CHUTE 1,B2C,5\n"
            + "A2,3030,\"CL\n1\",CHUTE 1,B2C,5\n";

    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(csv(csv));

    assertEquals(1, analisis.rutas().size());
    assertEquals(2, analisis.rutas().get(0).conteoCod());
    assertEquals("15.00", analisis.totalBultos().toPlainString());
  }

  @Test
  void ignoraColumnasMasAllaDelTope() throws Exception {
    byte[] contenido;
    try (XSSFWorkbook libro = new XSSFWorkbook();
        ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
      Sheet hoja = libro.createSheet("Proyección");
      Row cabecera = hoja.createRow(0);
      String[] columnas = {"RUTA", "CLUSTER", "CHUTE", "TIPO", "BULTOS"};
      for (int i = 0; i < columnas.length; i++) {
        cabecera.createCell(i).setCellValue(columnas[i]);
      }
      Row fila = hoja.createRow(1);
      fila.createCell(0).setCellValue("3030");
      fila.createCell(1).setCellValue("CL1");
      fila.createCell(2).setCellValue("CHUTE 1");
      fila.createCell(3).setCellValue("B2C");
      fila.createCell(4).setCellValue(7);
      fila.createCell(16_383).setCellValue("XFD");
      libro.write(salida);
      contenido = salida.toByteArray();
    }

    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(xlsx(contenido));

    assertEquals("7.00", analisis.totalBultos().toPlainString());
  }

  @Test
  void rechazaUnXlsxQueNoEsUnLibro() {
    MockMultipartFile falso =
        new MockMultipartFile("archivo", "proyeccion.xlsx", "application/octet-stream", "hola".getBytes());

    assertThrows(ConflictoException.class, () -> SimulacionExcel.analizar(falso));
  }

  private static MockMultipartFile csv(String contenido) {
    return new MockMultipartFile("archivo", "proyeccion.csv", "text/csv", contenido.getBytes());
  }

  private static MockMultipartFile xlsx(byte[] contenido) {
    return new MockMultipartFile(
        "archivo",
        "proyeccion.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        contenido);
  }

  private static void escribirFila(
      Sheet hoja,
      int indice,
      String cod,
      String ruta,
      String cluster,
      String chute,
      double bultos,
      double unidades,
      double lineas,
      String tipo) {
    Row fila = hoja.createRow(indice);
    fila.createCell(0).setCellValue(cod);
    fila.createCell(1).setCellValue(ruta);
    fila.createCell(2).setCellValue(cluster);
    fila.createCell(3).setCellValue(chute);
    fila.createCell(4).setCellValue(bultos);
    fila.createCell(5).setCellValue(unidades);
    fila.createCell(6).setCellValue(lineas);
    fila.createCell(7).setCellValue(tipo);
  }
}
