package oracle.simulacionsanta.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import oracle.simulacionsanta.response.ErrorFila;
import oracle.simulacionsanta.service.ExcelSantaAnita.ArchivoInvalidoException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class ExcelSantaAnitaTest {

  private static final String XLSX =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  // ------------------------------------------------------------------
  // .xlsx
  // ------------------------------------------------------------------

  @Test
  void xlsxConTituloEncabezadosConTildesYErroresPorFila() throws Exception {
    byte[] contenido;
    try (XSSFWorkbook libro = new XSSFWorkbook();
        ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
      Sheet hoja = libro.createSheet("Despacho");
      CellStyle entero = libro.createCellStyle();
      entero.setDataFormat(libro.createDataFormat().getFormat("0"));
      fila(hoja, 0, "Despacho Santa Anita · 17/11");
      // Fila 2 vacía; encabezado en la 3 con tildes, espacios, signos y una columna ignorada.
      fila(hoja, 2, " Rutas ", "Locales", "Clúster", "BULTOS", "Envío 1", "2° envío", "Tercer Envío");
      fila(hoja, 3, 2001.0, "Farmacia  Lima   Centro", "C2", 99.0, 10.0, 5.0, 0.0);
      fila(hoja, 4, "R-15", "Local A", "CL1", null, 12.0, null, 3.0);
      hoja.createRow(5); // vacía: se ignora
      fila(hoja, 6, "R-15", "LOCAL a", "1", null, 1.0, 1.0, 1.0);
      fila(hoja, 7, "R-16", "Local B", "CLUSTER 3", null, 12.5, 0.0, 0.0);
      // Con formato «0» Excel muestra 12.5 como «13»: igual debe leerse el valor real.
      hoja.getRow(7).getCell(4).setCellStyle(entero);
      fila(hoja, 8, "R-17", "Local C", "4", null, 1.0, 0.0, 0.0);
      fila(hoja, 9, "R-18", "Local D", "C3", null, 0.0, 0.0, 0.0);
      fila(hoja, 10, "R-19", "Local E", 3.0, null, "7", " 8 ", "");
      fila(hoja, 11, "R-20", "  ", "C1", null, 1.0, 0.0, 0.0);
      fila(hoja, 12, "R-21", "Local F", "C1", null, -2.0, 0.0, 5.0);
      libro.write(salida);
      contenido = salida.toByteArray();
    }

    ExcelSantaAnita.Resultado r =
        ExcelSantaAnita.leer(new MockMultipartFile("archivo", "despacho.xlsx", XLSX, contenido));

    assertEquals("despacho.xlsx", r.nombreArchivo());
    assertEquals(9, r.filasLeidas());
    assertEquals(3, r.filas().size());
    assertEquals(6, r.totalErrores());

    ExcelSantaAnita.Fila primera = r.filas().get(0);
    assertEquals(4, primera.orden());
    assertEquals("2001", primera.ruta());
    assertEquals("Farmacia Lima Centro", primera.local());
    assertEquals(2, primera.cluster());
    assertEquals(List.of(10, 5, 0), List.of(primera.envio1(), primera.envio2(), primera.envio3()));

    ExcelSantaAnita.Fila segunda = r.filas().get(1);
    assertEquals(5, segunda.orden());
    assertEquals(1, segunda.cluster());
    assertEquals(List.of(12, 0, 3), List.of(segunda.envio1(), segunda.envio2(), segunda.envio3()));

    ExcelSantaAnita.Fila tercera = r.filas().get(2);
    assertEquals(11, tercera.orden());
    assertEquals(3, tercera.cluster());
    assertEquals(List.of(7, 8, 0), List.of(tercera.envio1(), tercera.envio2(), tercera.envio3()));

    assertEquals(List.of(7, 8, 9, 10, 12, 13), r.errores().stream().map(ErrorFila::fila).toList());
    assertEquals(
        "El local «LOCAL a» ya está en la fila 5: una paleta lleva un solo local con todos sus"
            + " bultos.",
        mensaje(r, 7));
    assertTrue(mensaje(r, 8).contains("debe ser un número entero"), mensaje(r, 8));
    assertTrue(mensaje(r, 9).contains("CLUSTER «4» no es válido"), mensaje(r, 9));
    assertTrue(mensaje(r, 10).contains("mayor que 0"), mensaje(r, 10));
    assertEquals("Falta el LOCAL.", mensaje(r, 12));
    assertEquals("ENVÍO 1 no puede ser negativo.", mensaje(r, 13));
    assertEquals(
        List.of("E7", "E5", "E4", "E6", "E3", "E5"),
        r.errores().stream().map(ErrorFila::regla).toList());

    assertEquals(3, r.rutas());
    assertEquals(List.of(29, 13, 3), r.bultosEnvios());
    assertEquals(45, r.bultos());
    assertEquals(List.of(1, 1, 1), r.paletasCluster());
    // C1 (fila 5), C2 (fila 4) y C3 (fila 11) × envíos 1, 2 y 3.
    assertEquals(
        List.of(List.of(12, 0, 3), List.of(10, 5, 0), List.of(7, 8, 0)), r.bultosClusterEnvio());
  }

  @Test
  void unLocalVaEnUnaSolaFilaAunqueCambieLaRutaElClusterLasMayusculasOLasTildes() {
    String texto =
        "RUTA;LOCAL;CLUSTER;ENVIO1;ENVIO2;ENVIO3\n"
            + "R1;Botica Central Lima;1;1;0;0\n"
            + "R2;  BOTICA   CENTRAL  LIMA ;2;2;0;0\n"
            + "R1;bótica céntral límá;1;0;1;0\n"
            + "R3;Botica Central Callao;1;3;0;0\n"
            + "R4;Otro Local;C9;1;0;0\n"
            + "R5;otro local;2;1;0;0\n";

    ExcelSantaAnita.Resultado r = ExcelSantaAnita.leer(csv(texto));

    // La fila 6 tiene error (cluster), así que «Otro Local» se queda con la fila 7.
    assertEquals(List.of(2, 5, 7), r.filas().stream().map(ExcelSantaAnita.Fila::orden).toList());
    assertEquals(List.of(3, 4, 6), r.errores().stream().map(ErrorFila::fila).toList());
    assertEquals(List.of("E7", "E7", "E4"), r.errores().stream().map(ErrorFila::regla).toList());
    assertEquals(
        "El local «BOTICA CENTRAL LIMA» ya está en la fila 2: una paleta lleva un solo local con"
            + " todos sus bultos.",
        mensaje(r, 3));
    assertTrue(mensaje(r, 4).startsWith("El local «bótica céntral límá» ya está en la fila 2:"));
  }

  @Test
  void cadaErrorTraeSuCodigoDeRegla() {
    String texto =
        "RUTA;LOCAL;CLUSTER;ENVIO1;ENVIO2;ENVIO3\n"
            + ";Local 1;1;1;0;0\n"
            + "R".repeat(51) + ";Local 2;1;1;0;0\n"
            + "R3;;1;1;0;0\n"
            + "R4;" + "L".repeat(151) + ";1;1;0;0\n"
            + "R5;Local 5;;1;0;0\n"
            + "R6;Local 6;7;1;0;0\n"
            + "R7;Local 7;1;abc;0;0\n"
            + "R8;Local 8;1;0;-1;0\n"
            + "R9;Local 9;1;0;0;1,5\n"
            + "R10;Local 10;1;0;0;0\n"
            + "R11;Local 11;1;5;0;0\n"
            + "R12;local  11;3;1;0;0\n";

    ExcelSantaAnita.Resultado r = ExcelSantaAnita.leer(csv(texto));

    assertEquals(1, r.filas().size());
    assertEquals(
        List.of("E2", "E2", "E3", "E3", "E4", "E4", "E5", "E5", "E5", "E6", "E7"),
        r.errores().stream().map(ErrorFila::regla).toList());
    assertEquals(List.of(List.of(5, 0, 0), List.of(0, 0, 0), List.of(0, 0, 0)), r.bultosClusterEnvio());
  }

  @Test
  void xlsxSinNingunaFilaValidaResponde422ConLosErrores() throws Exception {
    byte[] contenido =
        xlsx(
            new Object[] {"RUTA", "LOCAL", "CLUSTER", "ENVIO 1", "ENVIO 2", "ENVIO 3"},
            new Object[] {"A", "L1", "C9", 1.0, 0.0, 0.0},
            new Object[] {"B", "L2", "C1", 0.0, 0.0, 0.0});

    ArchivoInvalidoException error =
        assertThrows(
            ArchivoInvalidoException.class,
            () -> ExcelSantaAnita.leer(new MockMultipartFile("archivo", "d.xlsx", XLSX, contenido)));

    assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.estado());
    assertTrue(error.getMessage().contains("ninguna fila válida (2 con error)"), error.getMessage());
    assertTrue(error.getMessage().contains("Fila 2:"), error.getMessage());
    assertEquals(List.of("fila 2", "fila 3"), List.copyOf(error.detalle().keySet()));
  }

  @Test
  void xlsxConColumnaFaltanteDiceCualesFaltan() throws Exception {
    byte[] contenido =
        xlsx(
            new Object[] {"Ruta", "Local", "Primer envío", "Envío 3"},
            new Object[] {"A", "L1", 1.0, 0.0});

    ArchivoInvalidoException error =
        assertThrows(
            ArchivoInvalidoException.class,
            () -> ExcelSantaAnita.leer(new MockMultipartFile("archivo", "d.xlsx", XLSX, contenido)));

    assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.estado());
    assertTrue(error.getMessage().startsWith("E1 · Faltan columnas obligatorias"), error.getMessage());
    assertTrue(
        error.getMessage().contains("(fila 1): CLUSTER, ENVÍO 2."), error.getMessage());
  }

  @Test
  void xlsxConFirmaQueNoEsDeExcelResponde400() {
    MockMultipartFile archivo =
        new MockMultipartFile("archivo", "d.xlsx", XLSX, "RUTA;LOCAL".getBytes(StandardCharsets.UTF_8));

    ArchivoInvalidoException error =
        assertThrows(ArchivoInvalidoException.class, () -> ExcelSantaAnita.leer(archivo));

    assertEquals(HttpStatus.BAD_REQUEST, error.estado());
  }

  // ------------------------------------------------------------------
  // .xls
  // ------------------------------------------------------------------

  @Test
  void xlsSeLeeIgualQueXlsx() throws Exception {
    byte[] contenido;
    try (HSSFWorkbook libro = new HSSFWorkbook();
        ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
      Sheet hoja = libro.createSheet("Despacho");
      fila(hoja, 0, "RUTAS", "LOCAL", "CLUSTER", "1 ENVIO", "2 ENVIO", "3 ENVIO");
      fila(hoja, 1, 3030.0, "Local Uno", "c 2", 4.0, 0.0, 1.0);
      libro.write(salida);
      contenido = salida.toByteArray();
    }

    ExcelSantaAnita.Resultado r =
        ExcelSantaAnita.leer(
            new MockMultipartFile("archivo", "despacho.XLS", "application/vnd.ms-excel", contenido));

    assertEquals(1, r.filas().size());
    ExcelSantaAnita.Fila fila = r.filas().get(0);
    assertEquals("3030", fila.ruta());
    assertEquals(2, fila.cluster());
    assertEquals(2, fila.orden());
    assertEquals(List.of(4, 0, 1), r.bultosEnvios());
  }

  // ------------------------------------------------------------------
  // .csv
  // ------------------------------------------------------------------

  @Test
  void csvConPuntoYComaEnWindows1252ComillasYFilasVacias() {
    String texto =
        "RUTA;LOCAL;CLUSTER;ENVÍO 1;ENVÍO 2;ENVÍO 3\r\n"
            + "2001;Botica Ñaña;C1;4;0;0\r\n"
            + ";;;;;\r\n"
            + "\r\n"
            + "2002;\"Local; con \"\"punto y coma\"\"\";2;0;3,0;\r\n";
    MockMultipartFile archivo =
        new MockMultipartFile(
            "archivo", "despacho.csv", "text/csv", texto.getBytes(Charset.forName("windows-1252")));

    ExcelSantaAnita.Resultado r = ExcelSantaAnita.leer(archivo);

    assertEquals(2, r.filasLeidas());
    assertEquals(0, r.totalErrores());
    assertEquals("Botica Ñaña", r.filas().get(0).local());
    assertEquals(2, r.filas().get(0).orden());
    ExcelSantaAnita.Fila segunda = r.filas().get(1);
    assertEquals("Local; con \"punto y coma\"", segunda.local());
    assertEquals(5, segunda.orden());
    assertEquals(2, segunda.cluster());
    assertEquals(List.of(0, 3, 0), List.of(segunda.envio1(), segunda.envio2(), segunda.envio3()));
    assertEquals(List.of(1, 1, 0), r.paletasCluster());
  }

  @Test
  void csvConComaBomYDuplicadosSinDistinguirMayusculas() {
    String texto =
        "﻿Nro,Ruta,Local,Cluster,Envio1,Envio2,Envio3,Bultos\n"
            + "1,A1,\"Farmacia, Centro\",cl2,1,2,3,6\n"
            + "2,a1,FARMACIA, CENTRO,1,1,1,1,3\n"
            + "3,a1,\"farmacia, centro\",1,1,1,1,3\n"
            + "4,A2,Otro,2,1.5,0,0,1\n";
    MockMultipartFile archivo =
        new MockMultipartFile(
            "archivo", "d.csv", "text/csv", texto.getBytes(StandardCharsets.UTF_8));

    ExcelSantaAnita.Resultado r = ExcelSantaAnita.leer(archivo);

    assertEquals(1, r.filas().size());
    assertEquals("Farmacia, Centro", r.filas().get(0).local());
    assertEquals(2, r.filas().get(0).cluster());
    // Fila 3: sin comillas «FARMACIA, CENTRO» corre las columnas (LOCAL = FARMACIA, CLUSTER = CENTRO).
    assertEquals(List.of(3, 4, 5), r.errores().stream().map(ErrorFila::fila).toList());
    assertTrue(mensaje(r, 3).contains("CLUSTER «CENTRO»"), mensaje(r, 3));
    assertTrue(mensaje(r, 4).contains("ya está en la fila 2"), mensaje(r, 4));
    assertTrue(mensaje(r, 5).contains("debe ser un número entero"), mensaje(r, 5));
  }

  @Test
  void csvSinEncabezadoEnLasPrimeras30Filas() {
    StringBuilder texto = new StringBuilder();
    for (int i = 0; i < 30; i++) {
      texto.append("nota ").append(i).append('\n');
    }
    texto.append("RUTA,LOCAL,CLUSTER,ENVIO1,ENVIO2,ENVIO3\nA,B,1,1,0,0\n");

    ArchivoInvalidoException error =
        assertThrows(ArchivoInvalidoException.class, () -> ExcelSantaAnita.leer(csv(texto.toString())));

    assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.estado());
    assertTrue(error.getMessage().contains("No se encontró el encabezado"), error.getMessage());
  }

  @Test
  void csvConMasDe5000FilasResponde413() {
    StringBuilder texto = new StringBuilder("RUTA,LOCAL,CLUSTER,ENVIO1,ENVIO2,ENVIO3\n");
    for (int i = 0; i <= ExcelSantaAnita.MAX_FILAS; i++) {
      texto.append("R").append(i).append(",L,1,1,0,0\n");
    }

    ArchivoInvalidoException error =
        assertThrows(ArchivoInvalidoException.class, () -> ExcelSantaAnita.leer(csv(texto.toString())));

    assertEquals(HttpStatus.CONTENT_TOO_LARGE, error.estado());
  }

  @Test
  void csvSoloConEncabezadoResponde422() {
    ArchivoInvalidoException error =
        assertThrows(
            ArchivoInvalidoException.class,
            () -> ExcelSantaAnita.leer(csv("RUTA,LOCAL,CLUSTER,ENVIO1,ENVIO2,ENVIO3\n,,,,,\n")));

    assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.estado());
    assertTrue(error.getMessage().contains("no tiene filas de datos"), error.getMessage());
  }

  // ------------------------------------------------------------------
  // Archivo
  // ------------------------------------------------------------------

  @Test
  void archivoMayorA10MbResponde413() {
    MockMultipartFile archivo =
        new MockMultipartFile(
            "archivo", "d.csv", "text/csv", new byte[(int) ExcelSantaAnita.MAX_BYTES + 1]);

    ArchivoInvalidoException error =
        assertThrows(ArchivoInvalidoException.class, () -> ExcelSantaAnita.verificar(archivo));

    assertEquals(HttpStatus.CONTENT_TOO_LARGE, error.estado());
  }

  @Test
  void extensionNoSoportadaResponde415YVacioResponde400() {
    ArchivoInvalidoException pdf =
        assertThrows(
            ArchivoInvalidoException.class,
            () ->
                ExcelSantaAnita.leer(
                    new MockMultipartFile("archivo", "d.pdf", "application/pdf", new byte[] {1})));
    assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, pdf.estado());

    ArchivoInvalidoException vacio =
        assertThrows(
            ArchivoInvalidoException.class,
            () -> ExcelSantaAnita.leer(new MockMultipartFile("archivo", "d.csv", "text/csv", new byte[0])));
    assertEquals(HttpStatus.BAD_REQUEST, vacio.estado());
  }

  // ------------------------------------------------------------------
  // Reglas de valores
  // ------------------------------------------------------------------

  @Test
  void clavesDeEncabezadoYValores() {
    assertEquals("ENVIO1", ExcelSantaAnita.clave(" Envío  1 "));
    assertEquals("2ENVIO", ExcelSantaAnita.clave("2° envío"));
    assertEquals("PRIMERENVIO", ExcelSantaAnita.clave("Primer-envío"));
    assertEquals("2001", ExcelSantaAnita.ruta("2001.0"));
    assertEquals("2001.5", ExcelSantaAnita.ruta("2001.5"));
    assertEquals(1, ExcelSantaAnita.cluster("C1"));
    assertEquals(2, ExcelSantaAnita.cluster("CL 2"));
    assertEquals(3, ExcelSantaAnita.cluster("Cluster 3"));
    assertEquals(3, ExcelSantaAnita.cluster("3.0"));
    assertNull(ExcelSantaAnita.cluster("C4"));
    assertNull(ExcelSantaAnita.cluster("0"));
    assertEquals(0, ExcelSantaAnita.envio("", "ENVÍO 1"));
    assertEquals(12, ExcelSantaAnita.envio("12,00", "ENVÍO 1"));
    assertEquals(99_999, ExcelSantaAnita.envio("99999", "ENVÍO 1"));
    assertEquals("12.5", ExcelSantaAnita.numero(12.5));
    assertEquals("2001", ExcelSantaAnita.numero(2001.0));
  }

  // ------------------------------------------------------------------

  private static String mensaje(ExcelSantaAnita.Resultado r, int fila) {
    return r.errores().stream()
        .filter((e) -> e.fila() == fila)
        .map(ErrorFila::mensaje)
        .findFirst()
        .orElse("(sin error en la fila " + fila + ")");
  }

  private static MockMultipartFile csv(String texto) {
    return new MockMultipartFile("archivo", "d.csv", "text/csv", texto.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] xlsx(Object[]... filas) throws Exception {
    try (XSSFWorkbook libro = new XSSFWorkbook();
        ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
      Sheet hoja = libro.createSheet("Hoja1");
      for (int i = 0; i < filas.length; i++) {
        fila(hoja, i, filas[i]);
      }
      libro.write(salida);
      return salida.toByteArray();
    }
  }

  /** Celdas de una fila (índice 0-based): String → texto, Double → número, null → sin celda. */
  private static void fila(Sheet hoja, int indice, Object... valores) {
    Row fila = hoja.createRow(indice);
    for (int c = 0; c < valores.length; c++) {
      Object valor = valores[c];
      if (valor instanceof String texto) {
        fila.createCell(c).setCellValue(texto);
      } else if (valor instanceof Double numero) {
        fila.createCell(c).setCellValue(numero);
      }
    }
  }
}
