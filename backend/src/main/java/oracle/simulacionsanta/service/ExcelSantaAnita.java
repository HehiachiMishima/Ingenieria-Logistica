package oracle.simulacionsanta.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.parsers.ParserConfigurationException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.simulacionsanta.response.ErrorFila;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
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
 * Lee el Excel de despacho de Santa Anita (.xlsx, .xls o .csv con coma o punto y coma): una fila
 * por paleta, y una paleta es un solo local con su ruta, su cluster y todos sus bultos de los 3
 * envíos (un local no se repite en el archivo).
 *
 * <ul>
 *   <li>Encabezado: la primera fila, dentro de las {@value #FILAS_CABECERA} primeras, con una celda
 *       RUTA o RUTAS. Las columnas se reconocen por nombre sin importar mayúsculas, tildes,
 *       espacios ni signos: RUTA|RUTAS, LOCAL|LOCALES, CLUSTER, ENVIO1|1ENVIO|PRIMERENVIO,
 *       ENVIO2|2ENVIO|SEGUNDOENVIO y ENVIO3|3ENVIO|TERCERENVIO (BULTOS u otras se ignoran). Si
 *       falta una obligatoria, 422 «E1 · …» con la lista.
 *   <li>Filas: se ignoran las vacías (sin ninguna de esas 6 columnas). Una fila con error no se
 *       guarda y se informa con el código de su {@link Regla} (un error por fila, el primero que
 *       se encuentra); las demás sí.
 *   <li>Topes: {@value #MAX_BYTES} bytes (10 MB) y {@value #MAX_FILAS} filas de datos (413).
 * </ul>
 *
 * <p>Memoria acotada: el .xlsx se lee en streaming (SAX) desde un temporal en disco, con tope de
 * bytes descomprimidos para la hoja y la tabla de textos; el .xls (formato binario) ya está
 * acotado por su propio formato y por los 10 MB.
 *
 * <p>Todo archivo rechazado lanza {@link ArchivoInvalidoException} con su 4xx y un mensaje apto
 * para el cliente; los detalles técnicos solo van al log.
 */
public final class ExcelSantaAnita {

  private static final Logger log = LoggerFactory.getLogger(ExcelSantaAnita.class);

  public static final long MAX_BYTES = 10L * 1024 * 1024;
  public static final int MAX_FILAS = 5_000;
  /** El encabezado se busca en estas primeras filas (puede haber un título encima). */
  public static final int FILAS_CABECERA = 30;
  public static final int MAX_RUTA = 50;
  public static final int MAX_LOCAL = 150;
  /** Bultos máximos de una paleta en un envío (mantiene los totales dentro de INTEGER). */
  public static final int MAX_BULTOS = 99_999;
  /** Errores que se devuelven (el total se informa aparte). */
  public static final int MAX_ERRORES = 50;
  /** Solo se leen las primeras columnas. */
  static final int MAX_COLUMNAS = 50;
  private static final int ERRORES_EN_MENSAJE = 5;
  /** XML de la hoja sin comprimir: 5 mil filas reales son unos pocos MB. */
  private static final long MAX_BYTES_HOJA = 100L * 1024 * 1024;
  /** sharedStrings.xml sin comprimir (se carga entero en memoria). */
  private static final long MAX_BYTES_CADENAS = 24L * 1024 * 1024;

  private static final Pattern ESPACIOS = Pattern.compile("[\\s\\u00A0\\u2007\\u202F\\uFEFF]+");
  private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
  private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^A-Z0-9]");
  /** Un número entero escrito como decimal (2001.0): se lee como «2001». */
  private static final Pattern ENTERO_CON_CEROS = Pattern.compile("^(\\d+)[.,]0+$");
  private static final Pattern NUMERO = Pattern.compile("^(-)?(\\d+)(?:[.,](\\d+))?$");
  private static final Pattern CLUSTER_NUMERO = Pattern.compile("^0*(\\d{1,9})(?:[.,]0+)?$");
  private static final Pattern CLUSTER_TEXTO = Pattern.compile("^(?:C|CL|CLUSTER)0*([1-3])$");
  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

  /** Columnas obligatorias en el orden en que se informan. */
  private static final List<String> OBLIGATORIAS =
      List.of("RUTA", "LOCAL", "CLUSTER", "ENVIO1", "ENVIO2", "ENVIO3");
  private static final Map<String, String> NOMBRES =
      Map.of(
          "RUTA", "RUTA",
          "LOCAL", "LOCAL",
          "CLUSTER", "CLUSTER",
          "ENVIO1", "ENVÍO 1",
          "ENVIO2", "ENVÍO 2",
          "ENVIO3", "ENVÍO 3");

  private ExcelSantaAnita() {}

  /** Reglas del archivo: el código viaja en cada ErrorFila (E1, en el mensaje del 422). */
  public enum Regla {
    /** Faltan columnas obligatorias en el encabezado (422, ninguna fila se lee). */
    E1,
    /** RUTA vacía o de más de {@value ExcelSantaAnita#MAX_RUTA} caracteres. */
    E2,
    /** LOCAL vacío o de más de {@value ExcelSantaAnita#MAX_LOCAL} caracteres. */
    E3,
    /** CLUSTER vacío o que no es 1, 2 ni 3. */
    E4,
    /** Envío que no es número, negativo, con decimales o de más de 99.999 bultos. */
    E5,
    /** La suma de los 3 envíos es 0. */
    E6,
    /** El LOCAL ya está en otra fila: una paleta lleva un solo local con todos sus bultos. */
    E7
  }

  /**
   * Una fila válida: una paleta.
   *
   * @param orden número de fila en el Excel (1 = primera)
   */
  public record Fila(
      int orden, String ruta, String local, int cluster, int envio1, int envio2, int envio3) {}

  /**
   * Lo leído de un archivo.
   *
   * @param filasLeidas filas de datos no vacías (válidas y con error)
   * @param errores los primeros {@value #MAX_ERRORES}
   * @param totalErrores todas las filas con error (= filas omitidas)
   */
  public record Resultado(
      String nombreArchivo,
      int filasLeidas,
      List<Fila> filas,
      List<ErrorFila> errores,
      int totalErrores) {

    /** Rutas distintas (sin distinguir mayúsculas). */
    public int rutas() {
      Set<String> rutas = new HashSet<>();
      filas.forEach((f) -> rutas.add(f.ruta().toLowerCase(Locale.ROOT)));
      return rutas.size();
    }

    /** Bultos de cada envío. */
    public List<Integer> bultosEnvios() {
      int e1 = 0;
      int e2 = 0;
      int e3 = 0;
      for (Fila f : filas) {
        e1 += f.envio1();
        e2 += f.envio2();
        e3 += f.envio3();
      }
      return List.of(e1, e2, e3);
    }

    public int bultos() {
      return bultosEnvios().stream().mapToInt(Integer::intValue).sum();
    }

    /** Paletas de C1, C2 y C3. */
    public List<Integer> paletasCluster() {
      int[] cuenta = new int[3];
      filas.forEach((f) -> cuenta[f.cluster() - 1]++);
      return List.of(cuenta[0], cuenta[1], cuenta[2]);
    }

    /** Bultos por cluster (filas C1, C2, C3) y envío (columnas envío 1, 2, 3). */
    public List<List<Integer>> bultosClusterEnvio() {
      int[][] bultos = new int[3][3];
      for (Fila f : filas) {
        int[] cluster = bultos[f.cluster() - 1];
        cluster[0] += f.envio1();
        cluster[1] += f.envio2();
        cluster[2] += f.envio3();
      }
      return List.of(
          List.of(bultos[0][0], bultos[0][1], bultos[0][2]),
          List.of(bultos[1][0], bultos[1][1], bultos[1][2]),
          List.of(bultos[2][0], bultos[2][1], bultos[2][2]));
    }
  }

  /**
   * Archivo rechazado. Extiende SolicitudInvalidaException (400) por si escapa del endpoint; el
   * endpoint lo responde con {@link #estado()} y, si hay, el detalle por fila en {@code campos}.
   */
  public static final class ArchivoInvalidoException extends SolicitudInvalidaException {
    private final HttpStatus estado;
    private final transient Map<String, String> detalle;

    ArchivoInvalidoException(HttpStatus estado, String mensaje, Map<String, String> detalle) {
      super(mensaje);
      this.estado = estado;
      this.detalle = detalle;
    }

    public HttpStatus estado() {
      return estado;
    }

    /** Errores por fila («fila 7» → mensaje) cuando ninguna fila es válida; null si no aplica. */
    public Map<String, String> detalle() {
      return detalle;
    }

    /** 400: sin archivo, vacío, dañado o con una firma que no coincide con la extensión. */
    public static ArchivoInvalidoException ilegible(String mensaje) {
      return new ArchivoInvalidoException(HttpStatus.BAD_REQUEST, mensaje, null);
    }

    /** 413: supera el tamaño o las filas permitidas. */
    public static ArchivoInvalidoException demasiadoGrande(String mensaje) {
      return new ArchivoInvalidoException(HttpStatus.CONTENT_TOO_LARGE, mensaje, null);
    }

    /** 415: extensión no soportada. */
    public static ArchivoInvalidoException noSoportado(String mensaje) {
      return new ArchivoInvalidoException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, mensaje, null);
    }

    /** 422: se lee, pero el contenido no sirve (encabezado, ninguna fila válida). */
    public static ArchivoInvalidoException contenido(String mensaje) {
      return new ArchivoInvalidoException(HttpStatus.UNPROCESSABLE_CONTENT, mensaje, null);
    }

    static ArchivoInvalidoException contenido(String mensaje, Map<String, String> detalle) {
      return new ArchivoInvalidoException(HttpStatus.UNPROCESSABLE_CONTENT, mensaje, detalle);
    }
  }

  private enum Formato {
    XLSX,
    XLS,
    CSV
  }

  /** Lo que se comprueba sin leer el contenido: que haya archivo, su nombre, tamaño y extensión. */
  public static void verificar(MultipartFile archivo) {
    if (archivo == null || archivo.isEmpty()) {
      throw ArchivoInvalidoException.ilegible("Selecciona un archivo .xlsx, .xls o .csv.");
    }
    String nombre = archivo.getOriginalFilename();
    if (nombre == null || nombre.isBlank()) {
      throw ArchivoInvalidoException.ilegible("El archivo no tiene nombre.");
    }
    if (archivo.getSize() > MAX_BYTES) {
      throw ArchivoInvalidoException.demasiadoGrande("El archivo supera el tamaño máximo de 10 MB.");
    }
    formato(nombre);
  }

  /** Lee y valida el archivo; no guarda nada. */
  public static Resultado leer(MultipartFile archivo) {
    verificar(archivo);
    String nombre = archivo.getOriginalFilename();
    Procesador procesador = new Procesador();
    try {
      byte[] firma = firma(archivo);
      switch (formato(nombre)) {
        case XLSX -> {
          exigirFirma(empiezaCon(firma, 0x50, 0x4B, 0x03, 0x04), ".xlsx");
          leerXlsx(archivo, procesador);
        }
        case XLS -> {
          exigirFirma(empiezaCon(firma, 0xD0, 0xCF, 0x11, 0xE0), ".xls");
          try (InputStream entrada = archivo.getInputStream()) {
            leerXls(entrada, procesador);
          }
        }
        case CSV -> leerCsv(archivo.getBytes(), procesador);
      }
    } catch (IOException e) {
      log.warn("No se pudo leer el archivo de Santa Anita '{}'", nombre, e);
      throw ArchivoInvalidoException.ilegible(
          "No se pudo leer el archivo. Verifica que no esté dañado y vuelve a subirlo.");
    } catch (ArchivoInvalidoException e) {
      throw e;
    } catch (RuntimeException e) {
      ArchivoInvalidoException invalido = causa(e);
      if (invalido != null) {
        throw invalido;
      }
      if (!vieneDePoi(e)) {
        throw e;
      }
      // POI lanza excepciones no comprobadas ante libros dañados, cifrados o muy antiguos.
      log.warn("POI rechazó el archivo de Santa Anita '{}'", nombre, e);
      throw ArchivoInvalidoException.ilegible(
          "El archivo está dañado, protegido con contraseña o en un formato de Excel no soportado.");
    }
    return procesador.resultado(nombre);
  }

  // ------------------------------------------------------------------
  // Reglas por fila
  // ------------------------------------------------------------------

  /** Recibe las filas en orden, busca el encabezado y valida cada fila de datos. */
  private static final class Procesador {
    private boolean cabecera;
    private boolean huboTexto;
    private int filaCabecera;
    private int iRuta;
    private int iLocal;
    private int iCluster;
    private int iEnvio1;
    private int iEnvio2;
    private int iEnvio3;
    private int filasLeidas;
    private int totalErrores;
    private final List<Fila> filas = new ArrayList<>();
    private final List<ErrorFila> errores = new ArrayList<>();
    /** Local normalizado ({@link #claveLocal}) → fila válida donde apareció primero. */
    private final Map<String, Integer> locales = new HashMap<>();

    void fila(int numero, String[] celdas) {
      if (!cabecera) {
        buscarCabecera(numero, celdas);
        return;
      }
      String ruta = celda(celdas, iRuta);
      String local = celda(celdas, iLocal);
      String cluster = celda(celdas, iCluster);
      String envio1 = celda(celdas, iEnvio1);
      String envio2 = celda(celdas, iEnvio2);
      String envio3 = celda(celdas, iEnvio3);
      if (vacio(ruta)
          && vacio(local)
          && vacio(cluster)
          && vacio(envio1)
          && vacio(envio2)
          && vacio(envio3)) {
        return;
      }
      if (++filasLeidas > MAX_FILAS) {
        throw ArchivoInvalidoException.demasiadoGrande(
            "El archivo supera el máximo de 5.000 filas de datos.");
      }
      try {
        validar(numero, ruta, local, cluster, envio1, envio2, envio3);
      } catch (FilaInvalida e) {
        totalErrores++;
        if (errores.size() < MAX_ERRORES) {
          errores.add(new ErrorFila(numero, e.regla().name(), e.getMessage()));
        }
      }
    }

    private void validar(
        int numero,
        String rutaCruda,
        String localCruda,
        String clusterCruda,
        String envio1Crudo,
        String envio2Crudo,
        String envio3Crudo) {
      String ruta = ruta(rutaCruda);
      if (ruta.isEmpty()) {
        throw new FilaInvalida(Regla.E2, "Falta la RUTA.");
      }
      if (ruta.length() > MAX_RUTA) {
        throw new FilaInvalida(Regla.E2, "La RUTA supera los " + MAX_RUTA + " caracteres.");
      }
      String local = texto(localCruda);
      if (local.isEmpty()) {
        throw new FilaInvalida(Regla.E3, "Falta el LOCAL.");
      }
      if (local.length() > MAX_LOCAL) {
        throw new FilaInvalida(Regla.E3, "El LOCAL supera los " + MAX_LOCAL + " caracteres.");
      }
      if (vacio(clusterCruda)) {
        throw new FilaInvalida(Regla.E4, "Falta el CLUSTER.");
      }
      Integer cluster = cluster(clusterCruda);
      if (cluster == null) {
        throw new FilaInvalida(
            Regla.E4,
            "El CLUSTER «" + recortar(texto(clusterCruda))
                + "» no es válido: usa 1, 2 o 3 (también C1, CL1 o CLUSTER 1).");
      }
      int envio1 = envio(envio1Crudo, "ENVÍO 1");
      int envio2 = envio(envio2Crudo, "ENVÍO 2");
      int envio3 = envio(envio3Crudo, "ENVÍO 3");
      if (envio1 + envio2 + envio3 <= 0) {
        throw new FilaInvalida(Regla.E6, "La suma de los 3 envíos debe ser mayor que 0.");
      }
      // Una paleta = un solo local con todos sus bultos: el local no se repite en el archivo,
      // aunque cambie la ruta o el cluster.
      Integer previa = locales.putIfAbsent(claveLocal(local), numero);
      if (previa != null) {
        throw new FilaInvalida(
            Regla.E7,
            "El local «" + recortar(local) + "» ya está en la fila " + previa
                + ": una paleta lleva un solo local con todos sus bultos.");
      }
      filas.add(new Fila(numero, ruta, local, cluster, envio1, envio2, envio3));
    }

    private void buscarCabecera(int numero, String[] celdas) {
      if (numero > FILAS_CABECERA) {
        throw sinCabecera();
      }
      Map<String, Integer> columnas = new HashMap<>();
      boolean conTexto = false;
      for (int i = 0; i < celdas.length; i++) {
        if (!vacio(celdas[i])) {
          conTexto = true;
        }
        String columna = columna(clave(celdas[i]));
        if (columna != null) {
          columnas.putIfAbsent(columna, i);
        }
      }
      huboTexto |= conTexto;
      if (!columnas.containsKey("RUTA")) {
        return;
      }
      List<String> faltan =
          OBLIGATORIAS.stream().filter((c) -> !columnas.containsKey(c)).map(NOMBRES::get).toList();
      if (!faltan.isEmpty()) {
        throw ArchivoInvalidoException.contenido(
            Regla.E1.name() + " · Faltan columnas obligatorias en el encabezado (fila " + numero
                + "): " + String.join(", ", faltan) + ".");
      }
      cabecera = true;
      filaCabecera = numero;
      iRuta = columnas.get("RUTA");
      iLocal = columnas.get("LOCAL");
      iCluster = columnas.get("CLUSTER");
      iEnvio1 = columnas.get("ENVIO1");
      iEnvio2 = columnas.get("ENVIO2");
      iEnvio3 = columnas.get("ENVIO3");
    }

    Resultado resultado(String nombreArchivo) {
      if (!cabecera) {
        throw huboTexto
            ? sinCabecera()
            : ArchivoInvalidoException.contenido("La hoja está vacía.");
      }
      if (filasLeidas == 0) {
        throw ArchivoInvalidoException.contenido(
            "El archivo no tiene filas de datos debajo del encabezado (fila " + filaCabecera + ").");
      }
      if (filas.isEmpty()) {
        Map<String, String> detalle = new LinkedHashMap<>();
        errores.forEach((e) -> detalle.put("fila " + e.fila(), e.mensaje()));
        String primeros =
            errores.stream()
                .limit(ERRORES_EN_MENSAJE)
                .map((e) -> "Fila " + e.fila() + ": " + e.mensaje())
                .collect(Collectors.joining(" "));
        int resto = totalErrores - Math.min(ERRORES_EN_MENSAJE, errores.size());
        throw ArchivoInvalidoException.contenido(
            "El archivo no tiene ninguna fila válida (" + totalErrores + " con error). " + primeros
                + (resto > 0 ? " Y " + resto + " más." : ""),
            detalle);
      }
      return new Resultado(
          nombreArchivo,
          filasLeidas,
          List.copyOf(filas),
          List.copyOf(errores),
          totalErrores);
    }

    private static String celda(String[] celdas, int indice) {
      return indice < celdas.length && celdas[indice] != null ? celdas[indice] : "";
    }
  }

  private static ArchivoInvalidoException sinCabecera() {
    return ArchivoInvalidoException.contenido(
        "No se encontró el encabezado: ninguna de las primeras " + FILAS_CABECERA
            + " filas tiene una celda RUTA.");
  }

  /** Columna conocida a partir de la clave del encabezado; null si se ignora. */
  private static String columna(String clave) {
    return switch (clave) {
      case "RUTA", "RUTAS" -> "RUTA";
      case "LOCAL", "LOCALES" -> "LOCAL";
      case "CLUSTER" -> "CLUSTER";
      case "ENVIO1", "1ENVIO", "PRIMERENVIO" -> "ENVIO1";
      case "ENVIO2", "2ENVIO", "SEGUNDOENVIO" -> "ENVIO2";
      case "ENVIO3", "3ENVIO", "TERCERENVIO" -> "ENVIO3";
      default -> null;
    };
  }

  /** Error de una fila: no corta la lectura. Sin traza (es un flujo normal). */
  private static final class FilaInvalida extends RuntimeException {
    private final Regla regla;

    FilaInvalida(Regla regla, String mensaje) {
      super(mensaje, null, false, false);
      this.regla = regla;
    }

    Regla regla() {
      return regla;
    }
  }

  /**
   * Clave de un local para compararlo: recortado, con los espacios colapsados, sin tildes y sin
   * distinguir mayúsculas («  Plaza  Vea  Lima » = «plaza vea LIMÁ»).
   */
  static String claveLocal(String local) {
    String sinTildes =
        MARCAS.matcher(Normalizer.normalize(texto(local), Normalizer.Form.NFD)).replaceAll("");
    return sinTildes.toUpperCase(Locale.ROOT);
  }

  /**
   * Clave de un encabezado: sin tildes, en mayúsculas y solo letras y dígitos («Envío 1» →
   * «ENVIO1», «1° envío» → «1ENVIO»).
   */
  static String clave(String texto) {
    if (texto == null || texto.isEmpty()) {
      return "";
    }
    String sinTildes = MARCAS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("");
    return NO_ALFANUMERICO.matcher(sinTildes.toUpperCase(Locale.ROOT)).replaceAll("");
  }

  /** Recortado y con los espacios internos (también los no separables) reducidos a uno. */
  static String texto(String valor) {
    if (valor == null) {
      return "";
    }
    return ESPACIOS.matcher(valor).replaceAll(" ").trim();
  }

  /** La ruta como texto: un número escrito como 2001.0 se lee «2001». */
  static String ruta(String valor) {
    String limpio = texto(valor);
    Matcher entero = ENTERO_CON_CEROS.matcher(limpio);
    return entero.matches() ? entero.group(1) : limpio;
  }

  /** 1, 2 o 3 (también «C1», «CL1», «CLUSTER 1»); null si no es ninguno. */
  static Integer cluster(String valor) {
    String limpio = texto(valor);
    Matcher numero = CLUSTER_NUMERO.matcher(limpio);
    if (numero.matches()) {
      int cluster = Integer.parseInt(numero.group(1));
      return cluster >= 1 && cluster <= 3 ? cluster : null;
    }
    Matcher conPrefijo = CLUSTER_TEXTO.matcher(clave(limpio));
    return conPrefijo.matches() ? Integer.valueOf(conPrefijo.group(1)) : null;
  }

  /** Bultos de un envío: entero de 0 a {@value #MAX_BULTOS}; vacío = 0. */
  static int envio(String valor, String columna) {
    String limpio = valor == null ? "" : ESPACIOS.matcher(valor).replaceAll("");
    if (limpio.isEmpty()) {
      return 0;
    }
    Matcher numero = NUMERO.matcher(limpio);
    if (!numero.matches()) {
      throw new FilaInvalida(
          Regla.E5, columna + " «" + recortar(texto(valor)) + "» no es un número.");
    }
    String decimales = numero.group(3);
    if (decimales != null && !decimales.chars().allMatch((c) -> c == '0')) {
      throw new FilaInvalida(
          Regla.E5, columna + " «" + recortar(texto(valor)) + "» debe ser un número entero.");
    }
    String entero = numero.group(2).replaceFirst("^0+(?=\\d)", "");
    if (numero.group(1) != null && !"0".equals(entero)) {
      throw new FilaInvalida(Regla.E5, columna + " no puede ser negativo.");
    }
    if (entero.length() > 5 || Integer.parseInt(entero) > MAX_BULTOS) {
      throw new FilaInvalida(Regla.E5, columna + " supera el máximo de 99.999 bultos.");
    }
    return Integer.parseInt(entero);
  }

  private static boolean vacio(String valor) {
    return valor == null || valor.isBlank() || texto(valor).isEmpty();
  }

  private static String recortar(String texto) {
    return texto.length() <= 40 ? texto : texto.substring(0, 40) + "…";
  }

  // ------------------------------------------------------------------
  // Lectores
  // ------------------------------------------------------------------

  /** .xlsx en streaming: el paquete se abre desde un temporal y la primera hoja se lee con SAX. */
  private static void leerXlsx(MultipartFile archivo, Procesador procesador) throws IOException {
    Path temporal = Files.createTempFile("santa-anita-", ".xlsx");
    try {
      try (InputStream entrada = archivo.getInputStream()) {
        Files.copy(entrada, temporal, StandardCopyOption.REPLACE_EXISTING);
      }
      try (OPCPackage paquete = OPCPackage.open(temporal.toFile(), PackageAccess.READ)) {
        XSSFReader lector = new XSSFReader(paquete);
        var hojas = lector.getSheetsData();
        if (!hojas.hasNext()) {
          throw ArchivoInvalidoException.contenido("El archivo no contiene hojas.");
        }
        SharedStrings cadenas = cadenas(paquete);
        StylesTable estilos = lector.getStylesTable();
        if (estilos == null) {
          estilos = new StylesTable();
        }
        XMLReader parser = XMLHelper.newXMLReader();
        parser.setContentHandler(
            new XSSFSheetXMLHandler(
                estilos, cadenas, new HojaXlsx(procesador), new FormatoCrudo(), false));
        try (InputStream hoja = new EntradaAcotada(hojas.next(), MAX_BYTES_HOJA, "La hoja")) {
          parser.parse(new InputSource(hoja));
        }
      } catch (OpenXML4JException | SAXException | ParserConfigurationException e) {
        ArchivoInvalidoException invalido = causa(e);
        if (invalido != null) {
          throw invalido;
        }
        log.warn("Libro .xlsx ilegible '{}'", archivo.getOriginalFilename(), e);
        throw ArchivoInvalidoException.ilegible(
            "El archivo .xlsx está dañado o no es un libro de Excel.");
      }
    } finally {
      Files.deleteIfExists(temporal);
    }
  }

  /** sharedStrings.xml se carga entero: se lee con tope de bytes descomprimidos. */
  private static SharedStrings cadenas(OPCPackage paquete) throws IOException, SAXException {
    List<PackagePart> partes =
        paquete.getPartsByContentType(XSSFRelation.SHARED_STRINGS.getContentType());
    if (partes.isEmpty()) {
      return new ReadOnlySharedStringsTable(paquete, false);
    }
    try (InputStream entrada =
        new EntradaAcotada(partes.get(0).getInputStream(), MAX_BYTES_CADENAS, "La tabla de textos")) {
      return new ReadOnlySharedStringsTable(entrada, false);
    }
  }

  /** Celdas de cada fila de la hoja .xlsx (las vacías no llegan: se ubican por su referencia). */
  private static final class HojaXlsx implements SheetContentsHandler {
    private final Procesador procesador;
    private final String[] celdas = new String[MAX_COLUMNAS];
    private int tamano;

    HojaXlsx(Procesador procesador) {
      this.procesador = procesador;
    }

    @Override
    public void startRow(int fila) {
      Arrays.fill(celdas, 0, tamano, null);
      tamano = 0;
    }

    @Override
    public void endRow(int fila) {
      procesador.fila(fila + 1, Arrays.copyOf(celdas, tamano));
    }

    @Override
    public void cell(String referencia, String valor, XSSFComment comentario) {
      int columna = referencia == null ? tamano : columna(referencia);
      if (columna < 0 || columna >= MAX_COLUMNAS) {
        return;
      }
      celdas[columna] = valor;
      tamano = Math.max(tamano, columna + 1);
    }

    /** Columna (0..n) de una referencia «AB12». */
    private static int columna(String referencia) {
      int columna = 0;
      for (int i = 0; i < referencia.length(); i++) {
        char c = Character.toUpperCase(referencia.charAt(i));
        if (c < 'A' || c > 'Z') {
          break;
        }
        columna = columna * 26 + (c - 'A' + 1);
        if (columna > MAX_COLUMNAS) {
          return MAX_COLUMNAS;
        }
      }
      return columna - 1;
    }
  }

  /**
   * Las celdas numéricas se leen por su valor y no por su formato: con el formato «0», 12.5 se
   * vería «13» y un decimal pasaría por entero.
   */
  private static final class FormatoCrudo extends DataFormatter {
    FormatoCrudo() {
      super(Locale.ROOT);
    }

    @Override
    public String formatRawCellContents(double valor, int indice, String formato) {
      return numero(valor);
    }

    @Override
    public String formatRawCellContents(
        double valor, int indice, String formato, boolean fechas1904) {
      return numero(valor);
    }
  }

  /** .xls: primera hoja del libro. */
  private static void leerXls(InputStream entrada, Procesador procesador) throws IOException {
    try (HSSFWorkbook libro = new HSSFWorkbook(entrada)) {
      if (libro.getNumberOfSheets() == 0) {
        throw ArchivoInvalidoException.contenido("El archivo no contiene hojas.");
      }
      Sheet hoja = libro.getSheetAt(0);
      for (Row registro : hoja) {
        int ultima = Math.min(Math.max(registro.getLastCellNum(), 0), MAX_COLUMNAS);
        String[] celdas = new String[ultima];
        for (int c = 0; c < ultima; c++) {
          celdas[c] = valorCelda(registro.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
        }
        procesador.fila(registro.getRowNum() + 1, celdas);
      }
    }
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
      case NUMERIC -> numero(celda.getNumericCellValue());
      case STRING -> celda.getStringCellValue();
      case BOOLEAN -> celda.getBooleanCellValue() ? "TRUE" : "FALSE";
      default -> "";
    };
  }

  /**
   * CSV con coma o punto y coma (el que hace aparecer la celda RUTA en las primeras filas).
   * Codificación: BOM UTF-8/UTF-16; sin BOM, UTF-8 si todo el archivo lo es y si no Windows-1252
   * (lo que guarda Excel en Windows). Respeta comillas, incluidas las que encierran saltos de línea.
   */
  private static void leerCsv(byte[] contenido, Procesador procesador) {
    String texto = decodificar(contenido);
    char separador = separador(texto);
    int n = texto.length();
    int i = !texto.isEmpty() && texto.charAt(0) == '﻿' ? 1 : 0;
    int linea = 1;
    List<String> celdas = new ArrayList<>();
    StringBuilder actual = new StringBuilder(64);
    while (i < n) {
      int inicio = linea;
      celdas.clear();
      actual.setLength(0);
      boolean comillas = false;
      while (i < n) {
        char c = texto.charAt(i++);
        if (comillas) {
          if (c == '"') {
            if (i < n && texto.charAt(i) == '"') {
              actual.append('"');
              i++;
            } else {
              comillas = false;
            }
          } else {
            if (c == '\n') {
              linea++;
            }
            actual.append(c);
          }
        } else if (c == '"') {
          comillas = true;
        } else if (c == separador) {
          agregar(celdas, actual);
        } else if (c == '\n') {
          linea++;
          break;
        } else if (c != '\r') {
          actual.append(c);
        }
      }
      agregar(celdas, actual);
      procesador.fila(inicio, celdas.toArray(String[]::new));
    }
  }

  private static void agregar(List<String> celdas, StringBuilder actual) {
    if (celdas.size() < MAX_COLUMNAS) {
      celdas.add(actual.toString());
    }
    actual.setLength(0);
  }

  private static String decodificar(byte[] contenido) {
    if (empiezaCon(contenido, 0xEF, 0xBB, 0xBF)) {
      return new String(contenido, 3, contenido.length - 3, StandardCharsets.UTF_8);
    }
    if (empiezaCon(contenido, 0xFF, 0xFE)) {
      return new String(contenido, 2, contenido.length - 2, StandardCharsets.UTF_16LE);
    }
    if (empiezaCon(contenido, 0xFE, 0xFF)) {
      return new String(contenido, 2, contenido.length - 2, StandardCharsets.UTF_16BE);
    }
    for (int i = 0; i < Math.min(contenido.length, 512); i++) {
      if (contenido[i] == 0) {
        throw ArchivoInvalidoException.ilegible("El archivo .csv no es de texto.");
      }
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(contenido))
          .toString();
    } catch (CharacterCodingException e) {
      return new String(contenido, WINDOWS_1252);
    }
  }

  /** «;» o «,»: el que deja una celda RUTA en alguna de las primeras filas; si ninguno, «,». */
  private static char separador(String texto) {
    String[] lineas = texto.split("\r?\n", FILAS_CABECERA + 1);
    for (int i = 0; i < Math.min(lineas.length, FILAS_CABECERA); i++) {
      for (char separador : new char[] {';', ','}) {
        for (String celda : dividir(lineas[i], separador)) {
          if ("RUTA".equals(columna(clave(celda)))) {
            return separador;
          }
        }
      }
    }
    return ',';
  }

  private static List<String> dividir(String linea, char separador) {
    if (linea.indexOf(separador) < 0) {
      return Collections.singletonList(linea.replace("\"", ""));
    }
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
  // Utilidades
  // ------------------------------------------------------------------

  /** Texto de un número de celda: «2001» (no «2001.0»), «12.5», «0.25». */
  static String numero(double valor) {
    if (Double.isNaN(valor) || Double.isInfinite(valor)) {
      return Double.toString(valor);
    }
    if (valor == Math.rint(valor) && Math.abs(valor) < 1e15) {
      return Long.toString((long) valor);
    }
    return BigDecimal.valueOf(valor).stripTrailingZeros().toPlainString();
  }

  private static Formato formato(String nombre) {
    String minusculas = nombre.toLowerCase(Locale.ROOT).trim();
    if (minusculas.endsWith(".xlsx")) {
      return Formato.XLSX;
    }
    if (minusculas.endsWith(".xls")) {
      return Formato.XLS;
    }
    if (minusculas.endsWith(".csv")) {
      return Formato.CSV;
    }
    throw ArchivoInvalidoException.noSoportado(
        "Formato no soportado. Usa un archivo .xlsx, .xls o .csv.");
  }

  private static byte[] firma(MultipartFile archivo) throws IOException {
    try (InputStream entrada = archivo.getInputStream()) {
      return entrada.readNBytes(512);
    }
  }

  private static void exigirFirma(boolean condicion, String extension) {
    if (!condicion) {
      throw ArchivoInvalidoException.ilegible(
          "El archivo " + extension + " no es un libro de Excel válido.");
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

  /** Flujo que corta con 413 al superar un tope de bytes descomprimidos (libros «bomba»). */
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
            descripcion + " del libro supera el tamaño máximo permitido sin comprimir.");
      }
    }
  }
}
