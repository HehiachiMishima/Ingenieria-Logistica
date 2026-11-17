package oracle.simulacionsanta.service;

import jakarta.persistence.criteria.Predicate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import oracle.common.config.Transacciones;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.LimiteExcedidoException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import oracle.common.response.PaginaResponse;
import oracle.common.support.ActorActual;
import oracle.common.support.Auditoria;
import oracle.common.support.Filtros;
import oracle.common.support.Trazas;
import oracle.simulacionsanta.model.EstadoRegistro;
import oracle.simulacionsanta.model.RegistroSanta;
import oracle.simulacionsanta.repository.PredeterminadosGuardados;
import oracle.simulacionsanta.repository.RegistroSantaRepository;
import oracle.simulacionsanta.repository.SimulacionSantaLotesRepository;
import oracle.simulacionsanta.request.RegistroNombreRequest;
import oracle.simulacionsanta.response.RegistroResponse;
import oracle.simulacionsanta.response.ValidacionExcelResponse;
import oracle.simulacionsanta.service.ExcelSantaAnita.ArchivoInvalidoException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Cargas de Excel de Santa Anita: listar, validar, subir, renombrar y eliminar. */
@Slf4j
@Service
public class SimulacionSantaRegistroService {

  static final int MAX_TAMANO_PAGINA = 100;
  static final int MAX_NOMBRE = 150;
  /**
   * Archivos que se leen a la vez por instancia (validar o subir): cada uno puede tener 10 MB.
   * Una ráfaga responde 429 en lugar de agotar memoria o CPU para todos.
   */
  static final int MAX_CARGAS_SIMULTANEAS = 2;
  /** Ventana en la que la misma carga (mismo usuario, contenido y nombre) no se duplica. */
  static final Duration VENTANA_DUPLICADOS = Duration.ofMinutes(10);
  static final String MENSAJE_CREADO = "Registro creado.";
  static final String MENSAJE_REPETIDO =
      "Este archivo ya se había registrado hace un momento; se devuelve el registro existente.";

  private final RegistroSantaRepository registros;
  private final SimulacionSantaLotesRepository lotes;
  private final Transacciones transacciones;
  private final Semaphore cargas = new Semaphore(MAX_CARGAS_SIMULTANEAS);
  private final CargasRecientes recientes;

  @Autowired
  public SimulacionSantaRegistroService(
      RegistroSantaRepository registros,
      SimulacionSantaLotesRepository lotes,
      Transacciones transacciones) {
    this(registros, lotes, transacciones, Clock.systemUTC());
  }

  SimulacionSantaRegistroService(
      RegistroSantaRepository registros,
      SimulacionSantaLotesRepository lotes,
      Transacciones transacciones,
      Clock reloj) {
    this.registros = registros;
    this.lotes = lotes;
    this.transacciones = transacciones;
    this.recientes = new CargasRecientes(reloj, VENTANA_DUPLICADOS);
  }

  /**
   * Página de registros, recientes primero. {@code texto} busca en nombre y archivo (sin
   * distinguir mayúsculas, comodines literales); {@code estados} acepta valores repetidos o
   * separados por coma.
   */
  public ApiResponse<PaginaResponse<RegistroResponse>> listar(
      int pagina, int tamano, String texto, List<String> estados) {
    int p = Math.max(pagina, 1) - 1;
    int t = Math.min(Math.max(tamano, 1), MAX_TAMANO_PAGINA);
    List<String> filtroEstados = estados(estados);
    String termino = Filtros.normalizar(texto);
    PageRequest pageable =
        PageRequest.of(
            p, t, Sort.by(Sort.Direction.DESC, "creadoEn").and(Sort.by(Sort.Direction.DESC, "id")));
    Specification<RegistroSanta> spec =
        (root, query, cb) -> {
          List<Predicate> condiciones = new ArrayList<>();
          if (!filtroEstados.isEmpty()) {
            condiciones.add(root.get("estado").in(filtroEstados));
          }
          if (termino != null) {
            String patron = "%" + Filtros.escaparLike(termino.toLowerCase(Locale.ROOT)) + "%";
            condiciones.add(
                cb.or(
                    cb.like(cb.lower(root.get("nombre")), patron, '\\'),
                    cb.like(cb.lower(root.get("nombreArchivo")), patron, '\\')));
          }
          return cb.and(condiciones.toArray(Predicate[]::new));
        };
    var datos =
        Trazas.medir(
            log,
            "SimulacionSantaRegistroService.listar",
            () ->
                transacciones.consultar(
                    () -> {
                      Page<RegistroSanta> encontrados = registros.findAll(spec, pageable);
                      return PaginaResponse.de(encontrados, respuestas(encontrados.getContent()));
                    }));
    return ApiResponse.ok("Registros listados.", datos);
  }

  /** Los 200 registros más recientes, para los selectores. */
  public ApiResponse<List<RegistroResponse>> opciones() {
    var datos =
        Trazas.medir(
            log,
            "SimulacionSantaRegistroService.opciones",
            () ->
                transacciones.consultar(
                    () -> respuestas(registros.findTop200ByOrderByCreadoEnDescIdDesc())));
    return ApiResponse.ok("Registros disponibles.", datos);
  }

  public ApiResponse<RegistroResponse> obtener(Long id) {
    var datos =
        transacciones.consultar(
            () -> SimulacionSantaRespuestas.registro(registros.findById(id).orElseThrow(() -> noExiste(id))));
    return ApiResponse.ok("Registro.", datos);
  }

  /** Lee el Excel y dice qué se guardaría, sin guardar nada. */
  public ApiResponse<ValidacionExcelResponse> validar(MultipartFile archivo) {
    ExcelSantaAnita.verificar(archivo);
    return Trazas.medir(
        log,
        "SimulacionSantaRegistroService.validar",
        () -> {
          if (!cargas.tryAcquire()) {
            throw ocupado();
          }
          try {
            ExcelSantaAnita.Resultado resultado = ExcelSantaAnita.leer(archivo);
            return ApiResponse.ok(
                "Archivo leído: " + resultado.filas().size() + " fila(s) válida(s) y "
                    + resultado.totalErrores() + " con error.",
                SimulacionSantaRespuestas.validacion(resultado));
          } finally {
            cargas.release();
          }
        });
  }

  /**
   * Alta de un registro con las filas válidas del Excel. El archivo se lee fuera de la
   * transacción y todo se guarda en una sola (registro, filas y la copia de los predeterminados
   * vigentes). Idempotente en una ventana corta: si el mismo usuario reenvía el mismo contenido
   * con el mismo nombre (p. ej. un reintento tras un timeout), se devuelve el registro ya creado;
   * si la primera carga sigue en proceso, 409.
   */
  public ApiResponse<RegistroResponse> subir(MultipartFile archivo, String nombre) {
    Long actor = ActorActual.id();
    String nombreLimpio = nombreValido(nombre);
    ExcelSantaAnita.verificar(archivo);
    return Trazas.medir(
        log,
        "SimulacionSantaRegistroService.subir",
        () -> procesar(archivo, nombreLimpio, actor));
  }

  private ApiResponse<RegistroResponse> procesar(
      MultipartFile archivo, String nombre, Long actor) {
    String huella = huella(archivo, actor, nombre);
    Long previo = recientes.reservar(huella);
    if (previo != null) {
      Optional<RegistroResponse> existente = existente(previo);
      if (existente.isPresent()) {
        log.info("Carga repetida de Santa Anita: se devuelve el registro {}", previo);
        return ApiResponse.ok(MENSAJE_REPETIDO, existente.get());
      }
      // Se eliminó entretanto: se procesa como nueva.
      recientes.olvidar(huella, previo);
      if (recientes.reservar(huella) != null) {
        throw cargaEnCurso();
      }
    }
    boolean registrado = false;
    try {
      if (!cargas.tryAcquire()) {
        throw ocupado();
      }
      try {
        ExcelSantaAnita.Resultado resultado = ExcelSantaAnita.leer(archivo);
        RegistroSanta guardado =
            transacciones.ejecutar(() -> persistir(nombre, resultado, actor));
        recientes.completar(huella, guardado.getId());
        registrado = true;
        return ApiResponse.ok(MENSAJE_CREADO, SimulacionSantaRespuestas.registro(guardado));
      } finally {
        cargas.release();
      }
    } finally {
      if (!registrado) {
        recientes.liberar(huella);
      }
    }
  }

  private RegistroSanta persistir(String nombre, ExcelSantaAnita.Resultado resultado, Long actor) {
    // FOR SHARE: un cambio simultáneo de los predeterminados espera a que esta carga confirme,
    // así el registro copia una configuración coherente.
    PredeterminadosGuardados guardados = lotes.predeterminados(true);
    ConfiguracionSanta config =
        guardados == null
            ? ConfiguracionSanta.fabrica()
            : ConfiguracionSanta.guardada(
                guardados.operarios(),
                guardados.bultosHora(),
                guardados.ordenClusters(),
                guardados.ubicaciones());
    List<Integer> bultos = resultado.bultosEnvios();
    List<Integer> porCluster = resultado.paletasCluster();
    List<String> orden = config.ordenComoTexto();

    RegistroSanta registro = new RegistroSanta();
    registro.setNombre(nombre);
    registro.setNombreArchivo(limitar(resultado.nombreArchivo(), 255));
    registro.setEnvioActual(0);
    registro.setPaletas(resultado.filas().size());
    registro.setRutas(resultado.rutas());
    registro.setFilasOmitidas(resultado.totalErrores());
    registro.setBultosEnvio1(bultos.get(0));
    registro.setBultosEnvio2(bultos.get(1));
    registro.setBultosEnvio3(bultos.get(2));
    registro.setPaletasCluster1(porCluster.get(0));
    registro.setPaletasCluster2(porCluster.get(1));
    registro.setPaletasCluster3(porCluster.get(2));
    registro.setOperarios(config.operarios());
    registro.setBultosHora(config.bultosHora());
    registro.setOrdenCluster1(orden.get(0));
    registro.setOrdenCluster2(orden.get(1));
    registro.setOrdenCluster3(orden.get(2));
    Auditoria.alCrear(registro, actor);
    registro.setConfiguradoPor(actor);
    registro.setConfiguradoEn(registro.getCreadoEn());
    RegistroSanta guardado = registros.saveAndFlush(registro);

    lotes.insertarFilas(guardado.getId(), resultado.filas());
    lotes.guardarUbicaciones(guardado.getId(), config.ubicaciones());
    return guardado;
  }

  public ApiResponse<RegistroResponse> renombrar(Long id, RegistroNombreRequest req) {
    Long actor = ActorActual.id();
    String nombre = nombreValido(req.nombre());
    var datos =
        Trazas.medir(
            log,
            "SimulacionSantaRegistroService.renombrar id=" + id,
            () ->
                transacciones.ejecutar(
                    () -> {
                      RegistroSanta registro = bloquear(id);
                      registro.setNombre(nombre);
                      Auditoria.alActualizar(registro, actor);
                      return SimulacionSantaRespuestas.registro(registros.save(registro));
                    }));
    return ApiResponse.ok("Registro actualizado.", datos);
  }

  /** Elimina el registro; sus filas y ubicaciones caen en cascada (ON DELETE CASCADE). */
  public ApiResponse<Void> eliminar(Long id) {
    Trazas.medir(
        log,
        "SimulacionSantaRegistroService.eliminar id=" + id,
        () ->
            transacciones.<Void>ejecutar(
                () -> {
                  registros.delete(bloquear(id));
                  return null;
                }));
    return ApiResponse.ok("Registro eliminado.", null);
  }

  // ------------------------------------------------------------------

  /** Estados del filtro: repetidos o separados por coma; 400 si alguno no existe. */
  static List<String> estados(List<String> valores) {
    if (valores == null) {
      return List.of();
    }
    Set<String> estados = new LinkedHashSet<>();
    for (String valor : valores) {
      if (valor == null) {
        continue;
      }
      for (String parte : valor.split(",")) {
        if (parte.isBlank()) {
          continue;
        }
        EstadoRegistro estado = EstadoRegistro.desdeTexto(parte);
        if (estado == null) {
          throw new SolicitudInvalidaException(
              "El estado «" + parte.trim() + "» no existe: usa PENDIENTE, EN_CURSO o COMPLETO.");
        }
        estados.add(estado.name());
      }
    }
    return List.copyOf(estados);
  }

  /** Nombre recortado de 1 a 150 caracteres; 400 si no. */
  static String nombreValido(String nombre) {
    String limpio = nombre == null ? "" : nombre.strip();
    if (limpio.isEmpty()) {
      throw new SolicitudInvalidaException("Indica el nombre del registro.");
    }
    if (limpio.length() > MAX_NOMBRE) {
      throw new SolicitudInvalidaException("El nombre admite hasta 150 caracteres.");
    }
    return limpio;
  }

  /**
   * SHA-256 del contenido más usuario, nombre del archivo y nombre del registro: un reintento
   * idéntico da la misma huella; el mismo archivo con otro nombre es otra carga.
   */
  static String huella(MultipartFile archivo, Long actor, String nombre) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      try (InputStream entrada = archivo.getInputStream()) {
        byte[] bufer = new byte[64 * 1024];
        int leidos;
        while ((leidos = entrada.read(bufer)) > 0) {
          sha.update(bufer, 0, leidos);
        }
      }
      String datos = "\n" + actor + "\n" + archivo.getOriginalFilename() + "\n" + nombre;
      sha.update(datos.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(sha.digest());
    } catch (IOException e) {
      log.warn("No se pudo leer el archivo '{}' para su huella", archivo.getOriginalFilename(), e);
      throw ArchivoInvalidoException.ilegible(
          "No se pudo leer el archivo. Verifica que no esté dañado y vuelve a subirlo.");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 no disponible", e);
    }
  }

  private Optional<RegistroResponse> existente(Long id) {
    return transacciones.consultar(
        () -> registros.findById(id).map(SimulacionSantaRespuestas::registro));
  }

  private RegistroSanta bloquear(Long id) {
    return registros.bloquear(id).orElseThrow(() -> noExiste(id));
  }

  private static List<RegistroResponse> respuestas(List<RegistroSanta> lista) {
    return lista.stream().map(SimulacionSantaRespuestas::registro).toList();
  }

  static RecursoNoEncontradoException noExiste(Long id) {
    return new RecursoNoEncontradoException("No existe el registro con id " + id + ".");
  }

  private static LimiteExcedidoException ocupado() {
    return new LimiteExcedidoException(
        "Hay varios archivos en proceso. Intenta de nuevo en unos segundos.");
  }

  private static ConflictoException cargaEnCurso() {
    return new ConflictoException(
        "Este archivo ya se está registrando. Espera unos segundos y revisa el listado.");
  }

  private static String limitar(String texto, int maximo) {
    return texto.length() <= maximo ? texto : texto.substring(0, maximo);
  }

  /**
   * Cargas en curso o recién completadas, por huella (solo en memoria de esta instancia). Tamaño
   * acotado: las vencidas se purgan al reservar y, si aun así se llena, una carga completada
   * simplemente no se recuerda.
   */
  static final class CargasRecientes {
    private static final int MAX_ENTRADAS = 1_000;
    private static final Long EN_CURSO = -1L;

    private record Entrada(Long registroId, Instant momento) {}

    private final Map<String, Entrada> entradas = new ConcurrentHashMap<>();
    private final Clock reloj;
    private final Duration ventana;

    CargasRecientes(Clock reloj, Duration ventana) {
      this.reloj = reloj;
      this.ventana = ventana;
    }

    /**
     * Reserva la huella. null = quedó reservada para quien llama; un id = registro creado hace
     * menos de la ventana; 409 si otra carga igual sigue en curso.
     */
    Long reservar(String huella) {
      Instant ahora = reloj.instant();
      if (entradas.size() > MAX_ENTRADAS / 2) {
        entradas.values().removeIf((e) -> !EN_CURSO.equals(e.registroId()) && vencida(e, ahora));
      }
      Entrada nueva = new Entrada(EN_CURSO, ahora);
      Entrada actual =
          entradas.compute(
              huella,
              (clave, previa) ->
                  previa == null || (!EN_CURSO.equals(previa.registroId()) && vencida(previa, ahora))
                      ? nueva
                      : previa);
      if (actual == nueva) {
        return null;
      }
      if (EN_CURSO.equals(actual.registroId())) {
        throw cargaEnCurso();
      }
      return actual.registroId();
    }

    void completar(String huella, Long registroId) {
      if (entradas.size() >= MAX_ENTRADAS) {
        entradas.remove(huella);
        return;
      }
      entradas.put(huella, new Entrada(registroId, reloj.instant()));
    }

    void liberar(String huella) {
      entradas.computeIfPresent(huella, (clave, e) -> EN_CURSO.equals(e.registroId()) ? null : e);
    }

    void olvidar(String huella, Long registroId) {
      entradas.computeIfPresent(huella, (clave, e) -> registroId.equals(e.registroId()) ? null : e);
    }

    private boolean vencida(Entrada entrada, Instant ahora) {
      return entrada.momento().plus(ventana).isBefore(ahora);
    }
  }
}
