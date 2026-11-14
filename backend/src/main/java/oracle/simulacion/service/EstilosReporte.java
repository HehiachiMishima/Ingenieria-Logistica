package oracle.simulacion.service;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Estilos de las celdas del Excel de la simulación: colores, fuentes, formatos y bordes. */
final class EstilosReporte {
  final CellStyle titulo;
  final CellStyle subtitulo;
  final CellStyle alerta;
  final CellStyle aviso;
  final CellStyle leyenda;
  final CellStyle seccion;
  final CellStyle cabecera;
  final CellStyle texto;
  final CellStyle fuerte;
  final CellStyle centro;
  final CellStyle derecha;
  final CellStyle numero;
  final CellStyle totalNumero;
  final CellStyle etiqueta;
  final CellStyle etiquetaTotal;
  final CellStyle etiquetaSelector;
  final CellStyle selector;
  final CellStyle pista;
  final CellStyle kpiEtiqueta;
  final CellStyle kpi;
  final CellStyle kpiDetalle;
  final CellStyle ruta;
  final CellStyle vacia;
  final CellStyle subeCentro;
  final CellStyle bajaCentro;
  final CellStyle semBajo;
  final CellStyle semMedio;
  final CellStyle semAlto;
  /** Semáforo de los totales por chute: la cifra con miles, a la derecha. */
  final CellStyle semBajoNumero;
  final CellStyle semMedioNumero;
  final CellStyle semAltoNumero;
  /** Banda de cada día en Configuración: el color de su pestaña de frecuencia. */
  final CellStyle diaLxv;
  final CellStyle diaMjs;
  final CellStyle kpiNumero;
  final CellStyle porcentaje;
  final CellStyle porcentajeTotal;
  /** Horas como hora de Excel: «2:31» (2 h 31 min); suma bien y se grafica. */
  final CellStyle hora;
  final CellStyle horaFuerte;
  /** Comparativo: «antes» atenuado, «después» en negrita y la variación con signo. */
  final CellStyle antes;
  final CellStyle despues;
  final CellStyle variacion;

  EstilosReporte(XSSFWorkbook libro) {
    short miles = libro.createDataFormat().getFormat("#,##0");
    short formatoHora = libro.createDataFormat().getFormat("[h]:mm");
    hora = estilo(libro, null, fuente(libro, 10, false, "1E293B"), HorizontalAlignment.RIGHT, true);
    hora.setDataFormat(formatoHora);
    horaFuerte = estilo(libro, null, fuente(libro, 10, true, "0F172A"), HorizontalAlignment.RIGHT, true);
    horaFuerte.setDataFormat(formatoHora);
    antes = estilo(libro, null, fuente(libro, 10, false, "64748B"), HorizontalAlignment.RIGHT, true);
    antes.setDataFormat(miles);
    despues = estilo(libro, null, fuente(libro, 10, true, "0F172A"), HorizontalAlignment.RIGHT, true);
    despues.setDataFormat(miles);
    variacion = estilo(libro, "F8FAFC", fuente(libro, 9, true, "1D63ED"), HorizontalAlignment.RIGHT, true);
    variacion.setDataFormat(libro.createDataFormat().getFormat("+0%;[Color10]-0%;0%"));
    titulo = estilo(libro, null, fuente(libro, 16, true, "0F1E3D"), HorizontalAlignment.LEFT, false);
    subtitulo = estilo(libro, null, fuente(libro, 10, false, "64748B"), HorizontalAlignment.LEFT, false);
    alerta = estilo(libro, "FEF3C7", fuente(libro, 10, true, "92400E"), HorizontalAlignment.LEFT, false);
    aviso = estilo(libro, "EEF2FF", fuente(libro, 10, true, "3730A3"), HorizontalAlignment.LEFT, false);
    leyenda = estilo(libro, null, fuente(libro, 9, false, "475569"), HorizontalAlignment.LEFT, false);
    seccion = estilo(libro, "E2E8F0", fuente(libro, 11, true, "0F1E3D"), HorizontalAlignment.LEFT, false);
    cabecera = estilo(libro, "0F1E3D", fuente(libro, 10, true, "FFFFFF"), HorizontalAlignment.CENTER, true);
    cabecera.setWrapText(true);
    texto = estilo(libro, null, fuente(libro, 10, false, "1E293B"), HorizontalAlignment.LEFT, false);
    fuerte = estilo(libro, null, fuente(libro, 10, true, "0F172A"), HorizontalAlignment.LEFT, true);
    centro = estilo(libro, null, fuente(libro, 10, false, "1E293B"), HorizontalAlignment.CENTER, true);
    derecha = estilo(libro, null, fuente(libro, 10, false, "1E293B"), HorizontalAlignment.RIGHT, true);
    numero = estilo(libro, null, fuente(libro, 10, false, "1E293B"), HorizontalAlignment.RIGHT, true);
    numero.setDataFormat(miles);
    totalNumero = estilo(libro, "F8FAFC", fuente(libro, 10, true, "0F172A"), HorizontalAlignment.RIGHT, true);
    totalNumero.setDataFormat(miles);
    etiqueta = estilo(libro, "F8FAFC", fuente(libro, 9, true, "475569"), HorizontalAlignment.LEFT, true);
    etiquetaTotal = estilo(libro, "F1F5F9", fuente(libro, 9, true, "334155"), HorizontalAlignment.LEFT, true);
    etiquetaSelector = estilo(libro, "0F1E3D", fuente(libro, 11, true, "FFFFFF"), HorizontalAlignment.RIGHT, true);
    selector = estilo(libro, "FEF9C3", fuente(libro, 12, true, "0F1E3D"), HorizontalAlignment.CENTER, true);
    selector.setBorderBottom(BorderStyle.MEDIUM);
    selector.setBorderTop(BorderStyle.MEDIUM);
    selector.setBorderLeft(BorderStyle.MEDIUM);
    selector.setBorderRight(BorderStyle.MEDIUM);
    pista = estilo(libro, null, fuente(libro, 9, false, "64748B"), HorizontalAlignment.LEFT, false);
    kpiEtiqueta = estilo(libro, "F8FAFC", fuente(libro, 9, true, "64748B"), HorizontalAlignment.LEFT, false);
    kpi = estilo(libro, "F8FAFC", fuente(libro, 16, true, "0F1E3D"), HorizontalAlignment.LEFT, false);
    kpiDetalle = estilo(libro, "F8FAFC", fuente(libro, 9, false, "64748B"), HorizontalAlignment.LEFT, false);
    ruta = estilo(libro, null, fuente(libro, 9, true, "0F172A"), HorizontalAlignment.LEFT, true);
    vacia = estilo(libro, null, fuente(libro, 9, false, "94A3B8"), HorizontalAlignment.CENTER, false);
    subeCentro = estilo(libro, null, fuente(libro, 10, true, "059669"), HorizontalAlignment.CENTER, true);
    bajaCentro = estilo(libro, null, fuente(libro, 10, true, "DC2626"), HorizontalAlignment.CENTER, true);
    semBajo = estilo(libro, "FEF3C7", fuente(libro, 10, true, "92400E"), HorizontalAlignment.CENTER, true);
    semMedio = estilo(libro, "D1FAE5", fuente(libro, 10, true, "065F46"), HorizontalAlignment.CENTER, true);
    semAlto = estilo(libro, "FEE2E2", fuente(libro, 10, true, "991B1B"), HorizontalAlignment.CENTER, true);
    semBajoNumero = estilo(libro, "FEF3C7", fuente(libro, 10, true, "92400E"), HorizontalAlignment.RIGHT, true);
    semBajoNumero.setDataFormat(miles);
    semMedioNumero = estilo(libro, "D1FAE5", fuente(libro, 10, true, "065F46"), HorizontalAlignment.RIGHT, true);
    semMedioNumero.setDataFormat(miles);
    semAltoNumero = estilo(libro, "FEE2E2", fuente(libro, 10, true, "991B1B"), HorizontalAlignment.RIGHT, true);
    semAltoNumero.setDataFormat(miles);
    diaLxv = estilo(libro, "1D4ED8", fuente(libro, 10, true, "FFFFFF"), HorizontalAlignment.CENTER, true);
    diaMjs = estilo(libro, "C2410C", fuente(libro, 10, true, "FFFFFF"), HorizontalAlignment.CENTER, true);
    kpiNumero = estilo(libro, "F8FAFC", fuente(libro, 16, true, "0F1E3D"), HorizontalAlignment.LEFT, false);
    kpiNumero.setDataFormat(miles);
    short pct = libro.createDataFormat().getFormat("0%");
    porcentaje = estilo(libro, null, fuente(libro, 9, false, "64748B"), HorizontalAlignment.RIGHT, true);
    porcentaje.setDataFormat(pct);
    porcentajeTotal = estilo(libro, "F8FAFC", fuente(libro, 9, true, "334155"), HorizontalAlignment.RIGHT, true);
    porcentajeTotal.setDataFormat(pct);
  }

  static XSSFFont fuente(XSSFWorkbook libro, int puntos, boolean negrita, String color) {
    XSSFFont fuente = libro.createFont();
    fuente.setFontName("Calibri");
    fuente.setFontHeightInPoints((short) puntos);
    fuente.setBold(negrita);
    fuente.setColor(color(color));
    return fuente;
  }

  static XSSFCellStyle estilo(
      XSSFWorkbook libro, String fondo, XSSFFont fuente, HorizontalAlignment alineacion, boolean borde) {
    XSSFCellStyle estilo = libro.createCellStyle();
    estilo.setFont(fuente);
    estilo.setAlignment(alineacion);
    estilo.setVerticalAlignment(VerticalAlignment.CENTER);
    if (fondo != null) {
      estilo.setFillForegroundColor(color(fondo));
      estilo.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }
    if (borde) {
      XSSFColor gris = color("E2E8F0");
      estilo.setBorderBottom(BorderStyle.THIN);
      estilo.setBorderTop(BorderStyle.THIN);
      estilo.setBorderLeft(BorderStyle.THIN);
      estilo.setBorderRight(BorderStyle.THIN);
      estilo.setBottomBorderColor(gris);
      estilo.setTopBorderColor(gris);
      estilo.setLeftBorderColor(gris);
      estilo.setRightBorderColor(gris);
    }
    return estilo;
  }

  static XSSFColor color(String hex) {
    int rgb = Integer.parseInt(hex, 16);
    return new XSSFColor(
        new byte[] {(byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb}, null);
  }
}
