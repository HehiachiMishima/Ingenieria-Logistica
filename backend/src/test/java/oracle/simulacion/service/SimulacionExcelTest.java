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
        "TIPO",
        "GRUPO",
        "FRECUENCIA"
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
        "RUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS\n"
            + "3030,CL1,CHUTE 1,A,L-M-X-J-V-S,B2C,12\n"
            + "3030,CL1,CHUTE 1,A,L-M-X-J-V-S,B2B,8\n";

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
        "RUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS,UNIDADES\n"
            + "3030,CL1,CHUTE 1,A,L-M-X-J-V-S,B2C,10,100\n"
            + "3030,CL1,CHUTE 1,A,L-M-X-J-V-S,B2C,0,50\n"
            + "3031,CL1,CHUTE 1,A,L-M-X-J-V-S,B2C,0,40\n";

    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(csv(csv));

    // 3031 no tiene bultos: no hay carga que balancear y se descarta.
    assertEquals(1, analisis.rutas().size());
    assertEquals("150.00", analisis.rutas().get(0).unidades().toPlainString());
  }

  @Test
  void aceptaElTipoConEspacioNoSeparable() {
    String csv = "RUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS\n3030,CL1,CHUTE 1,A,L-M-X-J-V-S,B2C\u00A0,5\n";

    assertEquals(TipoRuta.B2C, SimulacionExcel.analizar(csv(csv)).rutas().get(0).tipo());
  }

  @Test
  void rechazaUnTipoDistintoDeB2CYB2B() {
    String csv = "RUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS\n3030,CL1,CHUTE 1,A,L-M-X-J-V-S,B2X,5\n";

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> SimulacionExcel.analizar(csv(csv)));

    assertTrue(error.getMessage().contains("TIPO"), error.getMessage());
    assertTrue(error.getMessage().contains("B2C"), error.getMessage());
  }

  @Test
  void aceptaCsvConEncabezadosSinTildes() {
    String csv =
        "COD,RUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS,UNIDADES,LINEAS\n"
            + "1,3030,CL1,CHUTE 1,A,L-M-X-J-V-S,B2C,12,120,24\n"
            + "2,3031,CL1,CHUTE 1,A,L-M-X-J-V-S,B2B,8,80,16\n";
    MockMultipartFile archivo =
        new MockMultipartFile("archivo", "proyeccion.csv", "text/csv", csv.getBytes());

    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(archivo);

    assertEquals(2, analisis.rutas().size());
    assertEquals("20.00", analisis.totalBultos().toPlainString());
  }

  @Test
  void aceptaCsvExportadoPorExcelConBom() {
    String csv =
        "\uFEFFRUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS\n"
            + "3030,CL1,CHUTE 1,A,L-M-X-J-V-S,B2C,12\n";
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
      String[] columnas = {"COD.", "RUTA", "CLUSTER", "CHUTE", "TIPO", "BULTOS PROMEDIO", "GRUPO", "FRECUENCIA"};
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
      fila.createCell(6).setCellValue("A");
      fila.createCell(7).setCellValue("L-X-V");
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
        "RUTA;CLUSTER;CHUTE;GRUPO;FRECUENCIA;TIPO;BULTOS\n"
            + "3030;CL1;CHUTE 1;A;L-M-X-J-V-S;B2C;1,500\n"
            + "3031;CL1;CHUTE 1;A;L-M-X-J-V-S;B2B;\"12,5\"\n";

    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(csv(csv));

    assertEquals("1500.00", analisis.rutas().get(0).bultos().toPlainString());
    assertEquals("12.50", analisis.rutas().get(1).bultos().toPlainString());
  }

  @Test
  void rechazaValoresNoNumericosIndicandoLaFila() {
    String csv = "RUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS\n3030,CL1,CHUTE 1,A,L-M-X-J-V-S,B2C,doce\n";

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> SimulacionExcel.analizar(csv(csv)));

    assertTrue(error.getMessage().contains("Fila 2"));
  }

  @Test
  void rechazaUnaFilaSinGrupo() {
    String csv = "RUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS\n3030,CL1,CHUTE 1, ,L-M-X-J-V-S,B2C,5\n";

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> SimulacionExcel.analizar(csv(csv)));

    assertTrue(error.getMessage().contains("no tiene GRUPO"), error.getMessage());
  }

  @Test
  void leeLasTresFrecuenciasConCualquierSeparador() {
    String csv =
        "RUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS\n"
            + "3030,CL1,CHUTE 1,A,L-M-X-J-V-S,B2C,5\n"
            + "3031,CL1,CHUTE 1,A,l x v,B2C,5\n"
            + "3032,CL1,CHUTE 1,A,\"M, J, S\",B2C,5\n"
            + "3033,CL1,CHUTE 1,A,diaria,B2C,5\n"
            + "3034,CL1,CHUTE 1,A,MJS,B2C,5\n";

    java.util.Map<String, String> frecuencias = new java.util.HashMap<>();
    SimulacionExcel.analizar(csv(csv)).rutas()
        .forEach((r) -> frecuencias.put(r.codigoRuta(), r.frecuencia()));

    assertEquals("DIARIA", frecuencias.get("3030"));
    assertEquals("LXV", frecuencias.get("3031"));
    assertEquals("MJS", frecuencias.get("3032"));
    assertEquals("DIARIA", frecuencias.get("3033"));
    assertEquals("MJS", frecuencias.get("3034"));
  }

  @Test
  void rechazaUnaFrecuenciaConLetrasQueNoSonDias() {
    String csv =
        "RUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS\n3030,CL1,CHUTE 1,A,L-Z,B2C,5\n";

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> SimulacionExcel.analizar(csv(csv)));

    assertTrue(error.getMessage().contains("FRECUENCIA"), error.getMessage());
  }

  @Test
  void clasificaLasFrecuenciasParcialesPorSusDias() {
    String csv =
        "RUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS\n"
            + "3015,CL4,CHUTE 15,1,L-X,B2C,5\n"
            + "3035,CL3,CHUTE 29,2,X-V,B2C,5\n"
            + "3036,CL3,CHUTE 29,2,V,B2C,5\n"
            + "3040,CL3,CHUTE 29,2,j-m,B2C,5\n"
            + "3041,CL3,CHUTE 29,2,L-M,B2C,5\n";

    java.util.Map<String, SimulacionExcel.FilaRuta> rutas = new java.util.HashMap<>();
    SimulacionExcel.analizar(csv(csv)).rutas().forEach((r) -> rutas.put(r.codigoRuta(), r));

    // Solo días de L-X-V: balanceo L-X-V, con sus días tal cual.
    assertEquals("LXV", rutas.get("3015").frecuencia());
    assertEquals("L-X", rutas.get("3015").dias());
    assertEquals("LXV", rutas.get("3035").frecuencia());
    assertEquals("X-V", rutas.get("3035").dias());
    assertEquals("LXV", rutas.get("3036").frecuencia());
    assertEquals("V", rutas.get("3036").dias());
    // Solo días de M-J-S (en cualquier orden): balanceo M-J-S.
    assertEquals("MJS", rutas.get("3040").frecuencia());
    assertEquals("M-J", rutas.get("3040").dias());
    // Días de los dos: aparece en los dos balanceos, como una diaria.
    assertEquals("DIARIA", rutas.get("3041").frecuencia());
    assertEquals("L-M", rutas.get("3041").dias());
  }

  @Test
  void rechazaUnaRutaConDosFrecuencias() {
    String csv =
        "RUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS\n"
            + "3030,CL1,CHUTE 1,A,L-X-V,B2C,5\n"
            + "3030,CL1,CHUTE 1,A,M-J-S,B2C,5\n";

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> SimulacionExcel.analizar(csv(csv)));

    assertTrue(error.getMessage().contains("FRECUENCIA"), error.getMessage());
  }

  @Test
  void rechazaUnArchivoSinLaColumnaGrupo() {
    String csv = "RUTA,CLUSTER,CHUTE,TIPO,BULTOS\n3030,CL1,CHUTE 1,B2C,5\n";

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> SimulacionExcel.analizar(csv(csv)));

    assertTrue(error.getMessage().contains("GRUPO"), error.getMessage());
  }

  @Test
  void rechazaUnaRutaConDosGrupos() {
    String csv =
        "RUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS\n"
            + "3030,CL1,CHUTE 1,A,L-M-X-J-V-S,B2C,5\n"
            + "3030,CL1,CHUTE 1,B,L-M-X-J-V-S,B2C,5\n";

    ConflictoException error =
        assertThrows(ConflictoException.class, () -> SimulacionExcel.analizar(csv(csv)));

    assertTrue(error.getMessage().contains("GRUPO"), error.getMessage());
  }

  @Test
  void losGruposQueSoloDifierenEnMayusculasYTildesSonElMismo() {
    String csv =
        "RUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS\n"
            + "3030,CL1,CHUTE 1,Grupo A,L-M-X-J-V-S,B2C,5\n"
            + "3030,CL1,CHUTE 1,grupo  á,L-M-X-J-V-S,B2C,5\n"
            + "3031,CL1,CHUTE 1,GRUPO A,L-M-X-J-V-S,B2C,5\n";

    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(csv(csv));

    assertEquals(2, analisis.rutas().size());
    assertTrue(analisis.rutas().stream().allMatch((r) -> r.grupo().equals("Grupo A")));
  }

  @Test
  void rechazaUnaRutaConDosClusters() {
    String csv =
        "RUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS\n"
            + "3030,CL1,CHUTE 1,A,L-M-X-J-V-S,B2C,5\n"
            + "3030,CL2,CHUTE 1,A,L-M-X-J-V-S,B2C,5\n";

    assertThrows(ConflictoException.class, () -> SimulacionExcel.analizar(csv(csv)));
  }

  @Test
  void cuentaCodsDistintosYAceptaComillasConSaltoDeLinea() {
    String csv =
        "COD,RUTA,CLUSTER,CHUTE,GRUPO,FRECUENCIA,TIPO,BULTOS\n"
            + "A1,3030,\"CL\n1\",CHUTE 1,A,L-M-X-J-V-S,B2C,5\n"
            + "A1,3030,\"CL\n1\",CHUTE 1,A,L-M-X-J-V-S,B2C,5\n"
            + "A2,3030,\"CL\n1\",CHUTE 1,A,L-M-X-J-V-S,B2C,5\n";

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
      String[] columnas = {"RUTA", "CLUSTER", "CHUTE", "TIPO", "BULTOS", "GRUPO", "FRECUENCIA"};
      for (int i = 0; i < columnas.length; i++) {
        cabecera.createCell(i).setCellValue(columnas[i]);
      }
      Row fila = hoja.createRow(1);
      fila.createCell(0).setCellValue("3030");
      fila.createCell(1).setCellValue("CL1");
      fila.createCell(2).setCellValue("CHUTE 1");
      fila.createCell(3).setCellValue("B2C");
      fila.createCell(4).setCellValue(7);
      fila.createCell(5).setCellValue("A");
      fila.createCell(6).setCellValue("M-J-S");
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
    fila.createCell(8).setCellValue("A");
    fila.createCell(9).setCellValue("L-M-X-J-V-S");
  }
}
