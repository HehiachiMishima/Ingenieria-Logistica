package oracle.simulacion.service;

import jakarta.persistence.criteria.Predicate;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import oracle.common.config.Transacciones;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.exception.LimiteExcedidoException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.common.response.ApiResponse;
import oracle.common.response.PaginaResponse;
import oracle.common.support.ActorActual;
import oracle.common.support.Auditoria;
import oracle.common.support.Filtros;
import oracle.common.support.Trazas;
import oracle.simulacion.model.ArchivoExcel;
import oracle.simulacion.model.ParametroChute;
import oracle.simulacion.model.SimulacionConfig;
import oracle.simulacion.repository.ArchivoExcelRepository;
import oracle.simulacion.repository.SimulacionLotesRepository;
import oracle.simulacion.request.RegistroActualizarRequest;
import oracle.simulacion.response.ArchivoSimulacionResponse;
import oracle.simulacion.service.SimulacionExcel.ArchivoInvalidoException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Registro de archivos Excel: alta con parseo, listado, edición y borrado. */
@Slf4j
@Service
public class SimulacionRegistroService {

  private static final int MAX_TAMANO_PAGINA = 100;
  private static final String COLOR_INICIAL = "#1d63ed";
  /**
   * Cargas que se procesan a la vez por instancia. En Render free (0,1 vCPU, 512 MB) dos cargas
   * concurrentes ya se reparten la CPU: más solo alarga cada una hacia el timeout. Una ráfaga
   * responde 429 en lugar de agotar memoria o el pool para todos.
   */
  static final int MAX_CARGAS_SIMULTANEAS = 2;
  /**
   * Presupuesto de heap (MB) para el parseo de cargas simultáneas. Con -XX:MaxRAMPercentage=70
   * sobre 512 MB el heap es ~350 MB, de los que la aplicación en reposo usa ~150 MB.
   */
  static final int PRESUPUESTO_MEMORIA_MB = 128;
  /** Ventana en la que la misma carga (mismo usuario, contenido y datos) no se duplica. */
  static final Duration VENTANA_DUPLICADOS = Duration.ofMinutes(10);
  static final String MENSAJE_CREADO = "Registro creado.";
  static final String MENSAJE_REPETIDO =
      "Este archivo ya se había registrado hace un momento; se devuelve el registro existente.";

  private final ArchivoExcelRepository archivoRepo;
  private final SimulacionLotesRepository lotes;
  private final Transacciones transacciones;
  private final Semaphore cargas = new Semaphore(MAX_CARGAS_SIMULTANEAS);
  private final Semaphore memoria = new Semaphore(PRESUPUESTO_MEMORIA_MB);
  private final CargasRecientes recientes;

  @Autowired
  public SimulacionRegistroService(
      ArchivoExcelRepository archivoRepo,
      SimulacionLotesRepository lotes,
      Transacciones transacciones) {
    this(archivoRepo, lotes, transacciones, Clock.systemUTC());
  }

  SimulacionRegistroService(
      ArchivoExcelRepository archivoRepo,
      SimulacionLotesRepository lotes,
      Transacciones transacciones,
      Clock reloj) {
    this.archivoRepo = archivoRepo;
    this.lotes = lotes;
    this.transacciones = transacciones;
    this.recientes = new CargasRecientes(reloj, VENTANA_DUPLICADOS);
  }

  public ApiResponse<PaginaResponse<ArchivoSimulacionResponse>> listar(
      int pagina, int tamano, String texto, List<String> estados) {
    int p = Math.max(pagina, 1) - 1;
    int t = Math.min(Math.max(tamano, 1), MAX_TAMANO_PAGINA);
    PageRequest pageable =
        PageRequest.of(
            p, t, Sort.by(Sort.Direction.DESC, "creadoEn").and(Sort.by(Sort.Direction.DESC, "id")));
    String termino = Filtros.normalizar(texto);
    List<String> filtroEstados = estados == null ? List.of() : estados.stream().filter((e) -> e != null && !e.isBlank()).toList();
    Specification<ArchivoExcel> spec =
        (root, query, cb) -> {
          List<Predicate> condiciones = new ArrayList<>();
          if (!filtroEstados.isEmpty()) {
            condiciones.add(root.get("estado").in(filtroEstados));
          }
          if (termino != null) {
            String patron = "%" + termino.toLowerCase(Locale.ROOT) + "%";
            Predicate nombre = cb.like(cb.lower(root.get("nombreRegistro")), patron);
            Predicate archivo = cb.like(cb.lower(root.get("nombreArchivo")), patron);
            Predicate etiqueta = cb.like(cb.lower(root.get("etiqueta")), patron);
            condiciones.add(cb.or(nombre, archivo, etiqueta));
          }
          return cb.and(condiciones.toArray(Predicate[]::new));
        };
    var datos =
        Trazas.medir(
            log,
            "SimulacionRegistroService.listar",
            () ->
                transacciones.consultar(
                    () -> {
                      Page<ArchivoExcel> page = archivoRepo.findAll(spec, pageable);
                      List<ArchivoSimulacionResponse> lista = aResponses(page.getContent());
                      return PaginaResponse.de(page, lista);
                    }));
    return ApiResponse.ok("Registros listados.", datos);
  }

  public ApiResponse<List<ArchivoSimulacionResponse>> opciones() {
    var datos =
        Trazas.medir(
            log,
            "SimulacionRegistroService.opciones",
            () ->
                transacciones.consultar(
                    () -> aResponses(archivoRepo.findTop200ByOrderByCreadoEnDescIdDesc())));
    return ApiResponse.ok("Registros disponibles.", datos);
  }

  /**
   * Alta de un registro desde un archivo. Idempotente en una ventana corta: si el mismo usuario
   * reenvía el mismo contenido con los mismos datos (p. ej. reintento tras un timeout del
   * cliente), se devuelve el registro ya creado en lugar de duplicarlo; si la primera carga sigue
   * en proceso, responde 409.
   */
  public ApiResponse<ArchivoSimulacionResponse> subir(
      MultipartFile archivo, String nombreRegistro, String etiqueta, String colorHex) {
    Long actor = ActorActual.id();
    return Trazas.medir(
        log,
        "SimulacionRegistroService.subir",
        () -> procesar(archivo, nombreRegistro, etiqueta, colorHex, actor));
  }

  private ApiResponse<ArchivoSimulacionResponse> procesar(
      MultipartFile archivo, String nombreRegistro, String etiqueta, String colorHex, Long actor) {
    validarArchivo(archivo);
    String color = normalizarColor(colorHex);
    String huella = huella(archivo, actor, nombreRegistro, etiqueta, color);
    Long previo = recientes.reservar(huella);
    if (previo != null) {
      Optional<ArchivoSimulacionResponse> existente = existente(previo);
      if (existente.isPresent()) {
        log.info("Carga repetida de simulación: se devuelve el registro {}", previo);
        return ApiResponse.ok(MENSAJE_REPETIDO, existente.get());
      }
      // El registro se borró entretanto: se procesa como nuevo.
      recientes.olvidar(huella, previo);
      if (recientes.reservar(huella) != null) {
        throw cargaEnCurso();
      }
    }
    boolean registrado = false;
    try {
      int costo = costoMemoriaMb(archivo);
      if (!cargas.tryAcquire()) {
        throw ocupado();
      }
      try {
        if (!memoria.tryAcquire(costo)) {
          throw ocupado();
        }
        try {
          ArchivoSimulacionResponse creado =
              registrar(archivo, nombreRegistro, etiqueta, color, actor);
          recientes.completar(huella, creado.id());
          registrado = true;
          return ApiResponse.ok(MENSAJE_CREADO, creado);
        } finally {
          memoria.release(costo);
        }
      } finally {
        cargas.release();
      }
    } finally {
      if (!registrado) {
        recientes.liberar(huella);
      }
    }
  }

  public ApiResponse<ArchivoSimulacionResponse> actualizar(
      Long id, RegistroActualizarRequest req) {
    Long actor = ActorActual.id();
    var datos =
        Trazas.medir(
            log,
            "SimulacionRegistroService.actualizar id=" + id,
            () ->
                transacciones.ejecutar(
                    () -> {
                      // Con bloqueo: un balanceo en curso sobre el mismo
                      // registro termina antes y su estado no se pisa.
                      ArchivoExcel archivo = buscarBloqueado(id);
                      archivo.setNombreRegistro(req.nombreRegistro().trim());
                      archivo.setEtiqueta(normalizarEtiqueta(req.etiqueta()));
                      archivo.setColorHex(normalizarColor(req.colorHex()));
                      Auditoria.alActualizar(archivo, actor);
                      return aResponses(List.of(archivoRepo.save(archivo))).get(0);
                    }));
    return ApiResponse.ok("Registro actualizado.", datos);
  }

  public ApiResponse<Void> eliminar(Long id) {
    Trazas.medir(
        log,
        "SimulacionRegistroService.eliminar id=" + id,
        () ->
            transacciones.<Void>ejecutar(
                () -> {
                  // Bloquea el registro: no se borra a mitad de un balanceo.
                  archivoRepo.delete(
                      archivoRepo
                          .bloquear(id)
                          .orElseThrow(() -> noExiste(id)));
                  return null;
                }));
    return ApiResponse.ok("Registro eliminado.", null);
  }

  private static void validarArchivo(MultipartFile archivo) {
    if (archivo == null || archivo.isEmpty()) {
      throw ArchivoInvalidoException.ilegible("Selecciona un archivo para registrar.");
    }
    String nombreOriginal = archivo.getOriginalFilename();
    if (nombreOriginal == null || nombreOriginal.isBlank()) {
      throw ArchivoInvalidoException.ilegible("El archivo no tiene nombre.");
    }
  }

  /**
   * Heap estimado (MB) para parsear el archivo, medido con SimulacionExcelBenchmark: un .xlsx de
   * 4,2 MB (100k filas) necesita 25–57 MB de heap; un .csv de 5 MB, ~25 MB. .xls (HSSF) carga el
   * libro entero, de ahí el mismo factor que .xlsx. Un archivo más caro que el presupuesto corre
   * solo.
   */
  static int costoMemoriaMb(MultipartFile archivo) {
    String nombre = String.valueOf(archivo.getOriginalFilename()).toLowerCase(Locale.ROOT);
    long megas = Math.max(1, archivo.getSize() / (1024 * 1024));
    long costo = nombre.endsWith(".csv") ? 8 + megas * 4 : 16 + megas * 12;
    return (int) Math.min(costo, PRESUPUESTO_MEMORIA_MB);
  }

  private static LimiteExcedidoException ocupado() {
    return new LimiteExcedidoException(
        "Hay varias cargas en proceso. Intenta de nuevo en unos segundos.");
  }

  private static ConflictoException cargaEnCurso() {
    return new ConflictoException(
        "Este archivo ya se está registrando. Espera unos segundos y revisa el listado.");
  }

  private Optional<ArchivoSimulacionResponse> existente(Long id) {
    return transacciones.consultar(
        () -> archivoRepo.findById(id).map((archivo) -> aResponses(List.of(archivo)).get(0)));
  }

  /**
   * SHA-256 del contenido más usuario y datos del formulario: un reintento idéntico produce la
   * misma huella; el mismo archivo con otro nombre de registro es otra carga.
   */
  static String huella(
      MultipartFile archivo, Long actor, String nombreRegistro, String etiqueta, String color) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      try (InputStream entrada = archivo.getInputStream()) {
        byte[] bufer = new byte[64 * 1024];
        int leidos;
        while ((leidos = entrada.read(bufer)) > 0) {
          sha.update(bufer, 0, leidos);
        }
      }
      String datos =
          "\n" + actor + "\n" + archivo.getOriginalFilename() + "\n" + limpio(nombreRegistro)
              + "\n" + limpio(etiqueta) + "\n" + color;
      sha.update(datos.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(sha.digest());
    } catch (IOException e) {
      log.warn("No se pudo leer el archivo '{}' para calcular su huella", archivo.getOriginalFilename(), e);
      throw ArchivoInvalidoException.ilegible(
          "No se pudo leer el archivo. Verifica que no esté dañado y vuelve a subirlo.");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 no disponible", e);
    }
  }

  private static String limpio(String texto) {
    return texto == null ? "" : texto.trim();
  }

  /** Lee el archivo fuera de la transacción y guarda todo en una sola. */
  private ArchivoSimulacionResponse registrar(
      MultipartFile archivo, String nombreRegistro, String etiqueta, String color, Long actor) {
    String nombreOriginal = archivo.getOriginalFilename();
    SimulacionExcel.Analisis analisis = SimulacionExcel.analizar(archivo);
    ArchivoExcel guardado =
        transacciones.ejecutar(
            () -> persistir(nombreOriginal, nombreRegistro, etiqueta, color, analisis, actor));
    return aResponses(List.of(guardado)).get(0);
  }

  private ArchivoExcel persistir(
      String nombreOriginal,
      String nombreRegistro,
      String etiqueta,
      String color,
      SimulacionExcel.Analisis analisis,
      Long actor) {
    ArchivoExcel entidad = new ArchivoExcel();
    entidad.setNombreArchivo(limitar(nombreOriginal, 255));
    entidad.setNombreRegistro(limitar(nombreRegistro(nombreRegistro, nombreOriginal), 150));
    entidad.setEtiqueta(normalizarEtiqueta(etiqueta));
    entidad.setColorHex(color);
    entidad.setEstado(ArchivoExcel.ESTADO_PROYECCION);
    List<SimulacionExcel.FilaRuta> rutas = analisis.rutas();
    entidad.setTotalRutas(rutas.size());
    entidad.setTotalChutes(
        (int) rutas.stream().map(SimulacionExcel.FilaRuta::chute).distinct().count());
    entidad.setTotalBultos(sumar(rutas, SimulacionExcel.FilaRuta::bultos));
    entidad.setTotalUnidades(sumar(rutas, SimulacionExcel.FilaRuta::unidades));
    entidad.setTotalLineas(sumar(rutas, SimulacionExcel.FilaRuta::lineas));
    entidad.setTotalCod(rutas.stream().mapToInt(SimulacionExcel.FilaRuta::conteoCod).sum());
    Auditoria.alCrear(entidad, actor);
    ArchivoExcel guardado = archivoRepo.saveAndFlush(entidad);
    Long archivoId = guardado.getId();

    Map<String, Long> clusters =
        lotes.asegurarClusters(rutas.stream().map(SimulacionExcel.FilaRuta::cluster).toList());
    Map<String, Long> chutes =
        lotes.asegurarChutes(rutas.stream().map(SimulacionExcel.FilaRuta::chute).toList());

    lotes.insertarConfig(archivoId, SimulacionConfig.VELOCIDAD_INICIAL, chutes.size(), actor);
    lotes.insertarParametrosCluster(archivoId, clusters.values());
    lotes.insertarParametrosChute(archivoId, chutes.values(), ParametroChute.OPERARIOS_INICIALES);
    lotes.insertarRutas(archivoId, rutas, clusters, chutes);
    return guardado;
  }

  private ArchivoExcel buscarBloqueado(Long id) {
    return archivoRepo.bloquear(id).orElseThrow(() -> noExiste(id));
  }

  private static RecursoNoEncontradoException noExiste(Long id) {
    return new RecursoNoEncontradoException("No existe el registro con id " + id + ".");
  }

  private static BigDecimal sumar(
      List<SimulacionExcel.FilaRuta> rutas, Function<SimulacionExcel.FilaRuta, BigDecimal> campo) {
    return rutas.stream().map(campo).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static List<ArchivoSimulacionResponse> aResponses(List<ArchivoExcel> archivos) {
    return archivos.stream().map(SimulacionRespuestas::archivo).toList();
  }

  private String nombreRegistro(String nombreRegistro, String nombreArchivo) {
    if (nombreRegistro != null && !nombreRegistro.isBlank()) {
      return nombreRegistro.trim();
    }
    String sinExtension = nombreArchivo.replaceFirst("(?i)\\.(xlsx|xls|csv)$", "");
    return sinExtension.isBlank() ? "Registro sin nombre" : sinExtension;
  }

  /** La etiqueta es opcional: si llega vacía se guarda NULL, no un texto ficticio. */
  private String normalizarEtiqueta(String etiqueta) {
    return etiqueta == null || etiqueta.isBlank() ? null : limitar(etiqueta.trim(), 50);
  }

  private String normalizarColor(String color) {
    if (color == null || color.isBlank()) {
      return COLOR_INICIAL;
    }
    String limpio = color.trim();
    if (!limpio.matches("^#[0-9a-fA-F]{6}$")) {
      throw new SolicitudInvalidaException("El color debe tener formato #RRGGBB.");
    }
    return limpio.toLowerCase(Locale.ROOT);
  }

  private String limitar(String texto, int maximo) {
    return texto.length() <= maximo ? texto : texto.substring(0, maximo);
  }

  /**
   * Cargas en curso o recién completadas, por huella (solo en memoria de esta instancia; sin
   * cambios de esquema). Tamaño acotado: las entradas vencidas se purgan al reservar y, si aun así
   * se llena, una carga completada simplemente no se recuerda.
   */
  static final class CargasRecientes {
    private static final int MAX_ENTRADAS = 1_000;
    private static final Long EN_CURSO = -1L;

    private record Entrada(Long archivoId, Instant momento) {}

    private final Map<String, Entrada> entradas = new ConcurrentHashMap<>();
    private final Clock reloj;
    private final Duration ventana;

    CargasRecientes(Clock reloj, Duration ventana) {
      this.reloj = reloj;
      this.ventana = ventana;
    }

    /**
     * Reserva la huella para procesarla. Devuelve null si quedó reservada para quien llama, o el
     * id del registro creado hace menos de la ventana. Lanza 409 si otra carga igual sigue en
     * curso.
     */
    Long reservar(String huella) {
      Instant ahora = reloj.instant();
      if (entradas.size() > MAX_ENTRADAS / 2) {
        entradas.values().removeIf((e) -> !EN_CURSO.equals(e.archivoId()) && vencida(e, ahora));
      }
      Entrada nueva = new Entrada(EN_CURSO, ahora);
      Entrada actual =
          entradas.compute(
              huella,
              (clave, previa) -> previa == null || (!EN_CURSO.equals(previa.archivoId()) && vencida(previa, ahora))
                  ? nueva
                  : previa);
      if (actual == nueva) {
        return null;
      }
      if (EN_CURSO.equals(actual.archivoId())) {
        throw cargaEnCurso();
      }
      return actual.archivoId();
    }

    void completar(String huella, Long archivoId) {
      if (entradas.size() >= MAX_ENTRADAS) {
        entradas.remove(huella);
        return;
      }
      entradas.put(huella, new Entrada(archivoId, reloj.instant()));
    }

    void liberar(String huella) {
      entradas.computeIfPresent(huella, (clave, e) -> EN_CURSO.equals(e.archivoId()) ? null : e);
    }

    void olvidar(String huella, Long archivoId) {
      entradas.computeIfPresent(huella, (clave, e) -> archivoId.equals(e.archivoId()) ? null : e);
    }

    int tamano() {
      return entradas.size();
    }

    private boolean vencida(Entrada entrada, Instant ahora) {
      return entrada.momento().plus(ventana).isBefore(ahora);
    }
  }
}
