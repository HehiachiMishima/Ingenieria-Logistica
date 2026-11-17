package oracle.simulacion.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import oracle.simulacion.response.DistribucionResponse;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Color;
import org.apache.poi.ss.usermodel.ConditionalFormattingThreshold;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFColorScaleFormatting;
import org.apache.poi.xssf.usermodel.XSSFConditionalFormattingRule;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFSheetConditionalFormatting;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Hoja «Distribución» con estilo ejecutivo: encabezado de Farmacias Peruanas, banda de la marca
 * ATLAS (línea roja, isotipo y wordmark como en el login, línea dorada), indicadores, leyenda y un
 * bloque por cluster con todos los chutes en columnas (buff en rojo, bloqueados angostos en gris).
 * Cada ruta es una ficha como la de la pantalla, una por celda: borde grueso y número del color de
 * su grupo, código con el color de su frecuencia, bultos y la letra de la frecuencia. Debajo, los
 * bultos de cada día con una escala de color. Lista para imprimir: A3 horizontal, al ancho de una
 * página.
 */
final class DistribucionExcel {

  private static final ZoneId ZONA = ZoneId.of("America/Lima");
  private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
  /** Alto del logo de Farmacias Peruanas y de la marca ATLAS (px a 96 ppp). */
  private static final int ALTO_FP = 66;
  private static final int ALTO_MARCA = 54;

  // Paleta Atlas / Farmacias Peruanas.
  static final String NAVY = "0B1F3A";
  static final String NAVY_2 = "13294B";
  static final String ROJO = "E11D2E";
  static final String ORO = "F7C400";
  static final String AZUL = "1D63ED";
  static final String AMBAR = "B45309";
  static final String TINTA = "0F172A";
  /** Bultos: texto café sobre el tono kraft de la caja, como la píldora de la pantalla. */
  static final String CAFE = "7A4A1C";
  private static final String KRAFT = "C8894A";
  private static final String ROJO_SUAVE = "FFF4F5";
  private static final String GRIS = "64748B";
  private static final String GRIS_CLARO = "94A3B8";
  private static final String SUPERFICIE = "F5F7FB";
  private static final String BORDE = "D5DDE8";
  private static final String BORDE_FICHA = "E2E8F0";
  private static final String BLOQ = "E2E8F0";
  private static final String NAVY_SUAVE = "E8EDF4";
  private static final String GUION = "CBD5E1";

  /** Imágenes del encabezado; cualquiera puede faltar (null). */
  record Marcas(byte[] fp, byte[] atlas) {
    static final Marcas NINGUNA = new Marcas(null, null);
  }

  private final DistribucionResponse d;
  private final XSSFWorkbook libro;
  private final XSSFSheet hoja;
  private final int ultima;
  private final Map<String, XSSFCellStyle> estilos = new HashMap<>();
  private final Map<String, XSSFFont> fuentes = new HashMap<>();

  private DistribucionExcel(DistribucionResponse d, XSSFWorkbook libro) {
    this.d = d;
    this.libro = libro;
    this.hoja = libro.createSheet("Distribución");
    this.ultima = Math.max(1, d.chutes().size());
  }

  /** El libro con la hoja «Distribución» y las marcas que haya. */
  static byte[] escribir(DistribucionResponse d, Marcas marcas) {
    try (XSSFWorkbook libro = new XSSFWorkbook();
        ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
      new DistribucionExcel(d, libro).hoja(marcas == null ? Marcas.NINGUNA : marcas);
      libro.write(salida);
      return salida.toByteArray();
    } catch (IOException error) {
      throw new UncheckedIOException("No se pudo generar el Excel de la simulación.", error);
    }
  }

  /** «CHUTE 15» aunque el catálogo lo guarde como «15». */
  static String etiquetaChute(String nombre) {
    String limpio = nombre == null ? "" : nombre.trim();
    return limpio.toUpperCase(Locale.ROOT).startsWith("CHUTE") ? limpio.toUpperCase(Locale.ROOT) : "CHUTE " + limpio;
  }

  /** Color de la frecuencia: diaria en tinta, L-X-V en azul, M-J-S en ámbar. */
  static String colorFrecuencia(String frecuencia) {
    return switch (frecuencia) {
      case SimulacionReporte.LXV -> AZUL;
      case SimulacionReporte.MJS -> AMBAR;
      default -> TINTA;
    };
  }

  /** Letra de la pestaña de frecuencia: D, L o M. */
  static String letraFrecuencia(String frecuencia) {
    return switch (frecuencia) {
      case SimulacionReporte.LXV -> "L";
      case SimulacionReporte.MJS -> "M";
      default -> "D";
    };
  }

  /** Texto de la ficha: «1  3013   ■ 465   D» (grupo, código, bultos hacia arriba y frecuencia). */
  static String textoFicha(DistribucionResponse.Ruta r) {
    return (r.grupo() == null ? "" : r.grupo() + "  ")
        + r.codigo()
        + "   ■ "
        + String.format(Locale.US, "%,d", r.bultos().setScale(0, RoundingMode.CEILING).longValue())
        + "   "
        + letraFrecuencia(r.frecuencia());
  }

  private void hoja(Marcas marcas) {
    hoja.setDisplayGridlines(false);
    hoja.setZoom(90);
    hoja.setColumnWidth(0, 19 * 256);
    for (int i = 0; i < d.chutes().size(); i++) {
      hoja.setColumnWidth(i + 1, (d.chutes().get(i).bloqueado() ? 5 : 15) * 256);
    }
    int r = encabezado(marcas);
    r = indicadores(r);
    r = leyenda(r);
    if (d.clusters().isEmpty()) {
      celda(fila(r, 20), 0, "El registro no tiene rutas para mostrar.", estilo("vacio", null, fuente(10, false, GRIS), HorizontalAlignment.LEFT, null, false));
      r += 2;
    }
    for (DistribucionResponse.Cluster c : d.clusters()) {
      r = bloque(r, c) + 1;
    }
    nota(r);
    impresion();
  }

  // ---------------- Encabezado: logo, título y banda ATLAS ----------------

  private int encabezado(Marcas marcas) {
    fila(0, 22);
    fila(1, 26);
    fila(2, 18);
    if (marcas.fp() != null) {
      imagen(marcas.fp(), Workbook.PICTURE_TYPE_JPEG, 0, 4, 12, ALTO_FP);
    }
    XSSFCellStyle titulo = estilo("titulo", null, fuente(20, true, NAVY), HorizontalAlignment.LEFT, null, false);
    XSSFCellStyle sub = estilo("sub", null, fuente(10, false, GRIS), HorizontalAlignment.LEFT, null, false);
    celda(fila(0, 22), 2, "DISTRIBUCIÓN DE RUTAS POR CLUSTER Y CHUTE", titulo);
    unir(0, 1, 2, Math.max(2, ultima));
    String generado = d.generado() == null ? "" : "Generado " + FECHA.format(d.generado().atZone(ZONA));
    // La fecha va a la derecha de la banda; si hay muy pocos chutes no cabe y sigue al subtítulo.
    boolean fechaEnBanda = ultima >= 6;
    celda(fila(2, 18), 2,
        d.registro() + "  ·  " + d.clusters().size() + " cluster(s)  ·  " + d.chutes().size()
            + " chutes  ·  Diarias, L-X-V y M-J-S en un solo bloque por cluster"
            + (fechaEnBanda || generado.isEmpty() ? "" : "  ·  " + generado),
        sub);
    unir(2, 2, 2, Math.max(2, ultima));

    // Banda ATLAS de borde a borde: línea roja arriba, marca (isotipo + wordmark + subtítulo) y
    // fecha, línea dorada abajo.
    fila(3, 8);
    Row banda = fila(4, 52);
    XSSFCellStyle franja = estilos.computeIfAbsent(
        "banda",
        (k) -> {
          XSSFCellStyle s = base(null, fuente(9, false, GRIS), HorizontalAlignment.RIGHT);
          bordes(s, BorderStyle.THICK, ROJO, BorderStyle.THICK, ORO);
          return s;
        });
    for (int c = 0; c <= ultima; c++) {
      celda(banda, c, null, franja);
    }
    if (marcas.atlas() != null) {
      imagen(marcas.atlas(), Workbook.PICTURE_TYPE_PNG, 4, 6, 10, ALTO_MARCA);
    }
    if (fechaEnBanda) {
      int desde = Math.max(4, ultima - 6);
      celda(banda, desde, generado, franja);
      unir(4, 4, desde, ultima);
    }
    return 6;
  }

  /**
   * Imagen desde la columna 0 de {@code fila} (desplazada dx, dy px) con el alto pedido y su
   * proporción. El ancla se calcula aquí: en POI 5, {@code Picture.resize(escala)} escala el ancla
   * actual, no la imagen. Una imagen ilegible no se dibuja.
   */
  private void imagen(byte[] datos, int tipo, int fila, int dx, int dy, int alto) {
    int[] tamano = tamano(datos);
    if (tamano == null) {
      return;
    }
    int ancho = Math.round((float) alto * tamano[0] / tamano[1]);
    int col = 0;
    float x = dx + ancho;
    while (x > hoja.getColumnWidthInPixels(col)) {
      x -= hoja.getColumnWidthInPixels(col++);
    }
    int r = fila;
    float y = dy + alto;
    while (y > altoEnPixeles(r)) {
      y -= altoEnPixeles(r++);
    }
    XSSFClientAnchor ancla =
        new XSSFClientAnchor(
            Units.pixelToEMU(dx), Units.pixelToEMU(dy), Units.pixelToEMU(Math.round(x)), Units.pixelToEMU(Math.round(y)),
            0, fila, col, r);
    ancla.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
    var imagen = hoja.createDrawingPatriarch().createPicture(ancla, libro.addPicture(datos, tipo));
    // Excel ubica la imagen por el ancla; Numbers y Vista Rápida, por la posición absoluta del
    // xfrm, que POI deja relativa a la celda: se corrige para que se vea igual en todos.
    double arriba = 0;
    for (int k = 0; k < fila; k++) {
      arriba += altoEnPixeles(k);
    }
    imagen.getCTPicture().getSpPr().getXfrm().getOff().setY(Units.pixelToEMU((int) Math.round(arriba) + dy));
  }

  private float altoEnPixeles(int r) {
    Row f = hoja.getRow(r);
    return (f == null ? hoja.getDefaultRowHeightInPoints() : f.getHeightInPoints()) * 96 / 72;
  }

  /** Ancho y alto en píxeles de un PNG o JPEG, leídos de su cabecera (sin decodificar); null si no se reconoce. */
  static int[] tamano(byte[] b) {
    if (b.length > 24 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
      return new int[] {entero(b, 16), entero(b, 20)};
    }
    if (b.length > 4 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
      int i = 2;
      while (i + 9 < b.length) {
        if ((b[i] & 0xFF) != 0xFF) {
          i++;
          continue;
        }
        int marca = b[i + 1] & 0xFF;
        if (marca == 0xFF) {
          i++;
          continue;
        }
        int largo = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
        // SOF0..SOF15 (salvo DHT, JPG y DAC) traen alto y ancho.
        if (marca >= 0xC0 && marca <= 0xCF && marca != 0xC4 && marca != 0xC8 && marca != 0xCC) {
          int alto = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
          int ancho = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
          return ancho > 0 && alto > 0 ? new int[] {ancho, alto} : null;
        }
        i += 2 + largo;
      }
    }
    return null;
  }

  private static int entero(byte[] b, int i) {
    return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
  }

  // ---------------- Indicadores ----------------

  private int indicadores(int r) {
    DistribucionResponse.Totales t = d.totales();
    Object[][] kpis = {
      {"CLUSTERS", t.clusters(), NAVY},
      {"RUTAS", t.rutas(), NAVY},
      {"CHUTES EN USO", t.chutesEnUso(), NAVY},
      {"BULTOS L-X-V", t.bultosLxv(), AZUL},
      {"BULTOS M-J-S", t.bultosMjs(), AMBAR},
    };
    Row etiquetas = fila(r, 16);
    Row cifras = fila(r + 1, 30);
    int col = 0;
    for (Object[] k : kpis) {
      // El primero ocupa la columna de rótulos y un chute; los demás, dos chutes.
      int desde = col;
      int hasta = col == 0 ? 1 : col + 1;
      String hex = (String) k[2];
      XSSFCellStyle e = estilo("kpiE", SUPERFICIE, fuente(8, true, GRIS), HorizontalAlignment.CENTER, BORDE, false);
      XSSFCellStyle n = estilo("kpiN" + hex, SUPERFICIE, fuente(18, true, hex), HorizontalAlignment.CENTER, BORDE, false);
      n.setDataFormat(libro.createDataFormat().getFormat("#,##0"));
      for (int c = desde; c <= hasta; c++) {
        celda(etiquetas, c, null, e);
        celda(cifras, c, null, n);
      }
      celda(etiquetas, desde, k[0], e);
      celda(cifras, desde, k[1], n);
      unir(r, r, desde, hasta);
      unir(r + 1, r + 1, desde, hasta);
      col = hasta + 2;
    }
    return r + 3;
  }

  // ---------------- Leyenda ----------------

  private int leyenda(int r) {
    XSSFRichTextString t = new XSSFRichTextString();
    t.append("Ficha de cada ruta:   ", fuente(9, true, GRIS));
    t.append("1", fuente(9, true, SimulacionReporte.PALETA_GRUPOS.getFirst().substring(1).toUpperCase(Locale.ROOT)));
    t.append(" grupo (borde de su color)  ·  número de ruta con el color de su frecuencia:  ", fuente(9, false, GRIS));
    t.append("Diaria (D)", fuente(9, true, TINTA));
    t.append(" sale los dos días   ", fuente(9, false, GRIS));
    t.append("L-X-V (L)", fuente(9, true, AZUL));
    t.append("   ", fuente(9, false, GRIS));
    t.append("M-J-S (M)", fuente(9, true, AMBAR));
    t.append("  ·  ", fuente(9, false, GRIS));
    t.append("■", fuente(9, true, KRAFT));
    t.append(" bultos", fuente(9, true, CAFE));
    t.append("      Cabecera roja = chute con buff   ·   Columna gris = bloqueado", fuente(9, false, GRIS));
    celda(fila(r, 18), 0, t, estilo("leyenda", null, fuente(9, false, GRIS), HorizontalAlignment.LEFT, null, false));
    unir(r, r, 0, ultima);
    return r + 2;
  }

  // ---------------- Bloque de un cluster ----------------

  private int bloque(int r, DistribucionResponse.Cluster c) {
    XSSFCellStyle cabNavy = estilo("cabN", NAVY, fuente(8, true, "FFFFFF"), HorizontalAlignment.CENTER, NAVY_2, false);
    XSSFCellStyle cabBuff = estilo("cabB", ROJO, fuente(8, true, "FFFFFF"), HorizontalAlignment.CENTER, "B4121F", false);
    XSSFCellStyle cabBloq = estilo("cabQ", GUION, fuente(7, true, GRIS), HorizontalAlignment.CENTER, BORDE, false);
    XSSFCellStyle nombre = estilo("nom", NAVY, fuente(14, true, "FFFFFF"), HorizontalAlignment.CENTER, NAVY_2, false);

    Map<Long, DistribucionResponse.Celda> celdas = new HashMap<>();
    c.celdas().forEach((x) -> celdas.put(x.chuteId(), x));

    // Cabecera: nombre del cluster y los chutes.
    Row cab = fila(r, 20);
    celda(cab, 0, c.nombre(), nombre);
    for (int i = 0; i < d.chutes().size(); i++) {
      DistribucionResponse.Chute ch = d.chutes().get(i);
      celda(cab, i + 1, ch.bloqueado() ? "BLOQ." : etiquetaChute(ch.nombre()), ch.bloqueado() ? cabBloq : ch.buff() ? cabBuff : cabNavy);
    }

    // Una fila por ficha: tantas como rutas tenga el chute más cargado del cluster. Sin celdas
    // unidas junto a las fichas: algunos visores alargan la pestaña del grupo por toda la unión.
    int alto = Math.max(1, c.celdas().stream().mapToInt((x) -> x.rutas().size()).max().orElse(1));
    int primera = r + 1;
    int fin = r + alto;
    for (int k = 0; k < alto; k++) {
      fila(primera + k, alto == 1 ? 38 : alto == 2 ? 24 : 18);
    }

    // Resumen del cluster: rutas, diarias y rutas de cada día, repartidos y centrados en sus filas.
    XSSFFont negrita = fuente(10, true, NAVY);
    XSSFFont gris = fuente(8, false, GRIS);
    String rutas = c.rutas() + (c.rutas() == 1 ? " ruta" : " rutas");
    String diarias = c.diarias() + (c.diarias() == 1 ? " diaria" : " diarias");
    String dias = c.lxv() + " L-X-V · " + c.mjs() + " M-J-S";
    List<XSSFRichTextString> lineas = new ArrayList<>();
    if (alto == 1) {
      lineas.add(texto(rutas, negrita, "\n" + diarias + "\n" + dias, gris));
    } else if (alto == 2) {
      lineas.add(texto(rutas, negrita, "\n" + diarias, gris));
      lineas.add(texto("", negrita, dias, gris));
    } else {
      lineas.add(texto(rutas, negrita, "", gris));
      lineas.add(texto("", negrita, diarias, gris));
      lineas.add(texto("", negrita, dias, gris));
    }
    int desde = (alto - lineas.size()) / 2;
    XSSFCellStyle resumen = relleno("res", NAVY_SUAVE, fuente(9, false, NAVY), false);
    for (int k = 0; k < alto; k++) {
      int linea = k - desde;
      celda(hoja.getRow(primera + k), 0, linea >= 0 && linea < lineas.size() ? lineas.get(linea) : null, resumen);
    }

    for (int i = 0; i < d.chutes().size(); i++) {
      DistribucionResponse.Chute ch = d.chutes().get(i);
      int col = i + 1;
      boolean ultimaCol = col == ultima;
      DistribucionResponse.Celda x = ch.bloqueado() || ch.id() == null ? null : celdas.get(ch.id());
      for (int k = 0; k < alto; k++) {
        Row f = hoja.getRow(primera + k);
        if (ch.bloqueado()) {
          celda(f, col, null, relleno("bloq", BLOQ, fuente(10, false, GUION), ultimaCol));
        } else if (x == null || x.rutas().isEmpty()) {
          celda(f, col, k == (alto - 1) / 2 ? "—" : null, relleno("vacia", SUPERFICIE, fuente(10, false, GUION), ultimaCol));
        } else if (k < x.rutas().size()) {
          DistribucionResponse.Ruta ru = x.rutas().get(k);
          celda(f, col, ficha(ru), estiloFicha(ru.color(), ch.buff(), ultimaCol));
        } else {
          celda(f, col, null, relleno("hueco" + ch.buff(), ch.buff() ? ROJO_SUAVE : "FFFFFF", fuente(10, false, TINTA), ultimaCol));
        }
      }
    }

    // Bultos de cada día por chute (diarias + rutas del día).
    int f = totales(fin + 1, "BULTOS L-X-V", c.bultosLxv(), celdas, true, AZUL, "DCE7FD");
    return totales(f, "BULTOS M-J-S", c.bultosMjs(), celdas, false, AMBAR, "FCE3BF");
  }

  /** La ficha de una ruta: número del grupo, código, bultos y letra de la frecuencia, cada uno en su color. */
  private XSSFRichTextString ficha(DistribucionResponse.Ruta r) {
    String texto = textoFicha(r);
    String frecuencia = colorFrecuencia(r.frecuencia());
    String grupo = r.color() == null ? GRIS : r.color().substring(1).toUpperCase(Locale.ROOT);
    XSSFRichTextString t = new XSSFRichTextString(texto);
    int i = 0;
    if (r.grupo() != null) {
      t.applyFont(0, r.grupo().length(), fuente(8, true, grupo));
      i = r.grupo().length() + 2;
    }
    int finCodigo = i + r.codigo().length();
    t.applyFont(i, finCodigo, fuente(10, true, frecuencia));
    int caja = texto.indexOf('■', finCodigo);
    t.applyFont(finCodigo, caja, fuente(8, false, CAFE));
    t.applyFont(caja, caja + 1, fuente(7, false, KRAFT));
    int letra = texto.length() - 1;
    t.applyFont(caja + 1, letra, fuente(8, true, CAFE));
    t.applyFont(letra, texto.length(), fuente(8, true, frecuencia));
    return t;
  }

  /**
   * Ficha: borde izquierdo grueso del color del grupo (gris sin grupo), fondo rosado en un buff. En
   * las filas de fichas solo la última columna lleva borde derecho: el borde de la celda vecina
   * taparía la pestaña del grupo de la siguiente.
   */
  private XSSFCellStyle estiloFicha(String colorGrupo, boolean buff, boolean ultimaCol) {
    String grupo = colorGrupo == null ? null : colorGrupo.substring(1).toUpperCase(Locale.ROOT);
    return estilos.computeIfAbsent(
        "ficha-" + grupo + "-" + buff + "-" + ultimaCol,
        (k) -> {
          XSSFCellStyle s = base(buff ? ROJO_SUAVE : "FFFFFF", fuente(10, true, TINTA), HorizontalAlignment.LEFT);
          s.setIndention((short) 1);
          bordes(s, BorderStyle.THIN, BORDE_FICHA, BorderStyle.THIN, BORDE_FICHA);
          s.setBorderLeft(grupo == null ? BorderStyle.THIN : BorderStyle.THICK);
          s.setLeftBorderColor(color(grupo == null ? BORDE : grupo));
          s.setBorderRight(ultimaCol ? BorderStyle.THIN : BorderStyle.NONE);
          s.setRightBorderColor(color(BORDE));
          return s;
        });
  }

  /**
   * Celda de las filas de fichas que no es ficha (resumen, hueco, chute vacío o bloqueado): fondo
   * de la columna, sin bordes arriba ni abajo (la columna se ve continua) y el borde derecho solo
   * en la última columna.
   */
  private XSSFCellStyle relleno(String clave, String fondo, XSSFFont fuente, boolean ultimaCol) {
    return estilos.computeIfAbsent(
        "relleno-" + clave + "-" + ultimaCol,
        (k) -> {
          XSSFCellStyle s = base(fondo, fuente, HorizontalAlignment.CENTER);
          s.setWrapText(true);
          s.setBorderLeft(BorderStyle.THIN);
          s.setLeftBorderColor(color(BORDE));
          s.setBorderRight(ultimaCol ? BorderStyle.THIN : BorderStyle.NONE);
          s.setRightBorderColor(color(BORDE));
          return s;
        });
  }

  /** Texto con dos tramos: el primero con {@code f1} y el segundo con {@code f2}. */
  private static XSSFRichTextString texto(String t1, XSSFFont f1, String t2, XSSFFont f2) {
    XSSFRichTextString t = new XSSFRichTextString();
    if (!t1.isEmpty()) {
      t.append(t1, f1);
    }
    if (!t2.isEmpty()) {
      t.append(t2, f2);
    }
    return t;
  }

  private int totales(
      int r, String etiqueta, long total, Map<Long, DistribucionResponse.Celda> celdas, boolean lxv, String hex, String escala) {
    XSSFCellStyle et = estilo("et" + hex, "FFFFFF", fuente(8, true, hex), HorizontalAlignment.LEFT, BORDE, false);
    XSSFCellStyle num = estilo("num", "FFFFFF", fuente(9, true, TINTA), HorizontalAlignment.CENTER, BORDE, false);
    num.setDataFormat(libro.createDataFormat().getFormat("#,##0"));
    XSSFCellStyle cero = estilo("cero", "FFFFFF", fuente(9, false, GUION), HorizontalAlignment.CENTER, BORDE, false);
    XSSFCellStyle bloq = estilo("bloqT", BLOQ, fuente(9, false, GUION), HorizontalAlignment.CENTER, BORDE, false);
    Row f = fila(r, 17);
    XSSFRichTextString t = new XSSFRichTextString();
    t.append(etiqueta, fuente(8, true, hex));
    t.append("  " + String.format(Locale.US, "%,d", total), fuente(8, false, GRIS));
    celda(f, 0, t, et);
    String fuera = lxv ? SimulacionReporte.MJS : SimulacionReporte.LXV;
    for (int i = 0; i < d.chutes().size(); i++) {
      DistribucionResponse.Chute ch = d.chutes().get(i);
      DistribucionResponse.Celda x = ch.id() == null ? null : celdas.get(ch.id());
      // El chute trabaja ese día si tiene alguna diaria o ruta de ese día.
      boolean trabaja = x != null && x.rutas().stream().anyMatch((ru) -> !fuera.equals(ru.frecuencia()));
      if (ch.bloqueado()) {
        celda(f, i + 1, "", bloq);
      } else if (!trabaja) {
        celda(f, i + 1, "—", cero);
      } else {
        celda(f, i + 1, lxv ? x.bultosLxv() : x.bultosMjs(), num);
      }
    }
    // Escala de color: el chute más cargado del día se ve más intenso.
    XSSFSheetConditionalFormatting formatos = hoja.getSheetConditionalFormatting();
    XSSFConditionalFormattingRule regla = formatos.createConditionalFormattingColorScaleRule();
    XSSFColorScaleFormatting escalaColor = regla.getColorScaleFormatting();
    escalaColor.setNumControlPoints(2);
    escalaColor.getThresholds()[0].setRangeType(ConditionalFormattingThreshold.RangeType.MIN);
    escalaColor.getThresholds()[1].setRangeType(ConditionalFormattingThreshold.RangeType.MAX);
    escalaColor.setColors(new Color[] {color("FFFFFF"), color(escala)});
    formatos.addConditionalFormatting(new CellRangeAddress[] {new CellRangeAddress(r, r, 1, ultima)}, regla);
    return r + 1;
  }

  // ---------------- Nota e impresión ----------------

  private void nota(int r) {
    XSSFCellStyle s = estilo("nota", null, fuente(8, false, GRIS), HorizontalAlignment.LEFT, null, true);
    celda(fila(r, 30), 0,
        "Cada ficha muestra el grupo (número y borde de su color), el número de ruta con el color de su frecuencia, sus"
            + " bultos redondeados hacia arriba y la letra de la frecuencia (D diaria, L L-X-V, M M-J-S). Una diaria sale"
            + " una sola vez y trabaja los dos días. Las filas de bultos suman por chute la carga de cada día (diarias +"
            + " rutas de ese día), redondeada al entero.",
        s);
    unir(r, r, 0, ultima);
  }

  private void impresion() {
    hoja.createFreezePane(1, 0);
    PrintSetup p = hoja.getPrintSetup();
    p.setLandscape(true);
    p.setPaperSize(PrintSetup.A3_PAPERSIZE);
    p.setFitWidth((short) 1);
    p.setFitHeight((short) 0);
    hoja.setFitToPage(true);
    hoja.setHorizontallyCenter(true);
    hoja.setMargin(PageMargin.LEFT, 0.3);
    hoja.setMargin(PageMargin.RIGHT, 0.3);
    hoja.setMargin(PageMargin.TOP, 0.4);
    hoja.setMargin(PageMargin.BOTTOM, 0.5);
    hoja.getFooter().setLeft("Atlas · Distribución de rutas por cluster y chute");
    hoja.getFooter().setRight("Página &P de &N");
  }

  // ---------------- Utilidades ----------------

  private XSSFColor color(String hex) {
    return new XSSFColor(HexFormat.of().parseHex(hex), null);
  }

  private XSSFFont fuente(int puntos, boolean negrita, String hex) {
    return fuentes.computeIfAbsent(
        puntos + "-" + negrita + "-" + hex,
        (clave) -> {
          XSSFFont f = libro.createFont();
          f.setFontName("Arial");
          f.setFontHeightInPoints((short) puntos);
          f.setBold(negrita);
          f.setColor(color(hex));
          return f;
        });
  }

  private XSSFCellStyle estilo(
      String clave, String fondo, XSSFFont fuente, HorizontalAlignment h, String borde, boolean ajustar) {
    return estilos.computeIfAbsent(
        clave,
        (k) -> {
          XSSFCellStyle s = base(fondo, fuente, h);
          s.setWrapText(ajustar);
          if (borde != null) {
            bordes(s, BorderStyle.THIN, borde, BorderStyle.THIN, borde);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            s.setLeftBorderColor(color(borde));
            s.setRightBorderColor(color(borde));
          }
          return s;
        });
  }

  private XSSFCellStyle base(String fondo, XSSFFont fuente, HorizontalAlignment h) {
    XSSFCellStyle s = libro.createCellStyle();
    if (fondo != null) {
      s.setFillForegroundColor(color(fondo));
      s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }
    s.setFont(fuente);
    s.setAlignment(h);
    s.setVerticalAlignment(VerticalAlignment.CENTER);
    return s;
  }

  /** Bordes superior e inferior (los laterales los pone cada estilo). */
  private void bordes(XSSFCellStyle s, BorderStyle arriba, String colorArriba, BorderStyle abajo, String colorAbajo) {
    s.setBorderTop(arriba);
    s.setBorderBottom(abajo);
    if (colorArriba != null) {
      s.setTopBorderColor(color(colorArriba));
    }
    if (colorAbajo != null) {
      s.setBottomBorderColor(color(colorAbajo));
    }
  }

  private Row fila(int r, float alto) {
    Row f = hoja.getRow(r) == null ? hoja.createRow(r) : hoja.getRow(r);
    f.setHeightInPoints(alto);
    return f;
  }

  private void celda(Row fila, int col, Object valor, XSSFCellStyle estilo) {
    Cell c = fila.createCell(col);
    if (valor instanceof Number n) {
      c.setCellValue(n.doubleValue());
    } else if (valor instanceof XSSFRichTextString rt) {
      c.setCellValue(rt);
    } else if (valor != null) {
      c.setCellValue(valor.toString());
    }
    if (estilo != null) {
      c.setCellStyle(estilo);
    }
  }

  /** Une celdas solo si el rango tiene más de una. */
  private void unir(int f1, int f2, int c1, int c2) {
    if (c2 > c1 || f2 > f1) {
      hoja.addMergedRegion(new CellRangeAddress(f1, f2, c1, Math.max(c1, c2)));
    }
  }
}
