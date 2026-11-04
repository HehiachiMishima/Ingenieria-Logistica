package oracle.simulacion.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Benchmark reproducible de {@link SimulacionExcel#analizar} sin base de datos.
 *
 * <p>Genera libros .xlsx (con sharedStrings, como Excel) y .csv sintéticos de 1k/10k/50k/100k
 * filas en el directorio temporal del sistema y mide por archivo: tiempo de pared y de CPU
 * (mediana y min-max de 7 corridas tras 3 de calentamiento), bytes asignados por el hilo, pico de
 * heap muestreado y —para los de 100k— el heap mínimo con el que el parseo termina sin OOM
 * (búsqueda binaria lanzando JVMs hijas con -Xmx y SerialGC, como en una instancia de 512 MB).
 *
 * <p>No corre en el build normal. Ejecutar con:
 *
 * <pre>
 * SIMULACION_BENCH=1 ./mvnw test -Dtest=SimulacionExcelBenchmark
 * </pre>
 *
 * o directamente con {@code java -cp ... oracle.simulacion.service.SimulacionExcelBenchmark}.
 * Variables opcionales: SIMULACION_BENCH_MINHEAP=0 desactiva la búsqueda de heap mínimo;
 * SIMULACION_BENCH_KEEP=1 conserva los archivos generados.
 */
@EnabledIfEnvironmentVariable(named = "SIMULACION_BENCH", matches = "1")
class SimulacionExcelBenchmark {

  private static final int[] TAMANOS = {1_000, 10_000, 50_000, 100_000};
  private static final int CALENTAMIENTO = 3;
  private static final int CORRIDAS = 7;
  private static final String XLSX_MIME =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  @Test
  void medir() throws Exception {
    main(new String[0]);
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 2 && args[0].equals("--unico")) {
      // Modo JVM hija: un solo parseo; el código de salida indica éxito.
      SimulacionExcel.analizar(cargar(Path.of(args[1])));
      System.exit(0);
    }
    Path dir = Files.createTempDirectory("simulacion-bench-");
    boolean conservar = "1".equals(System.getenv("SIMULACION_BENCH_KEEP"));
    boolean minHeap = !"0".equals(System.getenv("SIMULACION_BENCH_MINHEAP"));
    try {
      List<Path> archivos = new ArrayList<>();
      for (int n : TAMANOS) {
        archivos.add(generarXlsx(dir, n, false, false));
        archivos.add(generarCsv(dir, n, StandardCharsets.UTF_8, ',', false));
        archivos.add(generarCsv(dir, n, Charset.forName("windows-1252"), ';', true));
      }
      archivos.add(generarXlsx(dir, 100_000, true, false)); // 100k rutas distintas
      archivos.add(generarXlsx(dir, 100_000, false, true)); // todo texto: peor caso sharedStrings

      System.out.println("Benchmark SimulacionExcel — " + Runtime.version()
          + " — heap max " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB — dir " + dir);
      System.out.println(
          "| archivo | MB | xml descomp. MB | rutas | lineas OK | ms mediana (min-max) | CPU ms | MB asignados | pico heap MB | heap min MB |");
      System.out.println("|---|---|---|---|---|---|---|---|---|---|");
      for (Path archivo : archivos) {
        System.out.println(medir(archivo, minHeap));
      }
    } finally {
      if (!conservar) {
        try (var rutas = Files.walk(dir)) {
          rutas.sorted(Comparator.reverseOrder()).forEach((p) -> p.toFile().delete());
        }
      }
    }
  }

  private static String medir(Path archivo, boolean minHeap) throws Exception {
    MockMultipartFile multipart = cargar(archivo);
    String nombre = archivo.getFileName().toString();
    SimulacionExcel.Analisis analisis;
    try {
      analisis = SimulacionExcel.analizar(multipart);
    } catch (RuntimeException e) {
      return "| " + nombre + " | ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " |";
    }
    for (int i = 0; i < CALENTAMIENTO; i++) {
      SimulacionExcel.analizar(multipart);
    }
    com.sun.management.ThreadMXBean hilos =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long[] pared = new long[CORRIDAS];
    long[] cpu = new long[CORRIDAS];
    long[] asignado = new long[CORRIDAS];
    long[] pico = new long[CORRIDAS];
    for (int i = 0; i < CORRIDAS; i++) {
      System.gc();
      Muestreador muestreador = new Muestreador();
      muestreador.start();
      long a0 = hilos.getCurrentThreadAllocatedBytes();
      long c0 = hilos.getCurrentThreadCpuTime();
      long t0 = System.nanoTime();
      SimulacionExcel.analizar(multipart);
      pared[i] = System.nanoTime() - t0;
      cpu[i] = hilos.getCurrentThreadCpuTime() - c0;
      asignado[i] = hilos.getCurrentThreadAllocatedBytes() - a0;
      pico[i] = muestreador.detener();
    }
    BigDecimal lineas =
        analisis.rutas().stream().map(SimulacionExcel.FilaRuta::lineas).reduce(BigDecimal.ZERO, BigDecimal::add);
    String heapMin = minHeap && nombre.contains("100000") ? heapMinimo(archivo) + "" : "-";
    return String.format(
        Locale.ROOT,
        "| %s | %.2f | %s | %d | %s | %d (%d-%d) | %d | %.1f | %.1f | %s |",
        nombre,
        Files.size(archivo) / 1048576.0,
        xmlDescomprimido(archivo),
        analisis.rutas().size(),
        lineas.signum() > 0 ? "sí" : "NO",
        ms(mediana(pared)),
        ms(Arrays.stream(pared).min().orElseThrow()),
        ms(Arrays.stream(pared).max().orElseThrow()),
        ms(mediana(cpu)),
        mediana(asignado) / 1048576.0,
        mediana(pico) / 1048576.0,
        heapMin);
  }

  /** Búsqueda binaria del menor -Xmx (MB) con el que el parseo termina en una JVM hija. */
  private static int heapMinimo(Path archivo) throws Exception {
    int bajo = 8;
    int alto = 512;
    if (!terminaCon(archivo, alto)) {
      return -1;
    }
    while (alto - bajo > 2) {
      int medio = (bajo + alto) / 2;
      if (terminaCon(archivo, medio)) {
        alto = medio;
      } else {
        bajo = medio;
      }
    }
    return alto;
  }

  private static boolean terminaCon(Path archivo, int mb) throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Process proceso =
        new ProcessBuilder(
                java,
                "-Xmx" + mb + "m",
                "-Xms" + Math.min(mb, 8) + "m",
                "-XX:+UseSerialGC",
                "-XX:ActiveProcessorCount=1",
                "-XX:+ExitOnOutOfMemoryError",
                "-cp",
                System.getProperty("java.class.path"),
                SimulacionExcelBenchmark.class.getName(),
                "--unico",
                archivo.toString())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (!proceso.waitFor(120, TimeUnit.SECONDS)) {
      proceso.destroyForcibly();
      return false;
    }
    return proceso.exitValue() == 0;
  }

  // ------------------------------------------------------------------
  // Generación de datos (semilla fija: resultados reproducibles)
  // ------------------------------------------------------------------

  /**
   * Libro con sharedStrings (como lo guarda Excel). {@code rutasUnicas}: cada fila es una ruta
   * distinta; {@code todoTexto}: todas las celdas son textos únicos (peor caso de sharedStrings).
   */
  static Path generarXlsx(Path dir, int filas, boolean rutasUnicas, boolean todoTexto)
      throws IOException {
    String sufijo = rutasUnicas ? "-rutas-unicas" : todoTexto ? "-todo-texto" : "";
    Path destino = dir.resolve("proyeccion-" + filas + sufijo + ".xlsx");
    Random azar = new Random(42);
    int rutas = rutasUnicas ? filas : Math.max(50, filas / 100);
    try (SXSSFWorkbook libro = new SXSSFWorkbook(new XSSFWorkbook(), 200, true, true);
        OutputStream salida = Files.newOutputStream(destino)) {
      Sheet hoja = libro.createSheet("Proyección");
      CellStyle miles = libro.createCellStyle();
      miles.setDataFormat(libro.createDataFormat().getFormat("#,##0.00"));
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
      Row primera = hoja.createRow(0);
      for (int c = 0; c < cabecera.length; c++) {
        primera.createCell(c).setCellValue(cabecera[c]);
      }
      for (int i = 0; i < filas; i++) {
        Row fila = hoja.createRow(i + 1);
        int ruta = rutasUnicas ? i : azar.nextInt(rutas);
        fila.createCell(0).setCellValue("COD-" + i);
        fila.createCell(1).setCellValue("R" + (3000 + ruta));
        fila.createCell(2).setCellValue("CL" + (ruta % 20));
        fila.createCell(3).setCellValue("CHUTE " + (ruta % 60));
        double[] valores = {
          centimos(azar, 50_000), centimos(azar, 500_000), centimos(azar, 100_000)
        };
        for (int v = 0; v < valores.length; v++) {
          var celda = fila.createCell(4 + v);
          if (todoTexto) {
            celda.setCellValue(BigDecimal.valueOf(valores[v]).toPlainString() + "");
          } else {
            celda.setCellValue(valores[v]);
            celda.setCellStyle(miles);
          }
        }
        fila.createCell(7).setCellValue("B2C");
      }
      libro.write(salida);
    }
    return destino;
  }

  /** CSV: UTF-8 con ',' y punto decimal, o Windows-1252 con ';' y coma decimal (Excel es-ES). */
  static Path generarCsv(Path dir, int filas, Charset charset, char separador, boolean comaDecimal)
      throws IOException {
    String sufijo = charset.equals(StandardCharsets.UTF_8) ? "-utf8" : "-cp1252";
    Path destino = dir.resolve("proyeccion-" + filas + sufijo + ".csv");
    Random azar = new Random(42);
    int rutas = Math.max(50, filas / 100);
    try (BufferedWriter salida = Files.newBufferedWriter(destino, charset)) {
      String s = String.valueOf(separador);
      salida.write(String.join(s, "COD.", "RUTA", "CLUSTER", "CHUTE", "BULTOS PROMEDIO",
          "UNIDADES PROMEDIO", "LÍNEAS PROMEDIO", "TIPO"));
      salida.write("\r\n");
      for (int i = 0; i < filas; i++) {
        int ruta = azar.nextInt(rutas);
        String[] valores = new String[3];
        double[] numeros = {centimos(azar, 50_000), centimos(azar, 500_000), centimos(azar, 100_000)};
        for (int v = 0; v < 3; v++) {
          String texto = String.format(Locale.ROOT, "%.2f", numeros[v]);
          valores[v] = comaDecimal ? texto.replace('.', ',') : texto;
        }
        salida.write(String.join(s, "COD-" + i, "R" + (3000 + ruta), "CL" + (ruta % 20),
            "CHUTE " + (ruta % 60), valores[0], valores[1], valores[2], "B2C"));
        salida.write("\r\n");
      }
    }
    return destino;
  }

  private static double centimos(Random azar, int maximo) {
    return (1 + azar.nextInt(maximo)) / 100.0;
  }

  // ------------------------------------------------------------------
  // Utilidades
  // ------------------------------------------------------------------

  private static MockMultipartFile cargar(Path archivo) throws IOException {
    String nombre = archivo.getFileName().toString();
    String tipo = nombre.endsWith(".csv") ? "text/csv" : XLSX_MIME;
    return new MockMultipartFile("archivo", nombre, tipo, Files.readAllBytes(archivo));
  }

  /** Tamaño descomprimido de la hoja y de sharedStrings (MB), para calibrar los topes. */
  private static String xmlDescomprimido(Path archivo) throws IOException {
    if (!archivo.toString().endsWith(".xlsx")) {
      return "-";
    }
    long hoja = 0;
    long cadenas = 0;
    try (ZipFile zip = new ZipFile(archivo.toFile())) {
      for (ZipEntry entrada : java.util.Collections.list(zip.entries())) {
        if (entrada.getName().startsWith("xl/worksheets/")) {
          hoja += entrada.getSize();
        } else if (entrada.getName().equals("xl/sharedStrings.xml")) {
          cadenas = entrada.getSize();
        }
      }
    }
    return String.format(Locale.ROOT, "hoja %.1f / sst %.1f", hoja / 1048576.0, cadenas / 1048576.0);
  }

  private static long mediana(long[] valores) {
    long[] copia = valores.clone();
    Arrays.sort(copia);
    return copia[copia.length / 2];
  }

  private static long ms(long nanos) {
    return nanos / 1_000_000L;
  }

  /** Muestrea el heap usado cada milisegundo; devuelve el pico sobre el valor inicial. */
  private static final class Muestreador extends Thread {
    private final MemoryMXBean memoria = ManagementFactory.getMemoryMXBean();
    private final AtomicBoolean activo = new AtomicBoolean(true);
    private final AtomicLong pico = new AtomicLong();
    private final long base = memoria.getHeapMemoryUsage().getUsed();

    Muestreador() {
      setDaemon(true);
    }

    @Override
    public void run() {
      while (activo.get()) {
        pico.accumulateAndGet(memoria.getHeapMemoryUsage().getUsed() - base, Math::max);
        try {
          Thread.sleep(1);
        } catch (InterruptedException e) {
          return;
        }
      }
    }

    long detener() throws InterruptedException {
      activo.set(false);
      join();
      return Math.max(0, pico.get());
    }
  }
}
