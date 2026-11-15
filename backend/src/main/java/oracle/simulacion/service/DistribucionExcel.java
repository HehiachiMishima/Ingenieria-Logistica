package oracle.simulacion.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * Hoja «Distribución» con estilo ejecutivo: encabezado de marca, indicadores, leyenda y un bloque
 * por cluster con todos los chutes en columnas (buff en rojo, bloqueados angostos en gris). Cada
 * celda lleva los números de ruta apilados, cada uno con el color de su frecuencia (texto con
 * varios formatos en la misma celda), y debajo los bultos de cada día con una escala de color.
 * Lista para imprimir: A3 horizontal, al ancho de una página.
 */
final class DistribucionExcel {

  private static final ZoneId ZONA = ZoneId.of("America/Lima");
  private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

  // Paleta Atlas / Farmacias Peruanas.
  static final String NAVY = "0B1F3A";
  static final String NAVY_2 = "13294B";
  static final String ROJO = "E11D2E";
  static final String AZUL = "1D63ED";
  static final String AMBAR = "B45309";
  static final String TINTA = "0F172A";
  private static final String ROJO_SUAVE = "FFF4F5";
  private static final String GRIS = "64748B";
  private static final String GRIS_CLARO = "94A3B8";
  private static final String SUPERFICIE = "F5F7FB";
  private static final String BORDE = "D5DDE8";
  private static final String BLOQ = "E2E8F0";
  private static final String NAVY_SUAVE = "E8EDF4";
  private static final String GUION = "CBD5E1";

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

  /** El libro con la hoja «Distribución»; {@code logo} (JPEG) va en el encabezado si no es null. */
  static byte[] escribir(DistribucionResponse d, byte[] logo) {
    try (XSSFWorkbook libro = new XSSFWorkbook();
        ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
      new DistribucionExcel(d, libro).hoja(logo);
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

  private void hoja(byte[] logo) {
    hoja.setDisplayGridlines(false);
    hoja.setZoom(90);
    hoja.setColumnWidth(0, 19 * 256);
    for (int i = 0; i < d.chutes().size(); i++) {
      hoja.setColumnWidth(i + 1, (d.chutes().get(i).bloqueado() ? 5 : 9) * 256);
    }
    int r = encabezado(logo);
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

  // ---------------- Encabezado: logo, título y marca ----------------

  private int encabezado(byte[] logo) {
    fila(0, 22);
    fila(1, 26);
    fila(2, 18);
    if (logo != null) {
      int idx = libro.addPicture(logo, Workbook.PICTURE_TYPE_JPEG);
      XSSFClientAnchor ancla =
          new XSSFClientAnchor(Units.pixelToEMU(4), Units.pixelToEMU(4), 0, 0, 0, 0, 1, 3);
      ancla.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
      hoja.createDrawingPatriarch().createPicture(ancla, idx).resize(0.7);
    }
    // Título a la derecha del logo; la marca, en el borde derecho (si hay espacio).
    boolean conMarca = ultima >= 16;
    int finTitulo = conMarca ? ultima - 8 : ultima;
    XSSFCellStyle titulo = estilo("titulo", null, fuente(20, true, NAVY), HorizontalAlignment.LEFT, null, false);
    XSSFCellStyle sub = estilo("sub", null, fuente(10, false, GRIS), HorizontalAlignment.LEFT, null, false);
    celda(fila(0, 22), 3, "DISTRIBUCIÓN DE RUTAS POR CLUSTER Y CHUTE", titulo);
    unir(0, 1, 3, finTitulo);
    celda(fila(2, 18), 3,
        d.registro() + "  ·  " + d.clusters().size() + " cluster(s)  ·  " + d.chutes().size()
            + " chutes  ·  Diarias, L-X-V y M-J-S en un solo bloque por cluster",
        sub);
    unir(2, 2, 3, finTitulo);
    String generado = d.generado() == null ? "" : "Generado " + FECHA.format(d.generado().atZone(ZONA));
    if (conMarca) {
      XSSFRichTextString marca = new XSSFRichTextString();
      marca.append("ATLAS", fuente(20, true, ROJO));
      marca.append("\nPLATAFORMA DE INGENIERÍA LOGÍSTICA", fuente(8, true, NAVY));
      celda(fila(0, 22), ultima - 6, marca, estilo("marca", null, fuente(8, true, NAVY), HorizontalAlignment.RIGHT, null, true));
      unir(0, 1, ultima - 6, ultima);
      celda(fila(2, 18), ultima - 6, generado, estilo("fecha", null, fuente(9, false, GRIS_CLARO), HorizontalAlignment.RIGHT, null, false));
      unir(2, 2, ultima - 6, ultima);
    }
    // Franja de marca: una línea roja fina bajo el encabezado.
    XSSFCellStyle linea = estilo("linea", ROJO, fuente(4, false, ROJO), HorizontalAlignment.LEFT, null, false);
    Row f = fila(3, 3);
    for (int c = 0; c <= ultima; c++) {
      celda(f, c, null, linea);
    }
    return 5;
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
      // El primero ocupa la columna de rótulos y un chute; los demás, tres chutes.
      int desde = col;
      int hasta = col == 0 ? 1 : col + 2;
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
    t.append("Frecuencia de cada ruta:   ", fuente(9, true, GRIS));
    t.append("■ Diaria", fuente(9, true, TINTA));
    t.append("  (sale los dos días)      ", fuente(9, false, GRIS));
    t.append("■ L-X-V", fuente(9, true, AZUL));
    t.append("      ", fuente(9, false, GRIS));
    t.append("■ M-J-S", fuente(9, true, AMBAR));
    t.append("        Cabecera roja = chute con buff   ·   Columna gris = bloqueado", fuente(9, false, GRIS));
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
    XSSFCellStyle resumen = estilo("res", NAVY_SUAVE, fuente(9, false, NAVY), HorizontalAlignment.CENTER, BORDE, true);
    XSSFCellStyle ruta = estilo("ruta", "FFFFFF", fuente(10, true, TINTA), HorizontalAlignment.CENTER, BORDE, true);
    XSSFCellStyle rutaBuff = estilo("rutaB", ROJO_SUAVE, fuente(10, true, TINTA), HorizontalAlignment.CENTER, "F3B9BF", true);
    XSSFCellStyle vacia = estilo("vacia", SUPERFICIE, fuente(10, false, GUION), HorizontalAlignment.CENTER, BORDE, false);
    XSSFCellStyle bloqueada = estilo("bloq", BLOQ, fuente(10, false, GUION), HorizontalAlignment.CENTER, BORDE, false);

    Map<Long, DistribucionResponse.Celda> celdas = new HashMap<>();
    c.celdas().forEach((x) -> celdas.put(x.chuteId(), x));

    // Cabecera: nombre del cluster y los chutes.
    Row cab = fila(r, 20);
    celda(cab, 0, c.nombre(), nombre);
    for (int i = 0; i < d.chutes().size(); i++) {
      DistribucionResponse.Chute ch = d.chutes().get(i);
      celda(cab, i + 1, ch.bloqueado() ? "BLOQ." : etiquetaChute(ch.nombre()), ch.bloqueado() ? cabBloq : ch.buff() ? cabBuff : cabNavy);
    }

    // Rutas: solo los números, apilados, con el color de su frecuencia.
    int alto = c.celdas().stream().mapToInt((x) -> x.rutas().size()).max().orElse(1);
    Row rutas = fila(r + 1, Math.max(34, 14 * alto + 8));
    XSSFRichTextString res = new XSSFRichTextString();
    res.append(c.rutas() + (c.rutas() == 1 ? " ruta\n" : " rutas\n"), fuente(10, true, NAVY));
    res.append(
        c.diarias() + (c.diarias() == 1 ? " diaria · " : " diarias · ") + c.lxv() + " L-X-V · " + c.mjs() + " M-J-S",
        fuente(8, false, GRIS));
    celda(rutas, 0, res, resumen);
    for (int i = 0; i < d.chutes().size(); i++) {
      DistribucionResponse.Chute ch = d.chutes().get(i);
      if (ch.bloqueado()) {
        celda(rutas, i + 1, "", bloqueada);
        continue;
      }
      DistribucionResponse.Celda x = ch.id() == null ? null : celdas.get(ch.id());
      if (x == null || x.rutas().isEmpty()) {
        celda(rutas, i + 1, "—", vacia);
        continue;
      }
      XSSFRichTextString t = new XSSFRichTextString();
      List<DistribucionResponse.Ruta> lista = x.rutas();
      for (int k = 0; k < lista.size(); k++) {
        DistribucionResponse.Ruta ru = lista.get(k);
        t.append(ru.codigo() + (k < lista.size() - 1 ? "\n" : ""), fuente(10, true, colorFrecuencia(ru.frecuencia())));
      }
      celda(rutas, i + 1, t, ch.buff() ? rutaBuff : ruta);
    }

    // Bultos de cada día por chute (diarias + rutas del día).
    int f = totales(r + 2, "BULTOS L-X-V", c.bultosLxv(), celdas, true, AZUL, "DCE7FD");
    return totales(f, "BULTOS M-J-S", c.bultosMjs(), celdas, false, AMBAR, "FCE3BF");
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
        "Cada celda muestra solo los números de ruta; el color indica su frecuencia. Una diaria sale una sola vez y trabaja"
            + " los dos días. Las filas de bultos suman por chute la carga de cada día (diarias + rutas de ese día),"
            + " redondeada al entero.",
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
          XSSFCellStyle s = libro.createCellStyle();
          if (fondo != null) {
            s.setFillForegroundColor(color(fondo));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
          }
          s.setFont(fuente);
          s.setAlignment(h);
          s.setVerticalAlignment(VerticalAlignment.CENTER);
          s.setWrapText(ajustar);
          if (borde != null) {
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            XSSFColor b = color(borde);
            s.setTopBorderColor(b);
            s.setBottomBorderColor(b);
            s.setLeftBorderColor(b);
            s.setRightBorderColor(b);
          }
          return s;
        });
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
