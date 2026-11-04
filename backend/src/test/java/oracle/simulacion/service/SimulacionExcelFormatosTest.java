package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import oracle.simulacion.service.SimulacionExcel.ArchivoInvalidoException;
import oracle.simulacion.service.SimulacionExcel.Limites;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

/** Formatos de entrada, separadores numéricos, cabecera, codificación y topes. */
class SimulacionExcelFormatosTest {

  private static final String XLSX =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  // ------------------------------------------------------------------
  // Números
  // ------------------------------------------------------------------

  @ParameterizedTest(name = "\"{0}\" -> {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "1.500|1500.00", // antes 1.50: un único punto con 3 dígitos es de miles
        "1,500|1500.00",
        "1.500,25|1500.25",
        "1,500.25|1500.25",
        "1.500.000|1500000.00",
        "1,500,000|1500000.00",
        "1.500.000,5|1500000.50",
        "12,5|12.50",
        "12.5|12.50",
        "12.50|12.50",
        "0,500|0.50", // parte entera 0: nunca es separador de miles
        "0.125|0.13",
        "1234.567|1234.57", // parte entera de 4 dígitos: decimal
        "1 500|1500.00",
        "1 500,75|1500.75",
        "7|7.00",
        ".5|0.50",
        "9999999999.99|9999999999.99"
      })
  void interpretaSeparadores(String texto, String esperado) {
    assertEquals(esperado, SimulacionExcel.decimal(texto, 2, "BULTOS").toPlainString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.2.3", "1,5,0", "-5", "doce", "1.5,00", "1,5.00", ".", "1,2345,678", "1e3",
      "10000000000"})
  void rechazaNumerosInvalidosCon422(String texto) {
    ArchivoInvalidoException error =
        assertThrows(
            ArchivoInvalidoException.class, () -> SimulacionExcel.decimal(texto, 9, "BULTOS"));
    assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.estado());
    assertTrue(error.getMessage().contains("Fila 9"), error.getMessage());
  }

  @Test
  void celdasNumericasRedondeanComoBigDecimalHalfUp() {
    assertEquals(150_000, SimulacionExcel.centimos(1500.0, 2, "BULTOS"));
    assertEquals(29, SimulacionExcel.centimos(0.29, 2, "BULTOS"));
    assertEquals(101, SimulacionExcel.centimos(1.005, 2, "BULTOS"));
    assertEquals(123, SimulacionExcel.centimos(1.234, 2, "BULTOS"));
    assertEquals(13, SimulacionExcel.centimos(0.125, 2, "BULTOS"));
    assertThrows(ArchivoInvalidoException.class, () -> SimulacionExcel.centimos(-1.0, 2, "BULTOS"));
    assertThrows(
        ArchivoInvalidoException.class, () -> SimulacionExcel.centimos(1e13, 2, "BULTOS"));
  }

  @Test
  void numeroDeExcelConTresDecimalesNoSeConfundeConMiles() throws Exception {
    byte[] libro =
        xlsx(
            new Object[] {"RUTA", "CLUSTER", "CHUTE", "TIPO", "BULTOS", "UNIDADES"},
            new Object[] {"3030", "CL1", "CHUTE 1", "B2C", 1.234, "1.500"});

    SimulacionExcel.FilaRuta ruta = SimulacionExcel.analizar(archivoXlsx(libro)).rutas().get(0);

    assertEquals("1.23", ruta.bultos().toPlainString()); // celda numérica: 1.234, no 1234
    assertEquals("1500.00", ruta.unidades().toPlainString()); // celda de texto "1.500"
  }

  @Test
  void codigoDeRutaNumericoSeLeeSinDecimales() throws Exception {
    byte[] libro =
        xlsx(new Object[] {"RUTA", "CLUSTER", "CHUTE", "TIPO", "BULTOS"}, new Object[] {3030.0, "CL1", 7.0, "B2C", 4.0});

    SimulacionExcel.FilaRuta ruta = SimulacionExcel.analizar(archivoXlsx(libro)).rutas().get(0);

    assertEquals("3030", ruta.codigoRuta());
    assertEquals("7", ruta.chute());
  }

  // ------------------------------------------------------------------
  // Codificación y separador del CSV
  // ------------------------------------------------------------------

  @Test
  void csvWindows1252ConservaLaColumnaLineas() {
    String csv = "RUTA;CLUSTER;CHUTE;TIPO;BULTOS;LÍNEAS PROMEDIO\r\n3030;CL1;CHUTE 1;B2C;5;7,5\r\n";

    SimulacionExcel.Analisis analisis =
        SimulacionExcel.analizar(csv(csv.getBytes(Charset.forName("windows-1252"))));

    assertEquals("7.50", analisis.rutas().get(0).lineas().toPlainString());
  }

  @Test
  void csvWindows1252ConTildesEnLosDatos() {
    String csv = "RUTA,CLUSTER,CHUTE,TIPO,BULTOS\n3030,CAÑETE,CHUTE 1,B2C,5\n";

    SimulacionExcel.Analisis analisis =
        SimulacionExcel.analizar(csv(csv.getBytes(Charset.forName("windows-1252"))));

    assertEquals("CAÑETE", analisis.rutas().get(0).cluster());
  }

  @Test
  void csvUtf8SinBomConTildes() {
    String csv = "RUTA,CLUSTER,CHUTE,TIPO,BULTOS,LÍNEAS\n3030,CAÑETE,CHUTE 1,B2C,5,3\n";

    SimulacionExcel.Analisis analisis =
        SimulacionExcel.analizar(csv(csv.getBytes(StandardCharsets.UTF_8)));

    assertEquals("CAÑETE", analisis.rutas().get(0).cluster());
    assertEquals("3.00", analisis.rutas().get(0).lineas().toPlainString());
  }

  @Test
  void csvUtf16ConBomYTabuladores() {
    String csv = "﻿RUTA\tCLUSTER\tCHUTE\tTIPO\tBULTOS\r\n3030\tCL1\tCHUTE 1\tB2C\t12\r\n";

    SimulacionExcel.Analisis analisis =
        SimulacionExcel.analizar(csv(csv.getBytes(StandardCharsets.UTF_16LE)));

    assertEquals("12.00", analisis.totalBultos().toPlainString());
  }

  @Test
  void cabeceraDebajoDeUnTituloEnCsvConPuntoYComa() {
    String csv =
        "Proyección semana 23\n\n"
            + "RUTA;CLUSTER;CHUTE;TIPO;BULTOS\n"
            + "3030;CL1;CHUTE 1;B2C;1,5\n";

    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(csv(csv.getBytes(StandardCharsets.UTF_8)));

    assertEquals("1.50", analisis.totalBultos().toPlainString());
  }

  @Test
  void cabeceraMayorA64KbNoFalla() {
    StringBuilder csv = new StringBuilder("RUTA,CLUSTER,CHUTE,TIPO,BULTOS");
    for (int i = 0; i < 9_000; i++) {
      csv.append(",EXTRA").append(i);
    }
    csv.append("\n3030,CL1,CHUTE 1,B2C,5\n");
    assertTrue(csv.length() > 64 * 1024);

    SimulacionExcel.Analisis analisis =
        SimulacionExcel.analizar(csv(csv.toString().getBytes(StandardCharsets.UTF_8)));

    assertEquals("5.00", analisis.totalBultos().toPlainString());
  }

  // ------------------------------------------------------------------
  // Cabecera
  // ------------------------------------------------------------------

  @Test
  void cabeceraEnLaTerceraFilaDeUnXlsx() throws Exception {
    byte[] libro =
        xlsx(
            new Object[] {"Proyección de rutas"},
            new Object[] {},
            new Object[] {"RUTA", "CLUSTER", "CHUTE", "TIPO", "BULTOS"},
            new Object[] {"3030", "CL1", "CHUTE 1", "B2C", 9.0});

    assertEquals("9.00", SimulacionExcel.analizar(archivoXlsx(libro)).totalBultos().toPlainString());
  }

  @Test
  void indicaLaColumnaQueFaltaEnLaCabecera() {
    String csv = "RUTA,CLUSTER,CHUTE,TIPO\n3030,CL1,CHUTE 1,B2C\n";

    ArchivoInvalidoException error =
        assertThrows(
            ArchivoInvalidoException.class,
            () -> SimulacionExcel.analizar(csv(csv.getBytes(StandardCharsets.UTF_8))));

    assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.estado());
    assertTrue(error.getMessage().contains("BULTOS"), error.getMessage());
  }

  @Test
  void sinCabeceraEnLasPrimerasFilas() {
    StringBuilder csv = new StringBuilder();
    for (int i = 0; i < SimulacionExcel.FILAS_BUSCAR_CABECERA + 5; i++) {
      csv.append("nota ").append(i).append('\n');
    }

    ArchivoInvalidoException error =
        assertThrows(
            ArchivoInvalidoException.class,
            () -> SimulacionExcel.analizar(csv(csv.toString().getBytes(StandardCharsets.UTF_8))));

    assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.estado());
    assertTrue(error.getMessage().contains("No se encontró la cabecera"), error.getMessage());
  }

  // ------------------------------------------------------------------
  // Nombres
  // ------------------------------------------------------------------

  @Test
  void normalizaEspaciosYComparaSinTildes() {
    assertEquals("CHUTE 1", SimulacionExcel.nombre("  CHUTE  \t1 "));
    assertEquals("Chuté 1", SimulacionExcel.nombre("Chuté   1"));
    assertEquals("CHUTE 1", SimulacionExcel.clave("chuté  1"));
    assertEquals("R3030", SimulacionExcel.clave("r3030"));
    assertEquals("COD.", SimulacionExcel.clave("﻿Cod."));
  }

  @Test
  void rutasConEspaciosDistintosSonLaMismaYSeGuardaElNombreCanonico() {
    String csv = "RUTA,CLUSTER,CHUTE,TIPO,BULTOS\n3030,CL  1,CHUTE  1,B2C,5\n3030,cl 1,Chute 1,B2C,5\n";

    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(csv(csv.getBytes(StandardCharsets.UTF_8)));

    assertEquals(1, analisis.rutas().size());
    assertEquals("CHUTE 1", analisis.rutas().get(0).chute());
    assertEquals("CL 1", analisis.rutas().get(0).cluster());
  }

  // ------------------------------------------------------------------
  // Topes y errores (estado HTTP y mensajes sin detalles internos)
  // ------------------------------------------------------------------

  @Test
  void superarElMaximoDeFilasEs413() {
    String csv = "RUTA,CLUSTER,CHUTE,TIPO,BULTOS\n1,A,B,B2C,1\n2,A,B,B2C,1\n3,A,B,B2C,1\n";
    Limites limites = new Limites(2, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

    ArchivoInvalidoException error =
        assertThrows(
            ArchivoInvalidoException.class,
            () -> SimulacionExcel.analizar(csv(csv.getBytes(StandardCharsets.UTF_8)), limites));

    assertEquals(HttpStatus.CONTENT_TOO_LARGE, error.estado());
  }

  @Test
  void lasFilasVaciasNoCuentanParaElTope() throws Exception {
    byte[] libro =
        xlsx(
            new Object[] {"RUTA", "CLUSTER", "CHUTE", "TIPO", "BULTOS"},
            new Object[] {"1", "A", "B", "B2C", 1.0},
            new Object[] {""},
            new Object[] {""},
            new Object[] {"2", "A", "B", "B2C", 1.0});
    Limites limites = new Limites(2, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

    assertEquals(
        "2.00", SimulacionExcel.analizar(archivoXlsx(libro), limites).totalBultos().toPlainString());
  }

  @Test
  void sharedStringsMayorAlTopeEs413() throws Exception {
    Object[][] filas = new Object[201][];
    filas[0] = new Object[] {"RUTA", "CLUSTER", "CHUTE", "TIPO", "BULTOS"};
    for (int i = 1; i < filas.length; i++) {
      filas[i] = new Object[] {"RUTA-" + i, "CLUSTER-" + i, "CHUTE-" + i, "B2C", 1.0};
    }
    Limites limites = new Limites(1_000, Long.MAX_VALUE, Long.MAX_VALUE, 2_048);

    ArchivoInvalidoException error =
        assertThrows(
            ArchivoInvalidoException.class,
            () -> SimulacionExcel.analizar(archivoXlsx(xlsx(filas)), limites));

    assertEquals(HttpStatus.CONTENT_TOO_LARGE, error.estado());
    assertTrue(error.getMessage().contains("textos"), error.getMessage());
  }

  @Test
  void hojaMayorAlTopeEs413() throws Exception {
    Object[][] filas = new Object[501][];
    filas[0] = new Object[] {"RUTA", "CLUSTER", "CHUTE", "TIPO", "BULTOS"};
    for (int i = 1; i < filas.length; i++) {
      filas[i] = new Object[] {"R", "C", "H", "B2C", (double) i};
    }
    Limites limites = new Limites(1_000, Long.MAX_VALUE, 4_096, Long.MAX_VALUE);

    ArchivoInvalidoException error =
        assertThrows(
            ArchivoInvalidoException.class,
            () -> SimulacionExcel.analizar(archivoXlsx(xlsx(filas)), limites));

    assertEquals(HttpStatus.CONTENT_TOO_LARGE, error.estado());
  }

  @Test
  void archivoMayorAlTopeEs413() {
    Limites limites = new Limites(10, 10, Long.MAX_VALUE, Long.MAX_VALUE);

    ArchivoInvalidoException error =
        assertThrows(
            ArchivoInvalidoException.class,
            () ->
                SimulacionExcel.analizar(
                    csv("RUTA,CLUSTER,CHUTE,TIPO,BULTOS\n".getBytes(StandardCharsets.UTF_8)), limites));

    assertEquals(HttpStatus.CONTENT_TOO_LARGE, error.estado());
  }

  @Test
  void extensionNoSoportadaEs415() {
    ArchivoInvalidoException error =
        assertThrows(
            ArchivoInvalidoException.class,
            () ->
                SimulacionExcel.analizar(
                    new MockMultipartFile("archivo", "datos.ods", "application/octet-stream", new byte[] {1})));

    assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, error.estado());
  }

  @Test
  void xlsxDanadoEs400() {
    byte[] zipRoto = {0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4, 5, 6, 7, 8};

    ArchivoInvalidoException error =
        assertThrows(ArchivoInvalidoException.class, () -> SimulacionExcel.analizar(archivoXlsx(zipRoto)));

    assertEquals(HttpStatus.BAD_REQUEST, error.estado());
  }

  @Test
  void csvBinarioEs400() {
    ArchivoInvalidoException error =
        assertThrows(
            ArchivoInvalidoException.class,
            () -> SimulacionExcel.analizar(csv(new byte[] {'R', 0, 'U', 0})));

    assertEquals(HttpStatus.BAD_REQUEST, error.estado());
  }

  @Test
  void unaIOExceptionNoFiltraDetallesAlCliente() {
    byte[] contenido = "RUTA,CLUSTER,CHUTE,TIPO,BULTOS\n3030,CL1,CHUTE 1,B2C,5\n".getBytes(StandardCharsets.UTF_8);
    MockMultipartFile roto =
        new MockMultipartFile("archivo", "proyeccion.csv", "text/csv", contenido) {
          private int aperturas;

          @Override
          public InputStream getInputStream() throws IOException {
            if (++aperturas > 1) {
              throw new IOException("/var/tmp/secreto/upload_123.tmp (No such file)");
            }
            return super.getInputStream();
          }
        };

    ArchivoInvalidoException error =
        assertThrows(ArchivoInvalidoException.class, () -> SimulacionExcel.analizar(roto));

    assertEquals(HttpStatus.BAD_REQUEST, error.estado());
    assertFalse(error.getMessage().contains("secreto"), error.getMessage());
    assertFalse(error.getMessage().contains("tmp"), error.getMessage());
  }

  @Test
  void elErrorSigueSiendoUnConflictoParaElManejadorGlobal() {
    // Fuera del endpoint de carga, ArchivoInvalidoException se maneja como ConflictoException (409).
    assertTrue(
        oracle.common.exception.ConflictoException.class.isAssignableFrom(ArchivoInvalidoException.class));
  }

  // ------------------------------------------------------------------
  // Utilidades
  // ------------------------------------------------------------------

  private static MockMultipartFile csv(byte[] contenido) {
    return new MockMultipartFile("archivo", "proyeccion.csv", "text/csv", contenido);
  }

  private static MockMultipartFile archivoXlsx(byte[] contenido) {
    return new MockMultipartFile("archivo", "proyeccion.xlsx", XLSX, contenido);
  }

  /** Libro con una fila por arreglo; String → texto, Number → celda numérica. */
  private static byte[] xlsx(Object[]... filas) throws IOException {
    try (XSSFWorkbook libro = new XSSFWorkbook();
        ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
      Sheet hoja = libro.createSheet("Proyección");
      for (int f = 0; f < filas.length; f++) {
        Row fila = hoja.createRow(f);
        for (int c = 0; c < filas[f].length; c++) {
          Object valor = filas[f][c];
          if (valor instanceof Number numero) {
            fila.createCell(c).setCellValue(numero.doubleValue());
          } else {
            fila.createCell(c).setCellValue((String) valor);
          }
        }
      }
      libro.write(salida);
      return salida.toByteArray();
    }
  }
}
