package oracle.simulacion.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import oracle.common.exception.ConflictoException;
import oracle.simulacion.model.TipoRuta;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.RichTextString;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * Interpreta el Excel de proyección y agrupa sus filas por RUTA y TIPO.
 *
 * <p>Columnas esperadas (sin distinguir mayúsculas ni tildes): COD./COD, RUTA, CLUSTER, CHUTE,
 * TIPO (B2C/B2B), BULTOS PROMEDIO/BULTOS y, opcionalmente, UNIDADES PROMEDIO/UNIDADES y LINEAS
 * PROMEDIO/LINEAS. La cabecera se busca en las primeras {@value #FILAS_BUSCAR_CABECERA} filas
 * (puede haber un título encima).
 *
 * <p>La misma ruta puede aparecer con tipos distintos (una ruta B2C y la misma ruta B2B), pero no
 * puede repetirse dos veces con el mismo tipo ni cambiar de cluster/chute dentro del mismo tipo.
 *
 * <p>Memoria y CPU acotadas: los .xlsx se leen en streaming (SAX) y la hoja y sharedStrings.xml
 * tienen un tope de bytes descomprimidos; el CSV se lee con un búfer propio sin marcas. Por fila
 * no se crean listas, mapas ni BigDecimal: los importes se suman en céntimos ({@code long}).
 *
 * <p>Números: ver {@link #decimal(String, int, String)} para la regla de separadores.
 *
 * <p>Errores: todo archivo rechazado lanza {@link ArchivoInvalidoException} (subclase de
 * {@link ConflictoException}) con un estado HTTP 4xx y un mensaje apto para el cliente; los
 * detalles técnicos (IOException, POI) solo van al log.
 */
public final class SimulacionExcel {

  private static final Logger log = LoggerFactory.getLogger(SimulacionExcel.class);

  static final int MAX_FILAS = 100_000;
  /** Solo se leen las primeras columnas: el formato usa 7. */
  static final int MAX_COLUMNAS = 50;
  /** Longitud de codigo_ruta y de los nombres de cluster/chute en la BD. */
  static final int MAX_TEXTO = 50;
  /** Máximo de DECIMAL(12,2), en céntimos. */
  private static final long MAX_CENTIMOS = 999_999_999_999L;
  static final BigDecimal MAX_VALOR = BigDecimal.valueOf(MAX_CENTIMOS, 2);
  /** La cabecera puede estar debajo de un título: se busca en estas primeras filas. */
  static final int FILAS_BUSCAR_CABECERA = 20;
  /** Caracteres que se inspeccionan para detectar el separador del CSV. */
  private static final int MUESTRA_CSV = 64 * 1024;
  /**
   * Tope por entrada del zip (respaldo global de POI). Los topes efectivos por parte son los de
   * {@link Limites}; este solo debe ser mayor o igual que ellos.
   */
  private static final long MAX_ENTRADA_ZIP = 128L * 1024 * 1024;

  private static final String[] COLUMNAS_OBLIGATORIAS = {"RUTA", "CLUSTER", "CHUTE", "TIPO", "BULTOS"};
  private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
  private static final Pattern ESPACIOS = Pattern.compile("[\\s\\u00A0\\u2007\\u202F\\uFEFF]+");
  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
  private static final char[] SEPARADORES_CSV = {',', ';', '\t', '|'};

  static {
    ZipSecureFile.setMaxEntrySize(MAX_ENTRADA_ZIP);
  }

  private SimulacionExcel() {}

  /**
   * Topes de lectura. Calibrados con {@code SimulacionExcelBenchmark}: un libro real de 100k filas
   * × 7 columnas tiene ~28 MB de hoja y 2,4–5,7 MB de sharedStrings; los topes dejan ~4× de margen
   * y, en el peor caso, limitan sharedStrings en memoria a ~80 MB.
   */
  record Limites(int maxFilas, long maxBytesArchivo, long maxBytesHoja, long maxBytesCadenas) {
    static final Limites PRODUCCION =
        new Limites(MAX_FILAS, 20L * 1024 * 1024, MAX_ENTRADA_ZIP, 24L * 1024 * 1024);
  }

  public record FilaRuta(
      String codigoRuta,
      String cluster,
      String chute,
      TipoRuta tipo,
      BigDecimal bultos,
      BigDecimal unidades,
      BigDecimal lineas,
      int conteoCod) {}

  public record Analisis(List<FilaRuta> rutas, BigDecimal totalBultos) {}

  /**
   * Archivo rechazado por su formato o contenido. Extiende {@link ConflictoException} para que,
   * fuera del endpoint de carga, siga respondiendo 409 como antes; el endpoint de carga lo traduce
   * a {@link #estado()}.
   */
  public static final class ArchivoInvalidoException extends ConflictoException {
    private final HttpStatus estado;

    ArchivoInvalidoException(HttpStatus estado, String mensaje) {
      super(mensaje);
      this.estado = estado;
    }

    public HttpStatus estado() {
      return estado;
    }

    /** 400: archivo vacío, dañado, ilegible o cuya firma no coincide con la extensión. */
    public static ArchivoInvalidoException ilegible(String mensaje) {
      return new ArchivoInvalidoException(HttpStatus.BAD_REQUEST, mensaje);
    }

    /** 413: supera el tamaño, las filas o los bytes descomprimidos permitidos. */
    public static ArchivoInvalidoException demasiadoGrande(String mensaje) {
      return new ArchivoInvalidoException(HttpStatus.CONTENT_TOO_LARGE, mensaje);
    }

    /** 415: extensión no soportada. */
    public static ArchivoInvalidoException noSoportado(String mensaje) {
      return new ArchivoInvalidoException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, mensaje);
    }

    /** 422: el archivo se lee pero su contenido no cumple el formato (cabecera, valores). */
    public static ArchivoInvalidoException contenido(String mensaje) {
      return new ArchivoInvalidoException(HttpStatus.UNPROCESSABLE_CONTENT, mensaje);
    }
  }

  public static Analisis analizar(MultipartFile archivo) {
    return analizar(archivo, Limites.PRODUCCION);
  }

  static Analisis analizar(MultipartFile archivo, Limites limites) {
    String nombre =
        archivo.getOriginalFilename() == null
            ? ""
            : archivo.getOriginalFilename().toLowerCase(Locale.ROOT);
    if (archivo.getSize() > limites.maxBytesArchivo()) {
      throw ArchivoInvalidoException.demasiadoGrande(
          "El archivo supera el tamaño máximo de " + limites.maxBytesArchivo() / (1024 * 1024) + " MB.");
    }
    try {
      byte[] firma = firma(archivo);
      if (nombre.endsWith(".csv")) {
        return analizarCsv(archivo, firma, limites);
      }
      if (nombre.endsWith(".xlsx")) {
        exigirFirma(empiezaCon(firma, 0x50, 0x4B, 0x03, 0x04), ".xlsx");
        return analizarXlsx(archivo, limites);
      }
      if (nombre.endsWith(".xls")) {
        exigirFirma(empiezaCon(firma, 0xD0, 0xCF, 0x11, 0xE0), ".xls");
        try (InputStream entrada = archivo.getInputStream()) {
          return analizarXls(entrada, limites);
        }
      }
      throw ArchivoInvalidoException.noSoportado(
          "Formato no soportado. Usa un archivo .xlsx, .xls o .csv.");
    } catch (IOException e) {
      log.warn("No se pudo leer el archivo de simulación '{}'", archivo.getOriginalFilename(), e);
      throw ArchivoInvalidoException.ilegible(
          "No se pudo leer el archivo. Verifica que no esté dañado y vuelve a subirlo.");
    } catch (ConflictoException e) {
      throw e;
    } catch (RuntimeException e) {
      ArchivoInvalidoException invalido = causa(e);
      if (invalido != null) {
        throw invalido;
      }
      if (!vieneDePoi(e)) {
        throw e;
      }
      // POI lanza excepciones no comprobadas ante libros dañados, cifrados o antiguos (BIFF5).
      log.warn("POI rechazó el archivo de simulación '{}'", archivo.getOriginalFilename(), e);
      throw ArchivoInvalidoException.ilegible(
          "El archivo está dañado, protegido con contraseña o en un formato de Excel no soportado.");
    }
  }

  /**
   * Nombre canónico para guardar (cluster, chute, ruta): sin espacios al borde y con los espacios
   * internos (incluidos los no separables) reducidos a uno. Conserva mayúsculas y tildes; de eso
   * se encarga la colación comun.ci de PostgreSQL. La colación NO colapsa espacios internos, por eso
   * se normalizan antes de insertar.
   */
  public static String nombre(String texto) {
    if (texto == null) {
      return "";
    }
    if (esSimple(texto)) {
      return texto;
    }
    return ESPACIOS.matcher(texto).replaceAll(" ").trim();
  }

  /**
   * Clave de comparación de nombres (cluster, chute, ruta): {@link #nombre(String)} en mayúsculas y
   * sin tildes. Equivale a la colación comun.ci sobre el nombre canónico, así que dos nombres con la
   * misma clave son el mismo registro.
   */
  public static String clave(String texto) {
    if (texto == null) {
      return "";
    }
    if (esSimple(texto)) {
      // Camino rápido (ASCII sin espacios raros): toUpperCase devuelve la misma instancia si ya
      // está en mayúsculas, así que no asigna nada en el caso típico ("R3030", "CHUTE 1").
      return texto.toUpperCase(Locale.ROOT);
    }
    String limpio = nombre(texto).toUpperCase(Locale.ROOT);
    return MARCAS.matcher(Normalizer.normalize(limpio, Normalizer.Form.NFD)).replaceAll("");
  }

  /** ASCII imprimible, sin espacios al borde ni dobles: no requiere normalización. */
  private static boolean esSimple(String texto) {
    int n = texto.length();
    if (n == 0) {
      return true;
    }
    if (texto.charAt(0) == ' ' || texto.charAt(n - 1) == ' ') {
      return false;
    }
    char anterior = 'x';
    for (int i = 0; i < n; i++) {
      char c = texto.charAt(i);
      if (c < 0x20 || c > 0x7E || (c == ' ' && anterior == ' ')) {
        return false;
      }
      anterior = c;
    }
    return true;
  }

  // ------------------------------------------------------------------
  // Lectores
  // ------------------------------------------------------------------

  /** .xlsx en streaming: el paquete se abre desde disco y la hoja se recorre con SAX. */
  private static Analisis analizarXlsx(MultipartFile archivo, Limites limites) throws IOException {
    Path temporal = Files.createTempFile("simulacion-", ".xlsx");
    try {
      archivo.transferTo(temporal);
      Agrupador agrupador = new Agrupador(limites);
      try (OPCPackage paquete = OPCPackage.open(temporal.toFile(), PackageAccess.READ)) {
        XSSFReader lector = new XSSFReader(paquete);
        XSSFReader.SheetIterator hojas = (XSSFReader.SheetIterator) lector.getSheetsData();
        if (!hojas.hasNext()) {
          throw ArchivoInvalidoException.contenido("El archivo no contiene hojas.");
        }
        SharedStrings cadenas = cadenas(paquete, limites);
        FilasXlsx filas = new FilasXlsx(agrupador);
        // Con estilos, POI entrega cada número por FormatoCrudo (con su double); un libro sin
        // estilos recibe los predeterminados para que ningún número llegue como texto.
        StylesTable estilos = lector.getStylesTable();
        if (estilos == null || estilos.getNumCellStyles() == 0) {
          estilos = new StylesTable();
        }
        XMLReader parser = XMLHelper.newXMLReader();
        parser.setContentHandler(
            new XSSFSheetXMLHandler(estilos, cadenas, filas, new FormatoCrudo(filas), false));
        try (InputStream hoja =
            new EntradaAcotada(hojas.next(), limites.maxBytesHoja(), "La hoja del libro")) {
          parser.parse(new InputSource(hoja));
        }
      } catch (OpenXML4JException | SAXException | ParserConfigurationException e) {
        ArchivoInvalidoException causa = causa(e);
        if (causa != null) {
          throw causa;
        }
        log.warn("Libro .xlsx ilegible '{}'", archivo.getOriginalFilename(), e);
        throw ArchivoInvalidoException.ilegible("El archivo .xlsx está dañado o no es un libro de Excel.");
      }
      return agrupador.resultado();
    } finally {
      Files.deleteIfExists(temporal);
    }
  }

  /**
   * sharedStrings.xml se carga completo en memoria (lo exige el acceso por índice), así que se lee
   * a través de un flujo con tope de bytes descomprimidos: un libro con cadenas gigantes se
   * rechaza con 413 en lugar de agotar el heap.
   */
  private static SharedStrings cadenas(OPCPackage paquete, Limites limites)
      throws IOException, SAXException {
    List<PackagePart> partes =
        paquete.getPartsByContentType(XSSFRelation.SHARED_STRINGS.getContentType());
    if (partes.isEmpty()) {
      return new ReadOnlySharedStringsTable(paquete, false);
    }
    try (InputStream entrada =
        new EntradaAcotada(
            partes.get(0).getInputStream(), limites.maxBytesCadenas(), "La tabla de textos del libro")) {
      return new CadenasLigeras(new TablaContada(entrada));
    }
  }

  /**
   * Copia de sharedStrings como String[]. ReadOnlySharedStringsTable.getItemAt crea un
   * XSSFRichTextString (objeto XMLBeans) en CADA celda de texto; aquí se crea uno liviano.
   */
  private static final class CadenasLigeras implements SharedStrings {
    private final String[] cadenas;
    private final int total;

    CadenasLigeras(TablaContada origen) {
      this.cadenas = new String[origen.leidas];
      for (int i = 0; i < cadenas.length; i++) {
        cadenas[i] = origen.getItemAt(i).getString();
      }
      this.total = origen.getCount();
    }

    @Override
    public RichTextString getItemAt(int indice) {
      return new Texto(cadenas[indice]);
    }

    @Override
    public int getCount() {
      return total;
    }

    @Override
    public int getUniqueCount() {
      return cadenas.length;
    }
  }

  /**
   * Cuenta los &lt;si&gt; leídos: uniqueCount sale de un atributo del XML y no es confiable. El
   * campo no tiene inicializador a propósito: se incrementa durante super() (que ya parsea).
   */
  private static final class TablaContada extends ReadOnlySharedStringsTable {
    private int leidas;

    TablaContada(InputStream entrada) throws IOException, SAXException {
      super(entrada, false);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
      super.endElement(uri, localName, qName);
      if ("si".equals(localName)) {
        leidas++;
      }
    }
  }

  /** RichTextString mínimo: el lector de la hoja solo usa toString(). */
  private record Texto(String getString) implements RichTextString {
    @Override
    public String toString() {
      return getString;
    }

    @Override
    public int length() {
      return getString.length();
    }

    @Override
    public int numFormattingRuns() {
      return 0;
    }

    @Override
    public int getIndexOfFormattingRun(int indice) {
      return 0;
    }

    @Override
    public void applyFont(int inicio, int fin, short fuente) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void applyFont(int inicio, int fin, Font fuente) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void applyFont(Font fuente) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void applyFont(short fuente) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void clearFormatting() {
      throw new UnsupportedOperationException();
    }
  }

  /** .xls: el formato binario ya está acotado (65 536 filas × 256 columnas). */
  private static Analisis analizarXls(InputStream entrada, Limites limites) throws IOException {
    try (HSSFWorkbook libro = new HSSFWorkbook(entrada)) {
      if (libro.getNumberOfSheets() == 0) {
        throw ArchivoInvalidoException.contenido("El archivo no contiene hojas.");
      }
      Sheet hoja = libro.getSheetAt(0);
      Agrupador agrupador = new Agrupador(limites);
      Fila fila = new Fila();
      for (Row registro : hoja) {
        fila.limpiar();
        int ultima = Math.min(Math.max(registro.getLastCellNum(), 0), MAX_COLUMNAS);
        for (int c = 0; c < ultima; c++) {
          Cell celda = registro.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
          if (esNumerica(celda)) {
            fila.ponerNumero(c, celda.getNumericCellValue());
          } else {
            fila.poner(c, valorCelda(celda));
          }
        }
        agrupador.fila(registro.getRowNum() + 1, fila);
      }
      return agrupador.resultado();
    }
  }

  /**
   * CSV: codificación detectada (BOM UTF-8/UTF-16; si no, UTF-8 válido o Windows-1252), separador
   * , ; tabulador o | detectado sobre las primeras filas, y comillas con saltos de línea.
   */
  private static Analisis analizarCsv(MultipartFile archivo, byte[] firma, Limites limites)
      throws IOException {
    Charset charset = detectarCharset(archivo, firma);
    char separador = detectarSeparador(archivo, charset);
    Agrupador agrupador = new Agrupador(limites);
    try (Reader lector = new InputStreamReader(archivo.getInputStream(), charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE))) {
      LectorCsv csv = new LectorCsv(lector, separador);
      Fila fila;
      while ((fila = csv.siguiente()) != null) {
        agrupador.fila(csv.linea(), fila);
      }
    }
    return agrupador.resultado();
  }

  /**
   * BOM primero; sin BOM, el archivo es UTF-8 solo si TODO él es UTF-8 válido (se valida en
   * streaming, sin cargarlo). Si no, Windows-1252: es lo que guarda Excel en Windows con "CSV
   * (delimitado por comas)", y antes hacía que "LÍNEAS" no se reconociera.
   */
  static Charset detectarCharset(MultipartFile archivo, byte[] firma) throws IOException {
    if (empiezaCon(firma, 0xEF, 0xBB, 0xBF)) {
      return StandardCharsets.UTF_8;
    }
    if (empiezaCon(firma, 0xFF, 0xFE)) {
      return StandardCharsets.UTF_16LE;
    }
    if (empiezaCon(firma, 0xFE, 0xFF)) {
      return StandardCharsets.UTF_16BE;
    }
    if (!esTexto(firma)) {
      throw ArchivoInvalidoException.ilegible("El archivo .csv no es de texto.");
    }
    return esUtf8Valido(archivo) ? StandardCharsets.UTF_8 : WINDOWS_1252;
  }

  private static boolean esUtf8Valido(MultipartFile archivo) throws IOException {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer entrada = ByteBuffer.allocate(16 * 1024);
    CharBuffer salida = CharBuffer.allocate(16 * 1024);
    try (InputStream flujo = archivo.getInputStream()) {
      byte[] arreglo = entrada.array();
      while (true) {
        int leidos = flujo.read(arreglo, entrada.position(), entrada.remaining());
        boolean fin = leidos < 0;
        if (!fin) {
          entrada.position(entrada.position() + leidos);
        }
        entrada.flip();
        CoderResult resultado = decoder.decode(entrada, salida, fin);
        if (resultado.isError()) {
          return false;
        }
        salida.clear();
        entrada.compact();
        if (fin) {
          if (decoder.flush(salida).isError() || entrada.position() > 0) {
            return false;
          }
          return true;
        }
      }
    }
  }

  /**
   * El separador es el que hace aparecer una cabecera válida en las primeras filas; si ninguno lo
   * logra, el más frecuente en la línea más "separada". Se lee una muestra acotada desde un flujo
   * nuevo: no se usa mark/reset (que fallaba con cabeceras de más de 64 KB).
   */
  private static char detectarSeparador(MultipartFile archivo, Charset charset) throws IOException {
    char[] muestra = new char[MUESTRA_CSV];
    int n = 0;
    try (Reader lector = new InputStreamReader(archivo.getInputStream(), charset)) {
      int leidos;
      while (n < muestra.length && (leidos = lector.read(muestra, n, muestra.length - n)) > 0) {
        n += leidos;
      }
    }
    String texto = new String(muestra, 0, n);
    String[] lineas = texto.split("\r?\n", FILAS_BUSCAR_CABECERA + 1);
    int lineasUtiles = Math.min(lineas.length, FILAS_BUSCAR_CABECERA);
    for (String linea : Arrays.copyOf(lineas, lineasUtiles)) {
      for (char separador : SEPARADORES_CSV) {
        if (linea.indexOf(separador) >= 0 && tieneObligatorias(mapearColumnas(dividir(linea, separador)))) {
          return separador;
        }
      }
    }
    char mejor = ',';
    long maximo = 0;
    for (String linea : Arrays.copyOf(lineas, lineasUtiles)) {
      for (char separador : SEPARADORES_CSV) {
        long cantidad = linea.chars().filter((c) -> c == separador).count();
        if (cantidad > maximo) {
          maximo = cantidad;
          mejor = separador;
        }
      }
    }
    return mejor;
  }

  private static List<String> dividir(String linea, char separador) {
    List<String> partes = new ArrayList<>();
    StringBuilder actual = new StringBuilder();
    boolean comillas = false;
    for (int i = 0; i < linea.length(); i++) {
      char c = linea.charAt(i);
      if (c == '"') {
        comillas = !comillas;
      } else if (c == separador && !comillas) {
        partes.add(actual.toString());
        actual.setLength(0);
      } else {
        actual.append(c);
      }
    }
    partes.add(actual.toString());
    return partes;
  }

  // ------------------------------------------------------------------
  // Agrupación por ruta
  // ------------------------------------------------------------------

  /**
   * Fila reutilizable: evita crear una lista por registro. Las celdas numéricas guardan su double y
   * solo se convierten a texto si una columna de texto (RUTA, COD...) lo pide.
   */
  static final class Fila {
    private final String[] valores = new String[MAX_COLUMNAS];
    private final double[] numeros = new double[MAX_COLUMNAS];
    private final boolean[] numerico = new boolean[MAX_COLUMNAS];
    private int tamano;

    void limpiar() {
      tamano = 0;
    }

    /** Coloca un texto en la columna indicada; las columnas intermedias quedan vacías. */
    void poner(int columna, String valor) {
      if (abrir(columna)) {
        valores[columna] = valor;
        numerico[columna] = false;
      }
    }

    void ponerNumero(int columna, double valor) {
      if (abrir(columna)) {
        valores[columna] = null;
        numeros[columna] = valor;
        numerico[columna] = true;
      }
    }

    private boolean abrir(int columna) {
      if (columna < 0 || columna >= MAX_COLUMNAS) {
        return false;
      }
      while (tamano < columna) {
        valores[tamano] = "";
        numerico[tamano] = false;
        tamano++;
      }
      tamano = Math.max(tamano, columna + 1);
      return true;
    }

    void agregar(String valor) {
      poner(tamano, valor);
    }

    String valor(int indice) {
      if (indice >= tamano) {
        return "";
      }
      if (numerico[indice] && valores[indice] == null) {
        valores[indice] = crudo(numeros[indice]);
      }
      return valores[indice];
    }

    boolean numerico(int indice) {
      return indice < tamano && numerico[indice];
    }

    double numero(int indice) {
      return numeros[indice];
    }

    boolean vacia() {
      for (int i = 0; i < tamano; i++) {
        if (numerico[i] || !valores[i].isBlank()) {
          return false;
        }
      }
      return true;
    }

    List<String> comoLista() {
      List<String> lista = new ArrayList<>(tamano);
      for (int i = 0; i < tamano; i++) {
        lista.add(valor(i));
      }
      return lista;
    }
  }

  /** Recibe las filas en orden, detecta la cabecera y acumula por RUTA. */
  private static final class Agrupador {

    private final Limites limites;
    private int iRuta = -1;
    private int iCluster = -1;
    private int iChute = -1;
    private int iTipo = -1;
    private int iBultos = -1;
    private int iUnidades = -1;
    private int iLineas = -1;
    private int iCod = -1;
    private boolean cabeceraEncontrada;
    private boolean huboTexto;
    private int filasVistas;
    private int filasDatos;
    /** Mejor intento de cabecera (con alguna columna conocida) para explicar qué falta. */
    private Map<String, Integer> candidata;
    private int filaCandidata;
    private final Map<String, Acumulado> porRuta = new LinkedHashMap<>();
    private long totalBultos;

    Agrupador(Limites limites) {
      this.limites = limites;
    }

    void fila(int numero, Fila fila) {
      if (!cabeceraEncontrada) {
        buscarCabecera(numero, fila);
        return;
      }
      if (fila.vacia()) {
        // Filas vacías (p. ej. con solo formato) no cuentan para el tope.
        return;
      }
      if (++filasDatos > limites.maxFilas()) {
        throw ArchivoInvalidoException.demasiadoGrande(
            "El archivo supera el máximo de " + limites.maxFilas() + " filas.");
      }
      acumular(numero, fila);
    }

    private void buscarCabecera(int numero, Fila fila) {
      filasVistas++;
      if (!fila.vacia()) {
        huboTexto = true;
        Map<String, Integer> columnas = mapearColumnas(fila.comoLista());
        if (tieneObligatorias(columnas)) {
          usarCabecera(columnas);
          return;
        }
        if (!columnas.isEmpty() && (candidata == null || columnas.size() > candidata.size())) {
          candidata = columnas;
          filaCandidata = numero;
        }
      }
      if (filasVistas >= FILAS_BUSCAR_CABECERA) {
        throw sinCabecera();
      }
    }

    private void usarCabecera(Map<String, Integer> columnas) {
      cabeceraEncontrada = true;
      iRuta = columnas.get("RUTA");
      iCluster = columnas.get("CLUSTER");
      iChute = columnas.get("CHUTE");
      iTipo = columnas.get("TIPO");
      iBultos = columnas.get("BULTOS");
      iUnidades = columnas.getOrDefault("UNIDADES", -1);
      iLineas = columnas.getOrDefault("LINEAS", -1);
      iCod = columnas.getOrDefault("COD", -1);
    }

    private ArchivoInvalidoException sinCabecera() {
      if (candidata != null) {
        for (String columna : COLUMNAS_OBLIGATORIAS) {
          if (!candidata.containsKey(columna)) {
            return ArchivoInvalidoException.contenido(
                "Falta la columna " + columna + " en la cabecera (fila " + filaCandidata + ").");
          }
        }
      }
      return ArchivoInvalidoException.contenido(
          "No se encontró la cabecera (" + String.join(", ", COLUMNAS_OBLIGATORIAS)
              + ") en las primeras " + FILAS_BUSCAR_CABECERA + " filas.");
    }

    private void acumular(int numero, Fila fila) {
      // Sin RUTA no es una ruta: filas vacías o de totales al pie de la hoja.
      String ruta = nombre(texto(fila, iRuta));
      if (ruta.isEmpty()) {
        return;
      }
      long bultos = centimos(fila, iBultos, numero, "BULTOS");
      long unidades = centimos(fila, iUnidades, numero, "UNIDADES");
      long lineas = centimos(fila, iLineas, numero, "LINEAS");
      // Una fila con 0 bultos pero con unidades o líneas sí suma a su ruta.
      if (bultos == 0 && unidades == 0 && lineas == 0) {
        return;
      }
      String cluster = nombre(texto(fila, iCluster));
      String chute = nombre(texto(fila, iChute));
      if (cluster.isEmpty() || chute.isEmpty()) {
        throw ArchivoInvalidoException.contenido(
            "La fila " + numero + " no tiene " + (cluster.isEmpty() ? "CLUSTER" : "CHUTE") + ".");
      }
      String tipoTexto = texto(fila, iTipo);
      TipoRuta tipo = TipoRuta.desdeTexto(tipoTexto);
      if (tipo == null) {
        throw ArchivoInvalidoException.contenido(
            "La fila " + numero + " tiene TIPO '" + tipoTexto + "'; solo se admite B2C o B2B.");
      }
      exigirLongitud(ruta, "RUTA", numero);
      exigirLongitud(cluster, "CLUSTER", numero);
      exigirLongitud(chute, "CHUTE", numero);
      String cod = texto(fila, iCod);

      String claveRuta = clave(ruta) + '\u0000' + tipo.name();
      Acumulado acumulado = porRuta.get(claveRuta);
      if (acumulado == null) {
        acumulado = new Acumulado(ruta, cluster, chute, tipo);
        porRuta.put(claveRuta, acumulado);
      } else {
        acumulado.exigirMismo(cluster, chute, numero);
      }
      acumulado.sumar(bultos, unidades, lineas, cod, numero);
      totalBultos += bultos;
    }

    Analisis resultado() {
      if (!cabeceraEncontrada) {
        if (!huboTexto) {
          throw ArchivoInvalidoException.contenido("La hoja está vacía.");
        }
        throw sinCabecera();
      }
      // Una ruta sin bultos no tiene carga que balancear.
      List<FilaRuta> rutas = new ArrayList<>(porRuta.size());
      porRuta.values().forEach(
          (acumulado) -> {
            if (acumulado.bultos > 0) {
              rutas.add(acumulado.aFila());
            }
          });
      if (rutas.isEmpty()) {
        throw ArchivoInvalidoException.contenido(
            "El archivo no contiene rutas con bultos mayores a cero.");
      }
      return new Analisis(List.copyOf(rutas), BigDecimal.valueOf(totalBultos, 2));
    }

    private static String texto(Fila fila, int indice) {
      return indice < 0 ? "" : fila.valor(indice).trim();
    }

    private static long centimos(Fila fila, int indice, int numero, String columna) {
      if (indice < 0) {
        return 0;
      }
      if (fila.numerico(indice)) {
        return SimulacionExcel.centimos(fila.numero(indice), numero, columna);
      }
      return SimulacionExcel.centimos(fila.valor(indice), numero, columna);
    }
  }

  /** Totales de una ruta en céntimos; los COD se cuentan sin repetir. */
  private static final class Acumulado {
    private final String ruta;
    private final String cluster;
    private final String chute;
    private final TipoRuta tipo;
    private final String claveCluster;
    private final String claveChute;
    private long bultos;
    private long unidades;
    private long lineas;
    /** El primer COD se guarda suelto: el conjunto solo se crea si aparece un segundo distinto. */
    private String primerCod;
    private Set<String> cods;
    private int filas;

    Acumulado(String ruta, String cluster, String chute, TipoRuta tipo) {
      this.ruta = ruta;
      this.cluster = cluster;
      this.chute = chute;
      this.tipo = tipo;
      this.claveCluster = clave(cluster);
      this.claveChute = clave(chute);
    }

    void exigirMismo(String otroCluster, String otroChute, int numero) {
      if (!cluster.equals(otroCluster) && !claveCluster.equals(clave(otroCluster))) {
        throw distinto("CLUSTER", cluster, otroCluster, numero);
      }
      if (!chute.equals(otroChute) && !claveChute.equals(clave(otroChute))) {
        throw distinto("CHUTE", chute, otroChute, numero);
      }
    }

    private ArchivoInvalidoException distinto(String columna, String esperado, String recibido, int fila) {
      return ArchivoInvalidoException.contenido(
          "Fila " + fila + ": la ruta " + ruta + " (" + tipo + ") ya tenía " + columna + " "
              + esperado + " y aparece con " + recibido + ". Una ruta debe tener un único "
              + columna + " por tipo.");
    }

    void sumar(long b, long u, long l, String cod, int numero) {
      bultos = acotar(bultos + b, "BULTOS", numero);
      unidades = acotar(unidades + u, "UNIDADES", numero);
      lineas = acotar(lineas + l, "LINEAS", numero);
      if (!cod.isEmpty()) {
        String claveCod = clave(cod);
        if (primerCod == null) {
          primerCod = claveCod;
        } else if (cods != null) {
          cods.add(claveCod);
        } else if (!primerCod.equals(claveCod)) {
          cods = new HashSet<>();
          cods.add(primerCod);
          cods.add(claveCod);
        }
      }
      filas++;
    }

    FilaRuta aFila() {
      int conteo = primerCod == null ? filas : cods == null ? 1 : cods.size();
      return new FilaRuta(
          ruta,
          cluster,
          chute,
          tipo,
          BigDecimal.valueOf(bultos, 2),
          BigDecimal.valueOf(unidades, 2),
          BigDecimal.valueOf(lineas, 2),
          conteo);
    }

    private long acotar(long valor, String columna, int numero) {
      if (valor > MAX_CENTIMOS) {
        throw ArchivoInvalidoException.contenido(
            "La ruta " + ruta + " supera el máximo permitido de " + columna + " (fila " + numero + ").");
      }
      return valor;
    }
  }

  // ------------------------------------------------------------------
  // Utilidades de lectura
  // ------------------------------------------------------------------

  /** Recibe las celdas de la hoja .xlsx y entrega cada fila al agrupador. */
  private static final class FilasXlsx implements SheetContentsHandler {
    private final Agrupador agrupador;
    private final Fila fila = new Fila();
    /** Número que FormatoCrudo acaba de recibir para la celda en curso. */
    private boolean hayNumero;
    private double numero;

    FilasXlsx(Agrupador agrupador) {
      this.agrupador = agrupador;
    }

    @Override
    public void startRow(int numero) {
      fila.limpiar();
    }

    @Override
    public void endRow(int numero) {
      agrupador.fila(numero + 1, fila);
    }

    @Override
    public void cell(String referencia, String valor, XSSFComment comentario) {
      int columna = referencia == null ? fila.tamano : columna(referencia);
      if (hayNumero) {
        hayNumero = false;
        fila.ponerNumero(columna, numero);
      } else {
        fila.poner(columna, valor == null ? "" : valor);
      }
    }

    /** Columna (0..n) de una referencia "AB12" sin crear un CellReference por celda. */
    private static int columna(String referencia) {
      int columna = 0;
      for (int i = 0; i < referencia.length(); i++) {
        char c = referencia.charAt(i);
        if (c >= 'A' && c <= 'Z') {
          columna = columna * 26 + (c - 'A' + 1);
          if (columna > MAX_COLUMNAS) {
            return MAX_COLUMNAS;
          }
        } else if (c >= 'a' && c <= 'z') {
          columna = columna * 26 + (c - 'a' + 1);
          if (columna > MAX_COLUMNAS) {
            return MAX_COLUMNAS;
          }
        } else {
          break;
        }
      }
      return columna - 1;
    }
  }

  /**
   * Las celdas numéricas se leen como número puro (con el formato del libro, 1500 con "#,##0"
   * llegaría como "1,500"). El double se pasa tal cual a la fila: no se convierte a texto ni se
   * vuelve a parsear, y no pasa por la heurística de separadores.
   */
  private static final class FormatoCrudo extends DataFormatter {
    private final FilasXlsx filas;

    FormatoCrudo(FilasXlsx filas) {
      super(Locale.ROOT);
      this.filas = filas;
    }

    @Override
    public String formatRawCellContents(double valor, int indice, String formato) {
      filas.hayNumero = true;
      filas.numero = valor;
      return "";
    }

    @Override
    public String formatRawCellContents(
        double valor, int indice, String formato, boolean fechas1904) {
      return formatRawCellContents(valor, indice, formato);
    }
  }

  /** Texto canónico de un número de celda: "3030" (no "3030.0"), "12.5", "0.001". */
  private static String crudo(double valor) {
    if (valor == Math.rint(valor) && Math.abs(valor) < 1e15) {
      return Long.toString((long) valor);
    }
    return BigDecimal.valueOf(valor).stripTrailingZeros().toPlainString();
  }

  private static boolean esNumerica(Cell celda) {
    if (celda == null) {
      return false;
    }
    CellType tipo = celda.getCellType();
    if (tipo == CellType.FORMULA) {
      tipo = celda.getCachedFormulaResultType();
    }
    return tipo == CellType.NUMERIC;
  }

  private static String valorCelda(Cell celda) {
    if (celda == null) {
      return "";
    }
    CellType tipo = celda.getCellType();
    if (tipo == CellType.FORMULA) {
      tipo = celda.getCachedFormulaResultType();
    }
    return switch (tipo) {
      case NUMERIC -> crudo(celda.getNumericCellValue());
      case STRING -> celda.getStringCellValue();
      case BOOLEAN -> String.valueOf(celda.getBooleanCellValue());
      default -> "";
    };
  }

  /**
   * Lector CSV por registros con búfer propio (sin mark/reset ni lecturas sincronizadas por
   * carácter): respeta comillas, incluidos saltos de línea y "" dentro de ellas.
   */
  private static final class LectorCsv {
    private final Reader lector;
    private final char separador;
    private final char[] bufer = new char[16 * 1024];
    private int posicion;
    private int limite;
    private int linea = 1;
    private int lineaRegistro;
    private boolean fin;
    private boolean inicio = true;
    private final StringBuilder actual = new StringBuilder(64);
    private final Fila fila = new Fila();

    LectorCsv(Reader lector, char separador) {
      this.lector = lector;
      this.separador = separador;
    }

    int linea() {
      return lineaRegistro;
    }

    private int leer() throws IOException {
      if (posicion == limite) {
        limite = lector.read(bufer, 0, bufer.length);
        posicion = 0;
        if (limite <= 0) {
          limite = 0;
          return -1;
        }
      }
      return bufer[posicion++];
    }

    private int mirar() throws IOException {
      int c = leer();
      if (c != -1) {
        posicion--;
      }
      return c;
    }

    Fila siguiente() throws IOException {
      while (!fin) {
        lineaRegistro = linea;
        fila.limpiar();
        actual.setLength(0);
        boolean comillas = false;
        while (true) {
          int c = leer();
          if (inicio) {
            inicio = false;
            if (c == '﻿') {
              continue;
            }
          }
          if (c == -1) {
            fin = true;
            break;
          }
          if (c == '\n') {
            linea++;
          }
          if (comillas) {
            if (c == '"') {
              if (mirar() == '"') {
                leer();
                actual.append('"');
              } else {
                comillas = false;
              }
            } else {
              actual.append((char) c);
            }
          } else if (c == '"') {
            comillas = true;
          } else if (c == separador) {
            agregar();
          } else if (c == '\n') {
            break;
          } else if (c != '\r') {
            actual.append((char) c);
          }
        }
        agregar();
        if (!fila.vacia()) {
          return fila;
        }
      }
      return null;
    }

    private void agregar() {
      if (fila.tamano < MAX_COLUMNAS) {
        fila.agregar(actual.toString().trim());
      }
      actual.setLength(0);
    }
  }

  private static Map<String, Integer> mapearColumnas(List<String> cabecera) {
    Map<String, Integer> columnas = new HashMap<>();
    for (int i = 0; i < cabecera.size(); i++) {
      switch (clave(cabecera.get(i))) {
        case "COD", "COD." -> columnas.putIfAbsent("COD", i);
        case "RUTA" -> columnas.putIfAbsent("RUTA", i);
        case "CLUSTER" -> columnas.putIfAbsent("CLUSTER", i);
        case "CHUTE" -> columnas.putIfAbsent("CHUTE", i);
        case "TIPO" -> columnas.putIfAbsent("TIPO", i);
        case "BULTOS", "BULTOS PROMEDIO" -> columnas.putIfAbsent("BULTOS", i);
        case "UNIDADES", "UNIDADES PROMEDIO" -> columnas.putIfAbsent("UNIDADES", i);
        case "LINEAS", "LINEAS PROMEDIO" -> columnas.putIfAbsent("LINEAS", i);
        default -> {
          // Columna desconocida: se ignora.
        }
      }
    }
    return columnas;
  }

  private static boolean tieneObligatorias(Map<String, Integer> columnas) {
    for (String columna : COLUMNAS_OBLIGATORIAS) {
      if (!columnas.containsKey(columna)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Convierte un texto numérico a DECIMAL(12,2).
   *
   * <p>Regla de separadores (supuesto documentado; el esquema guarda 2 decimales, así que un
   * importe del negocio nunca trae exactamente 3 decimales significativos):
   *
   * <ul>
   *   <li>Con ambos separadores, el último es el decimal: "1.500,25" y "1,500.25" → 1500.25.
   *   <li>Un mismo separador repetido es de miles y debe agrupar de a 3: "1.500.000" → 1500000.
   *   <li>Un único separador seguido de exactamente 3 dígitos, con parte entera de 1 a 3 dígitos
   *       distinta de 0, es de miles: "1.500" → 1500 y "1,500" → 1500 (antes "1.500" daba 1.5).
   *   <li>En cualquier otro caso el único separador es decimal: "12,5", "12.50", "0,500", "1234.567".
   * </ul>
   *
   * Los números de celdas numéricas de Excel no pasan por esta regla (ya son canónicos). Rechaza
   * textos no numéricos y negativos indicando la fila, en lugar de convertirlos en 0.
   */
  static BigDecimal decimal(String texto, int fila, String columna) {
    return BigDecimal.valueOf(centimos(texto, fila, columna), 2);
  }

  /** {@link #decimal} en céntimos, sin crear BigDecimal en el caso común. */
  static long centimos(String texto, int fila, String columna) {
    if (texto == null || texto.isBlank()) {
      return 0;
    }
    int n = texto.length();
    char[] limpio = new char[n];
    int largo = 0;
    int comas = 0;
    int puntos = 0;
    int ultimaComa = -1;
    int ultimoPunto = -1;
    for (int i = 0; i < n; i++) {
      char c = texto.charAt(i);
      if (c >= '0' && c <= '9') {
        limpio[largo++] = c;
      } else if (c == ',') {
        comas++;
        ultimaComa = largo;
        limpio[largo++] = c;
      } else if (c == '.') {
        puntos++;
        ultimoPunto = largo;
        limpio[largo++] = c;
      } else if (!(c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '\t')) {
        throw noNumerico(texto, fila, columna);
      }
    }
    if (largo == 0 || largo == comas + puntos) {
      throw noNumerico(texto, fila, columna);
    }
    char decimal;
    if (comas > 0 && puntos > 0) {
      boolean comaDecimal = ultimaComa > ultimoPunto;
      decimal = comaDecimal ? ',' : '.';
      if ((comaDecimal ? comas : puntos) > 1) {
        throw noNumerico(texto, fila, columna);
      }
      exigirGrupos(limpio, largo, comaDecimal ? '.' : ',', comaDecimal ? ultimaComa : ultimoPunto,
          texto, fila, columna);
    } else if (comas + puntos == 0) {
      decimal = 0;
    } else {
      char separador = comas > 0 ? ',' : '.';
      int posicion = comas > 0 ? ultimaComa : ultimoPunto;
      if (comas + puntos > 1) {
        exigirGrupos(limpio, largo, separador, largo, texto, fila, columna);
        decimal = 0;
      } else {
        int digitosDespues = largo - posicion - 1;
        boolean ceroInicial = posicion == 1 && limpio[0] == '0';
        boolean miles = digitosDespues == 3 && posicion >= 1 && posicion <= 3 && !ceroInicial;
        decimal = miles ? 0 : separador;
      }
    }
    // Reconstruye "entero[.fraccion]" sin separadores de miles.
    long entero = 0;
    int digitosEnteros = 0;
    int i = 0;
    for (; i < largo && limpio[i] != decimal; i++) {
      char c = limpio[i];
      if (c >= '0' && c <= '9') {
        if (++digitosEnteros > 13) {
          throw demasiadoGrande(fila, columna);
        }
        entero = entero * 10 + (c - '0');
      }
    }
    int inicioFraccion = i + 1;
    int digitosFraccion = Math.max(0, largo - inicioFraccion);
    long resultado;
    if (digitosFraccion <= 2) {
      long fraccion = 0;
      for (int j = inicioFraccion; j < largo; j++) {
        fraccion = fraccion * 10 + (limpio[j] - '0');
      }
      resultado = entero * 100 + (digitosFraccion == 1 ? fraccion * 10 : fraccion);
    } else {
      BigDecimal valor =
          new BigDecimal(entero + "." + new String(limpio, inicioFraccion, digitosFraccion))
              .setScale(2, RoundingMode.HALF_UP);
      resultado = valor.unscaledValue().longValueExact();
    }
    if (resultado > MAX_CENTIMOS) {
      throw demasiadoGrande(fila, columna);
    }
    return resultado;
  }

  /**
   * Céntimos de un número de celda con el mismo redondeo que {@code
   * BigDecimal.valueOf(valor).setScale(2, HALF_UP)}. Camino rápido: si valor×100 es entero (salvo
   * error de representación) se toma directo; si no, BigDecimal.
   */
  static long centimos(double valor, int fila, String columna) {
    if (Double.isNaN(valor) || Double.isInfinite(valor) || valor < 0) {
      throw noNumerico(crudo(valor), fila, columna);
    }
    double escalado = valor * 100;
    double redondo = Math.rint(escalado);
    if (Math.abs(escalado - redondo) < 1e-6 && redondo <= MAX_CENTIMOS) {
      return (long) redondo;
    }
    BigDecimal exacto = BigDecimal.valueOf(valor).setScale(2, RoundingMode.HALF_UP);
    if (exacto.compareTo(MAX_VALOR) > 0) {
      throw demasiadoGrande(fila, columna);
    }
    return exacto.unscaledValue().longValueExact();
  }

  /** Los separadores de miles deben agrupar de a 3 dígitos (antes de {@code hasta}). */
  private static void exigirGrupos(
      char[] limpio, int largo, char miles, int hasta, String texto, int fila, String columna) {
    int digitos = 0;
    boolean primerGrupo = true;
    for (int i = 0; i < hasta; i++) {
      if (limpio[i] == miles) {
        if ((primerGrupo && (digitos < 1 || digitos > 3)) || (!primerGrupo && digitos != 3)) {
          throw noNumerico(texto, fila, columna);
        }
        primerGrupo = false;
        digitos = 0;
      } else {
        digitos++;
      }
    }
    if (!primerGrupo && digitos != 3) {
      throw noNumerico(texto, fila, columna);
    }
  }

  private static ArchivoInvalidoException noNumerico(String texto, int fila, String columna) {
    return ArchivoInvalidoException.contenido(
        "Fila " + fila + ": el valor \"" + recortar(texto.trim()) + "\" de " + columna
            + " no es un número válido.");
  }

  private static ArchivoInvalidoException demasiadoGrande(int fila, String columna) {
    return ArchivoInvalidoException.contenido(
        "Fila " + fila + ": el valor de " + columna + " es demasiado grande.");
  }

  private static String recortar(String texto) {
    return texto.length() <= 40 ? texto : texto.substring(0, 40) + "…";
  }

  private static void exigirLongitud(String texto, String columna, int fila) {
    if (texto.length() > MAX_TEXTO) {
      throw ArchivoInvalidoException.contenido(
          "Fila " + fila + ": " + columna + " supera los " + MAX_TEXTO + " caracteres.");
    }
  }

  private static void exigirFirma(boolean condicion, String extension) {
    if (!condicion) {
      throw ArchivoInvalidoException.ilegible(
          "El archivo " + extension + " no es un libro de Excel válido.");
    }
  }

  private static ArchivoInvalidoException causa(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof ArchivoInvalidoException invalido) {
        return invalido;
      }
      if (t instanceof SAXException sax && sax.getException() instanceof ArchivoInvalidoException e) {
        return e;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return null;
  }

  private static boolean vieneDePoi(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t.getClass().getName().startsWith("org.apache.poi.")) {
        return true;
      }
      StackTraceElement[] pila = t.getStackTrace();
      if (pila.length > 0 && pila[0].getClassName().startsWith("org.apache.poi.")) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }

  private static byte[] firma(MultipartFile archivo) throws IOException {
    try (InputStream entrada = archivo.getInputStream()) {
      return entrada.readNBytes(512);
    }
  }

  private static boolean empiezaCon(byte[] datos, int... esperado) {
    if (datos.length < esperado.length) {
      return false;
    }
    for (int i = 0; i < esperado.length; i++) {
      if ((datos[i] & 0xFF) != esperado[i]) {
        return false;
      }
    }
    return true;
  }

  /** Un CSV es texto: sin bytes nulos (los binarios de Excel/zip los tienen). */
  private static boolean esTexto(byte[] datos) {
    for (byte dato : datos) {
      if (dato == 0) {
        return false;
      }
    }
    return true;
  }

  /** Flujo que corta con 413 al superar un tope de bytes descomprimidos. */
  private static final class EntradaAcotada extends FilterInputStream {
    private final long maximo;
    private final String descripcion;
    private long leidos;

    EntradaAcotada(InputStream entrada, long maximo, String descripcion) {
      super(entrada);
      this.maximo = maximo;
      this.descripcion = descripcion;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b >= 0) {
        contar(1);
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = super.read(b, off, len);
      if (n > 0) {
        contar(n);
      }
      return n;
    }

    private void contar(long n) {
      leidos += n;
      if (leidos > maximo) {
        throw ArchivoInvalidoException.demasiadoGrande(
            descripcion + " supera el tamaño máximo permitido (" + maximo / (1024 * 1024)
                + " MB sin comprimir).");
      }
    }
  }
}
