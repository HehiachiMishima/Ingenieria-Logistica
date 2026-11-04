package oracle.malla.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipInputStream;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.malla.request.CentroMallaRequest;
import oracle.malla.request.CentroMallaRequest.Capacidad;
import oracle.malla.request.CentroMallaRequest.Dotacion;
import oracle.malla.request.CentroMallaRequest.DotacionReal;
import oracle.malla.request.CentroMallaRequest.Flujo;
import oracle.malla.request.CentroMallaRequest.Ola;
import oracle.malla.request.CentroMallaRequest.Turno;
import oracle.malla.request.CentroMallaRequest.Zona;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

/**
 * Excel de la malla de un centro: una hoja por bloque (Centro, Turnos,
 * Flujos, Olas, Zonas, Capacidad, Dotación y Dotación real). El mismo formato
 * sirve de plantilla, de exportación y de importación, así que lo que se
 * descarga se puede volver a cargar. Leer no guarda: devuelve los datos para
 * que el formulario los muestre.
 */
public final class MallaExcel {

    /** Una malla es pequeña: con esto sobra y se evita leer archivos enormes. */
    static final long MAX_BYTES = 2L * 1024 * 1024;
    /** Tope de lo que el .xlsx (un zip) puede ocupar descomprimido: frena las bombas zip. */
    static final long MAX_DESCOMPRIMIDO = 20L * 1024 * 1024;
    private static final int MAX_FILAS = 500;

    static final String CENTRO = "Centro";
    static final String TURNOS = "Turnos";
    static final String FLUJOS = "Flujos";
    static final String OLAS = "Olas";
    static final String ZONAS = "Zonas";
    static final String CAPACIDAD = "Capacidad";
    static final String DOTACION = "Dotación";
    static final String DOTACION_REAL = "Dotación real";

    private static final List<String> PROCESOS = List.of("REC", "ALM", "REA", "OLA", "DES");
    private static final Map<String, String> NOMBRES_PROCESO = Map.of(
            "RECEPCION", "REC", "ALMACENAMIENTO", "ALM", "REABASTECIMIENTO", "REA",
            "PICKING", "OLA", "OLAS", "OLA", "DESPACHO", "DES");
    private static final DataFormatter FORMATO = new DataFormatter(Locale.ROOT);

    private MallaExcel() {
    }

    // ---------------- Lectura ----------------

    public static CentroMallaRequest leer(MultipartFile archivo) {
        String nombre = archivo.getOriginalFilename() == null ? "" : archivo.getOriginalFilename();
        if (!nombre.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new SolicitudInvalidaException("Usa un archivo .xlsx (descarga la plantilla).");
        }
        if (archivo.getSize() > MAX_BYTES) {
            throw new SolicitudInvalidaException("El archivo supera los 2 MB permitidos para una malla.");
        }
        try {
            byte[] contenido = archivo.getBytes();
            comprobarTamanoReal(contenido);
            try (Workbook libro = new XSSFWorkbook(new ByteArrayInputStream(contenido))) {
                return leer(libro);
            }
        } catch (SolicitudInvalidaException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new SolicitudInvalidaException("No se pudo leer el Excel: revisa que sea la plantilla de la malla.");
        }
    }

    /** Descomprime sin guardar nada, contando bytes reales (no los que declara el zip). */
    static void comprobarTamanoReal(byte[] contenido) throws IOException {
        long total = 0;
        byte[] bloque = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(contenido))) {
            while (zip.getNextEntry() != null) {
                for (int leidos; (leidos = zip.read(bloque)) > 0; ) {
                    total += leidos;
                    if (total > MAX_DESCOMPRIMIDO) {
                        throw new SolicitudInvalidaException(
                                "El Excel es demasiado grande para una malla: usa la plantilla.");
                    }
                }
            }
        }
    }

    static CentroMallaRequest leer(Workbook libro) {
        List<Map<String, String>> centro = filas(libro, CENTRO, true);
        if (centro.isEmpty()) {
            throw new SolicitudInvalidaException("La hoja «Centro» debe tener el código y el nombre del centro.");
        }
        Map<String, String> datosCentro = centro.get(0);

        List<Turno> turnos = new ArrayList<>();
        for (Map<String, String> f : filas(libro, TURNOS, false)) {
            turnos.add(new Turno(
                    f.get("CODIGO"), f.get("NOMBRE"),
                    hora(f, "INICIO", TURNOS), hora(f, "FIN", TURNOS), hora(f, "REFRIGERIO", TURNOS),
                    f.get("TIPO DE REFRIGERIO"), f.get("NOTA"), si(f.get("CONFIRMADO"))));
        }
        List<Flujo> flujos = new ArrayList<>();
        for (Map<String, String> f : filas(libro, FLUJOS, false)) {
            flujos.add(new Flujo(
                    proceso(f.get("PROCESO"), FLUJOS, f), responsable(f.get("RESPONSABLE"), f),
                    hora(f, "INICIO", FLUJOS), hora(f, "FIN", FLUJOS), f.get("ACTIVIDAD"),
                    si(f.get("CONFIRMADO"))));
        }
        List<Ola> olas = new ArrayList<>();
        for (Map<String, String> f : filas(libro, OLAS, false)) {
            olas.add(new Ola(
                    f.get("CLUSTER"), f.get("OLA"),
                    hora(f, "CAIDA DE PEDIDOS", OLAS), hora(f, "LANZAMIENTO", OLAS),
                    hora(f, "CIERRE DE PICKING", OLAS), hora(f, "DESPACHO", OLAS),
                    si(f.get("CONFIRMADO")), f.get("NOTA")));
        }
        List<Zona> zonas = new ArrayList<>();
        for (Map<String, String> f : filas(libro, ZONAS, false)) {
            zonas.add(new Zona(f.get("ZONA"), entero(f, "LPH REAL", ZONAS), entero(f, "LPH DISENO", ZONAS)));
        }
        Map<String, String> cap = primera(libro, CAPACIDAD);
        Capacidad capacidad = new Capacidad(
                entero(cap, "UNIDADES POR DIA", CAPACIDAD), entero(cap, "LINEAS POR DIA", CAPACIDAD),
                entero(cap, "BULTOS DE RECEPCION POR DIA", CAPACIDAD),
                entero(cap, "PROVEEDORES POR DIA", CAPACIDAD));
        List<Dotacion> dotacion = new ArrayList<>();
        for (Map<String, String> f : filas(libro, DOTACION, false)) {
            String proceso = proceso(f.get("PROCESO"), DOTACION, f);
            for (Map.Entry<String, String> celda : f.entrySet()) {
                if (!celda.getKey().equals("PROCESO") && !celda.getKey().equals("#")) {
                    dotacion.add(new Dotacion(celda.getKey(), proceso, entero(f, celda.getKey(), DOTACION)));
                }
            }
        }
        Map<String, String> real = primera(libro, DOTACION_REAL);
        DotacionReal dotacionReal = new DotacionReal(
                entero(real, "INBOUND", DOTACION_REAL), entero(real, "OUTBOUND", DOTACION_REAL),
                entero(real, "DESPACHO", DOTACION_REAL), entero(real, "TOTAL", DOTACION_REAL),
                real.get("SEMANA"));
        return new CentroMallaRequest(datosCentro.get("CODIGO"), datosCentro.get("NOMBRE"), turnos, flujos,
                olas, zonas, capacidad, dotacion, dotacionReal);
    }

    /**
     * Filas de una hoja como mapas encabezado → valor (encabezados sin tildes
     * y en mayúsculas). Las filas vacías se saltan; «#» guarda el número de
     * fila para los mensajes. Sin la hoja: lista vacía, salvo si es obligatoria.
     */
    private static List<Map<String, String>> filas(Workbook libro, String hoja, boolean obligatoria) {
        Sheet sheet = libro.getSheet(hoja);
        if (sheet == null) {
            if (obligatoria) {
                throw new SolicitudInvalidaException("Falta la hoja «" + hoja + "» (descarga la plantilla).");
            }
            return List.of();
        }
        Row encabezado = sheet.getRow(sheet.getFirstRowNum());
        if (encabezado == null) {
            return List.of();
        }
        List<String> columnas = new ArrayList<>();
        for (int c = 0; c < encabezado.getLastCellNum(); c++) {
            columnas.add(normalizar(texto(encabezado.getCell(c))));
        }
        List<Map<String, String>> filas = new ArrayList<>();
        for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row fila = sheet.getRow(r);
            if (fila == null) {
                continue;
            }
            Map<String, String> valores = new LinkedHashMap<>();
            boolean vacia = true;
            for (int c = 0; c < columnas.size(); c++) {
                if (columnas.get(c).isEmpty()) {
                    continue;
                }
                String valor = texto(fila.getCell(c));
                valores.put(columnas.get(c), valor);
                vacia &= valor == null;
            }
            if (vacia) {
                continue;
            }
            if (filas.size() >= MAX_FILAS) {
                throw new SolicitudInvalidaException("La hoja «" + hoja + "» tiene demasiadas filas.");
            }
            valores.put("#", String.valueOf(r + 1));
            filas.add(valores);
        }
        return filas;
    }

    private static Map<String, String> primera(Workbook libro, String hoja) {
        List<Map<String, String>> filas = filas(libro, hoja, false);
        return filas.isEmpty() ? Map.of() : filas.get(0);
    }

    /** Valor de una celda como texto (las horas de Excel salen como "HH:mm"); null si está vacía. */
    private static String texto(Cell celda) {
        if (celda == null) {
            return null;
        }
        CellType tipo = celda.getCellType() == CellType.FORMULA ? celda.getCachedFormulaResultType()
                : celda.getCellType();
        if (tipo == CellType.NUMERIC && DateUtil.isCellDateFormatted(celda)) {
            double dia = celda.getNumericCellValue() % 1;
            int minutos = (int) Math.round(dia * 24 * 60) % (24 * 60);
            return String.format("%02d:%02d", minutos / 60, minutos % 60);
        }
        if (tipo == CellType.NUMERIC) {
            double valor = celda.getNumericCellValue();
            return valor == Math.rint(valor) ? String.valueOf((long) valor) : String.valueOf(valor);
        }
        String valor = FORMATO.formatCellValue(celda).trim();
        return valor.isEmpty() ? null : valor;
    }

    private static String hora(Map<String, String> fila, String columna, String hoja) {
        String valor = fila.get(columna);
        if (valor == null) {
            return null;
        }
        try {
            String[] partes = valor.split(":");
            LocalTime hora = LocalTime.of(Integer.parseInt(partes[0].trim()), Integer.parseInt(partes[1].trim()));
            return String.format("%02d:%02d", hora.getHour(), hora.getMinute());
        } catch (RuntimeException ex) {
            throw invalido(hoja, fila, "«" + valor + "» no es una hora (usa HH:mm)");
        }
    }

    private static Integer entero(Map<String, String> fila, String columna, String hoja) {
        String valor = fila.get(columna);
        if (valor == null) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(valor.replace(",", "")));
        } catch (NumberFormatException ex) {
            throw invalido(hoja, fila, "«" + valor + "» no es un número");
        }
    }

    private static String proceso(String valor, String hoja, Map<String, String> fila) {
        String clave = valor == null ? "" : normalizar(valor);
        if (PROCESOS.contains(clave)) {
            return clave;
        }
        String porNombre = NOMBRES_PROCESO.get(clave);
        if (porNombre != null) {
            return porNombre;
        }
        throw invalido(hoja, fila, "proceso «" + valor + "» desconocido (REC, ALM, REA, OLA o DES)");
    }

    private static String responsable(String valor, Map<String, String> fila) {
        String clave = valor == null ? "" : normalizar(valor);
        if (clave.equals("IL") || clave.startsWith("INGENIERIA")) {
            return "IL";
        }
        if (clave.equals("OPS") || clave.startsWith("OPERACION")) {
            return "OPS";
        }
        throw invalido(FLUJOS, fila, "responsable «" + valor + "» desconocido (IL u OPS)");
    }

    private static boolean si(String valor) {
        String clave = valor == null ? "" : normalizar(valor);
        return clave.equals("SI") || clave.equals("S") || clave.equals("TRUE") || clave.equals("1")
                || clave.equals("X");
    }

    private static SolicitudInvalidaException invalido(String hoja, Map<String, String> fila, String detalle) {
        return new SolicitudInvalidaException(
                "Hoja «" + hoja + "», fila " + fila.getOrDefault("#", "?") + ": " + detalle + ".");
    }

    /** Mayúsculas y sin tildes: los encabezados se comparan así. */
    private static String normalizar(String valor) {
        if (valor == null) {
            return "";
        }
        return Normalizer.normalize(valor.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
    }

    // ---------------- Escritura ----------------

    /** Plantilla vacía (con un ejemplo de cada hoja) o la malla de un centro. */
    public static byte[] escribir(CentroMallaRequest malla) {
        try (XSSFWorkbook libro = new XSSFWorkbook(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            CellStyle titulo = libro.createCellStyle();
            Font negrita = libro.createFont();
            negrita.setBold(true);
            negrita.setColor(IndexedColors.WHITE.getIndex());
            titulo.setFont(negrita);
            titulo.setFillForegroundColor(IndexedColors.DARK_RED.getIndex());
            titulo.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            hoja(libro, titulo, CENTRO, List.of("Código", "Nombre"),
                    List.of(Arrays.asList(malla.codigo(), malla.nombre())));
            hoja(libro, titulo, TURNOS, List.of("Código", "Nombre", "Inicio", "Fin", "Refrigerio",
                    "Tipo de refrigerio", "Nota", "Confirmado"), filas(malla.turnos(), t -> Arrays.asList(
                    t.codigo(), t.nombre(), t.inicio(), t.fin(), t.refrigerio(), t.tipoRefrigerio(),
                    t.nota(), siNo(t.confirmado()))));
            hoja(libro, titulo, FLUJOS, List.of("Proceso", "Responsable", "Inicio", "Fin", "Actividad",
                    "Confirmado"), filas(malla.flujos(), f -> Arrays.asList(
                    f.proceso(), f.responsable(), f.inicio(), f.fin(), f.actividad(), siNo(f.confirmado()))));
            hoja(libro, titulo, OLAS, List.of("Cluster", "Ola", "Caída de pedidos", "Lanzamiento",
                    "Cierre de picking", "Despacho", "Confirmado", "Nota"), filas(malla.olas(), o -> Arrays.asList(
                    o.cluster(), o.nombre(), o.caidaPedidos(), o.lanzamiento(), o.cierrePicking(),
                    o.despacho(), siNo(o.confirmado()), o.nota())));
            hoja(libro, titulo, ZONAS, List.of("Zona", "LPH real", "LPH diseño"), filas(malla.zonas(),
                    z -> Arrays.asList(z.nombre(), z.lphReal(), z.lphDiseno())));
            Capacidad cap = malla.capacidad();
            hoja(libro, titulo, CAPACIDAD, List.of("Unidades por día", "Líneas por día",
                    "Bultos de recepción por día", "Proveedores por día"), List.of(Arrays.asList(
                    cap.unidadesDia(), cap.lineasDia(), cap.bultosRecepcionDia(), cap.proveedoresDia())));
            List<String> columnasDotacion = new ArrayList<>(List.of("Proceso"));
            malla.turnos().forEach(t -> columnasDotacion.add(t.codigo()));
            List<List<Object>> dotacion = new ArrayList<>();
            for (String proceso : PROCESOS) {
                List<Object> fila = new ArrayList<>(List.of(proceso));
                for (Turno turno : malla.turnos()) {
                    fila.add(malla.dotacion().stream()
                            .filter(d -> d.proceso().equals(proceso)
                                    && d.turno().equalsIgnoreCase(turno.codigo()))
                            .findFirst().map(Dotacion::personas).orElse(null));
                }
                dotacion.add(fila);
            }
            hoja(libro, titulo, DOTACION, columnasDotacion, dotacion);
            DotacionReal real = malla.dotacionReal();
            hoja(libro, titulo, DOTACION_REAL, List.of("Inbound", "Outbound", "Despacho", "Total", "Semana"),
                    List.of(Arrays.asList(real.inbound(), real.outbound(), real.despacho(), real.total(), real.semana())));
            libro.write(salida);
            return salida.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo generar el Excel de la malla.", ex);
        }
    }

    private static <T> List<List<Object>> filas(List<T> datos, Function<T, List<Object>> fila) {
        return datos.stream().map(fila).toList();
    }

    private static void hoja(Workbook libro, CellStyle titulo, String nombre, List<String> columnas,
            List<List<Object>> filas) {
        Sheet hoja = libro.createSheet(nombre);
        Row encabezado = hoja.createRow(0);
        for (int c = 0; c < columnas.size(); c++) {
            Cell celda = encabezado.createCell(c);
            celda.setCellValue(columnas.get(c));
            celda.setCellStyle(titulo);
        }
        for (int r = 0; r < filas.size(); r++) {
            Row fila = hoja.createRow(r + 1);
            List<Object> valores = filas.get(r);
            for (int c = 0; c < valores.size(); c++) {
                Object valor = valores.get(c);
                if (valor instanceof Number numero) {
                    fila.createCell(c).setCellValue(numero.doubleValue());
                } else if (valor != null) {
                    fila.createCell(c).setCellValue(valor.toString());
                }
            }
        }
        for (int c = 0; c < columnas.size(); c++) {
            hoja.setColumnWidth(c, Math.max(12, columnas.get(c).length() + 4) * 256);
        }
        hoja.createFreezePane(0, 1);
    }

    private static String siNo(boolean valor) {
        return valor ? "Sí" : "No";
    }
}
