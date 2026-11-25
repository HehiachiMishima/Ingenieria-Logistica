package oracle.simulacion.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import oracle.common.config.Transacciones;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import oracle.common.support.ActorActual;
import oracle.common.support.Trazas;
import oracle.simulacion.model.ArchivoExcel;
import oracle.simulacion.model.Balanceo;
import oracle.simulacion.model.ParametroCluster;
import oracle.simulacion.repository.RutaProyeccionRepository;
import oracle.simulacion.repository.RutasDataRepository;
import oracle.simulacion.repository.RutasDataRepository.RutaData;
import oracle.simulacion.repository.SimulacionLotesRepository;
import oracle.simulacion.request.DataRequest;
import oracle.simulacion.response.CambioDataResponse;
import oracle.simulacion.service.SimulacionConfiguracionService.Contexto;
import org.springframework.stereotype.Service;

/**
 * DATA: el bloque de Configuración con las rutas que no se balancean. Ahí se crean (con uno o
 * varios locales), se editan, se eliminan y reciben tipo, frecuencia y grupo. Solo una ruta
 * completa pasa a un cluster; la celda donde se suelta (cluster y chute) es su posición original,
 * el «antes» del Dashboard. Un cluster que todavía no empezó a balancearse puede devolver sus
 * rutas a DATA. Los clusters se agregan o se quitan desde el último.
 *
 * <p>Toda escritura bloquea el escenario como las de Configuración (SELECT … FOR UPDATE), así que
 * no se cruzan con un balanceo en curso, y deja al día su estado y sus totales.
 */
@Slf4j
@Service
public class SimulacionDataService {

  private static final Pattern NOMBRE_CLUSTER = Pattern.compile("^CL\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);

  private final SimulacionConfiguracionService configuracion;
  private final RutasDataRepository repo;
  private final RutaProyeccionRepository rutaRepo;
  private final SimulacionLotesRepository lotes;
  private final PlanoChutes plano;
  private final Transacciones transacciones;

  public SimulacionDataService(
      SimulacionConfiguracionService configuracion,
      RutasDataRepository repo,
      RutaProyeccionRepository rutaRepo,
      SimulacionLotesRepository lotes,
      PlanoChutes plano,
      Transacciones transacciones) {
    this.configuracion = configuracion;
    this.repo = repo;
    this.rutaRepo = rutaRepo;
    this.lotes = lotes;
    this.plano = plano;
    this.transacciones = transacciones;
  }

  // ---------------- Rutas ----------------

  /** Crea la ruta en DATA (sin tipo, frecuencia ni grupo), en un chute al azar. */
  public ApiResponse<CambioDataResponse> crear(Long archivoId, DataRequest.Ruta req) {
    RutaNormalizada datos = RutaNormalizada.de(req);
    Long actor = ActorActual.id();
    escribir(
        "crear id=" + archivoId,
        () -> {
          Contexto ctx = bloquear(archivoId);
          if (repo.existe(archivoId, datos.codigo(), null, null)) {
            throw new ConflictoException(
                "Ya hay una ruta " + datos.codigo() + " sin tipo en DATA: asígnale un tipo o edítala.");
          }
          long id =
              repo.insertar(
                  archivoId,
                  datos.codigo(),
                  chuteAlAzar(ctx),
                  datos.bultos(),
                  datos.unidades(),
                  datos.lineas(),
                  datos.locales().size());
          repo.reemplazarLocales(id, locales(datos));
          configuracion.tocar(ctx.archivo(), actor);
          return null;
        });
    return listo("Ruta " + datos.codigo() + " creada en DATA." + avisoSumados(datos));
  }

  /** Cambia el número y los locales de una ruta de DATA (tipo, frecuencia y grupo se conservan). */
  public ApiResponse<CambioDataResponse> actualizar(Long archivoId, Long rutaId, DataRequest.Ruta req) {
    RutaNormalizada datos = RutaNormalizada.de(req);
    Long actor = ActorActual.id();
    String nombre =
        escribir(
            "actualizar id=" + archivoId + " ruta=" + rutaId,
            () -> {
              Contexto ctx = bloquear(archivoId);
              RutaData ruta = enData(ctx, rutaId, "editarla");
              if (repo.existe(archivoId, datos.codigo(), ruta.tipo(), rutaId)) {
                throw new ConflictoException("Ya existe la ruta " + nombre(datos.codigo(), ruta.tipo()) + ".");
              }
              repo.actualizar(
                  archivoId,
                  rutaId,
                  datos.codigo(),
                  datos.bultos(),
                  datos.unidades(),
                  datos.lineas(),
                  datos.locales().size());
              repo.reemplazarLocales(rutaId, locales(datos));
              configuracion.tocar(ctx.archivo(), actor);
              return nombre(datos.codigo(), ruta.tipo());
            });
    return listo("Ruta " + nombre + " guardada." + avisoSumados(datos));
  }

  /** Borra una ruta de DATA y sus locales. */
  public ApiResponse<CambioDataResponse> eliminar(Long archivoId, Long rutaId) {
    Long actor = ActorActual.id();
    String nombre =
        escribir(
            "eliminar id=" + archivoId + " ruta=" + rutaId,
            () -> {
              Contexto ctx = bloquear(archivoId);
              RutaData ruta = enData(ctx, rutaId, "eliminarla");
              repo.eliminar(archivoId, rutaId);
              configuracion.tocar(ctx.archivo(), actor);
              return nombre(ruta.codigo(), ruta.tipo());
            });
    return listo("Ruta " + nombre + " eliminada.");
  }

  // ---------------- Tipo, frecuencia y grupo (solo en DATA) ----------------

  /** Tipo para las rutas elegidas de DATA: todas o ninguna, sin dejar dos con el mismo número y tipo. */
  public ApiResponse<CambioDataResponse> asignarTipo(Long archivoId, DataRequest.Tipo req) {
    List<Long> ids = distintos(req.rutaIds());
    Long actor = ActorActual.id();
    int[] hechas =
        escribir(
            "tipo id=" + archivoId + " rutas=" + ids.size(),
            () -> {
              Contexto ctx = bloquear(archivoId);
              List<Long> enData = deData(archivoId, ids, "el tipo");
              List<String> repetidas = repo.repetidasConTipo(archivoId, enData, req.tipo());
              if (!repetidas.isEmpty()) {
                throw new ConflictoException(
                    "No se cambió el tipo: quedarían repetidas como " + req.tipo() + " las rutas " + lista(repetidas) + ".");
              }
              int n = repo.asignarTipo(archivoId, enData, req.tipo());
              configuracion.tocar(ctx.archivo(), actor);
              return new int[] {n, ids.size() - enData.size()};
            });
    return listo("Tipo " + req.tipo() + " asignado a " + cantidad(hechas[0]) + "." + omitidas(hechas[1]));
  }

  /** Frecuencia para las rutas elegidas de DATA. */
  public ApiResponse<CambioDataResponse> asignarFrecuencia(Long archivoId, DataRequest.Frecuencia req) {
    List<Long> ids = distintos(req.rutaIds());
    Long actor = ActorActual.id();
    int[] hechas =
        escribir(
            "frecuencia id=" + archivoId + " rutas=" + ids.size(),
            () -> {
              Contexto ctx = bloquear(archivoId);
              List<Long> enData = deData(archivoId, ids, "la frecuencia");
              int n = repo.asignarFrecuencia(archivoId, enData, req.frecuencia());
              configuracion.tocar(ctx.archivo(), actor);
              return new int[] {n, ids.size() - enData.size()};
            });
    return listo(
        "Frecuencia " + NombresSimulacion.textoFrecuencia(req.frecuencia()) + " asignada a " + cantidad(hechas[0]) + "."
            + omitidas(hechas[1]));
  }

  /** Grupo para las rutas elegidas de DATA. Se guarda limpio («  a » → «A»). */
  public ApiResponse<CambioDataResponse> asignarGrupo(Long archivoId, DataRequest.Grupo req) {
    List<Long> ids = distintos(req.rutaIds());
    String grupo = RutaNormalizada.texto(req.grupo());
    if (grupo.isEmpty()) {
      throw new SolicitudInvalidaException("Escribe el grupo.");
    }
    Long actor = ActorActual.id();
    int[] hechas =
        escribir(
            "grupo id=" + archivoId + " rutas=" + ids.size(),
            () -> {
              Contexto ctx = bloquear(archivoId);
              List<Long> enData = deData(archivoId, ids, "el grupo");
              int n = repo.asignarGrupo(archivoId, enData, grupo);
              configuracion.tocar(ctx.archivo(), actor);
              return new int[] {n, ids.size() - enData.size()};
            });
    return listo("Grupo " + grupo + " asignado a " + cantidad(hechas[0]) + "." + omitidas(hechas[1]));
  }

  // ---------------- Mover entre DATA y los clusters ----------------

  /**
   * Rutas a una celda: a un cluster (solo rutas de DATA y completas) o a DATA (desde DATA cambian de
   * columna; desde un cluster, solo si aún no empezó a balancearse). Todas o ninguna.
   */
  public ApiResponse<CambioDataResponse> mover(Long archivoId, DataRequest.Mover req) {
    List<Long> ids = distintos(req.rutaIds());
    Long actor = ActorActual.id();
    String mensaje =
        escribir(
            "mover id=" + archivoId + " rutas=" + ids.size(),
            () -> {
              Contexto ctx = bloquear(archivoId);
              Long destino = req.clusterId();
              long chute = req.chuteId();
              if (!ctx.parametrosChute().containsKey(chute)) {
                throw new SolicitudInvalidaException("El chute " + chute + " no pertenece al registro.");
              }
              if (destino != null && !ctx.parametrosCluster().containsKey(destino)) {
                throw new SolicitudInvalidaException("El cluster " + destino + " no pertenece al registro.");
              }
              List<RutaData> rutas = repo.rutas(archivoId, ids);
              if (rutas.size() != ids.size()) {
                throw new ConflictoException("Alguna ruta ya no existe: pulsa «Actualizar».");
              }
              String hecho = destino == null ? aData(ctx, rutas, chute) : aCluster(ctx, rutas, destino, chute);
              alCambiarRutas(ctx, actor);
              return hecho;
            });
    return listo(mensaje);
  }

  private String aData(Contexto ctx, List<RutaData> rutas, long chute) {
    Long archivoId = ctx.archivo().getId();
    List<RutaData> cambian = rutas.stream().filter((r) -> !r.enData() || r.chuteId() != chute).toList();
    if (cambian.isEmpty()) {
      throw new ConflictoException("Las rutas ya están en DATA · " + SimulacionConfiguracionService.etiquetaChute(ctx, chute) + ".");
    }
    exigirNoBloqueado(ctx, chute);
    // Un cluster que ya empezó a balancearse (o está cerrado) no devuelve rutas a DATA.
    Set<Long> origenes = new LinkedHashSet<>();
    cambian.stream().filter((r) -> !r.enData()).forEach((r) -> origenes.add(r.clusterId()));
    for (Long origen : origenes) {
      exigirSinEmpezar(ctx, origen, "devolver sus rutas a DATA");
    }
    repo.aData(archivoId, cambian.stream().map(RutaData::id).toList(), chute);
    repo.borrarHistorialDeRutas(
        archivoId, cambian.stream().filter((r) -> !r.enData()).map(RutaData::id).toList());
    return cantidad(cambian.size()) + " → DATA · " + SimulacionConfiguracionService.etiquetaChute(ctx, chute) + ".";
  }

  private String aCluster(Contexto ctx, List<RutaData> rutas, long destino, long chute) {
    Long archivoId = ctx.archivo().getId();
    String nombre = ctx.nombreCluster(destino);
    List<String> enCluster = rutas.stream().filter((r) -> !r.enData()).map(RutaData::codigo).toList();
    if (!enCluster.isEmpty()) {
      throw new ConflictoException(
          "Desde DATA solo salen rutas de DATA: " + lista(enCluster) + " ya están en un cluster. Muévelas por separado.");
    }
    List<String> incompletas = rutas.stream().filter((r) -> !r.completa()).map(RutaData::codigo).toList();
    if (!incompletas.isEmpty()) {
      throw new ConflictoException(
          "Asigna tipo, frecuencia y grupo antes de pasarlas a un cluster: " + lista(incompletas) + ".");
    }
    if (cerradoEnAlguno(ctx, destino)) {
      throw new ConflictoException(nombre + " está cerrado: reábrelo para recibir rutas.");
    }
    exigirNoBloqueado(ctx, chute);
    // Un cluster vacío entra a la secuencia del balanceo: no puede quedar antes de uno que ya empezó.
    if (!ctx.conRutas().contains(destino)) {
      List<Long> todos = todosLosClusters(ctx);
      for (Long siguiente : todos.subList(todos.indexOf(destino) + 1, todos.size())) {
        if (ctx.conRutas().contains(siguiente) && empezado(ctx, siguiente)) {
          throw new ConflictoException(
              nombre + " va antes de " + ctx.nombreCluster(siguiente)
                  + ", que ya empezó a balancearse: usa un cluster posterior o restablece "
                  + ctx.nombreCluster(siguiente) + ".");
        }
      }
    }
    List<Long> ids = rutas.stream().map(RutaData::id).toList();
    if (repo.aCluster(archivoId, ids, destino, chute) != ids.size()) {
      throw new ConflictoException("Alguna ruta ya no está en DATA: pulsa «Actualizar».");
    }
    // Cambia la carga del cluster: si ya tenía operarios, vuelve al paso 2 (como al mover a mano).
    Set<Balanceo> balanceos = EnumSet.noneOf(Balanceo.class);
    for (RutaData ruta : rutas) {
      Arrays.stream(Balanceo.values()).filter((b) -> b.incluye(ruta.frecuencia())).forEach(balanceos::add);
    }
    configuracion.invalidarOperariosEn(ctx, balanceos, List.of(destino));
    return cantidad(ids.size()) + " → " + nombre + " · " + SimulacionConfiguracionService.etiquetaChute(ctx, chute) + ".";
  }

  // ---------------- Clusters ----------------

  /** Cuántos clusters hay. Se agregan al final (CL4, CL5…); al quitar, sus rutas vuelven a DATA. */
  public ApiResponse<CambioDataResponse> cambiarClusters(Long archivoId, DataRequest.Clusters req) {
    int cantidad = req.cantidad();
    Long actor = ActorActual.id();
    String mensaje =
        escribir(
            "clusters id=" + archivoId + " cantidad=" + cantidad,
            () -> {
              Contexto ctx = bloquear(archivoId);
              List<Long> todos = todosLosClusters(ctx);
              int actual = todos.size();
              String hecho;
              if (cantidad > actual) {
                int ultimo = todos.stream().mapToInt((id) -> numero(ctx.nombreCluster(id))).max().orElse(0);
                ultimo = Math.max(ultimo, actual);
                List<String> nuevos = new ArrayList<>();
                for (int i = 1; i <= cantidad - actual; i++) {
                  nuevos.add("CL" + (ultimo + i));
                }
                lotes.asegurarParametrosCluster(archivoId, lotes.asegurarClusters(nuevos).values());
                hecho = "Ahora hay " + cantidad + (cantidad == 1 ? " cluster." : " clusters.");
              } else if (cantidad < actual) {
                List<Long> quitar = todos.subList(cantidad, actual);
                for (Long id : quitar) {
                  exigirSinEmpezar(ctx, id, "quitarlo");
                }
                List<Long> vuelven = repo.devolverADataEnSuChute(archivoId, quitar);
                repo.borrarHistorialDeRutas(archivoId, vuelven);
                repo.quitarClusters(archivoId, quitar);
                hecho =
                    "Ahora hay " + cantidad + (cantidad == 1 ? " cluster" : " clusters")
                        + (vuelven.isEmpty() ? "." : "; " + cantidad(vuelven.size()) + " volvieron a DATA.");
              } else {
                hecho = "Los clusters ya eran " + cantidad + ".";
              }
              alCambiarRutas(ctx, actor);
              return hecho;
            });
    return listo(mensaje);
  }

  // ---------------- Reglas ----------------

  /** Bloquea el escenario (como toda escritura de Configuración) y arma su contexto. */
  private Contexto bloquear(Long archivoId) {
    return configuracion.contexto(archivoId, Balanceo.LXV, true);
  }

  /** La ruta existe y está en DATA; si no, se dice dónde está. */
  private RutaData enData(Contexto ctx, Long rutaId, String accion) {
    List<RutaData> rutas = repo.rutas(ctx.archivo().getId(), List.of(rutaId));
    if (rutas.isEmpty()) {
      throw new RecursoNoEncontradoException("La ruta ya no existe: pulsa «Actualizar».");
    }
    RutaData ruta = rutas.getFirst();
    if (!ruta.enData()) {
      throw new ConflictoException(
          "La ruta " + ruta.codigo() + " está en " + ctx.nombreCluster(ruta.clusterId())
              + ": devuélvela a DATA para " + accion + ".");
    }
    return ruta;
  }

  /** De las elegidas, las que están en DATA (las únicas que reciben tipo, frecuencia o grupo). */
  private List<Long> deData(Long archivoId, List<Long> ids, String que) {
    List<RutaData> rutas = repo.rutas(archivoId, ids);
    if (rutas.size() != ids.size()) {
      throw new ConflictoException("Alguna ruta ya no existe: pulsa «Actualizar».");
    }
    List<Long> enData = rutas.stream().filter(RutaData::enData).map(RutaData::id).toList();
    if (enData.isEmpty()) {
      throw new SolicitudInvalidaException("Esas rutas están en un cluster: " + que + " se asigna en DATA.");
    }
    return enData;
  }

  /** Tras mover rutas: clusters con rutas, estado del escenario, totales y su versión. */
  private void alCambiarRutas(Contexto ctx, Long actor) {
    ctx.conRutas().clear();
    ctx.conRutas().addAll(rutaRepo.clustersConRutas(ctx.archivo().getId()));
    SimulacionConfiguracionService.actualizarEstado(ctx);
    RutasDataRepository.Totales t = repo.totales(ctx.archivo().getId());
    ArchivoExcel archivo = ctx.archivo();
    archivo.setTotalRutas(t.rutas());
    archivo.setTotalChutes(t.chutes());
    archivo.setTotalBultos(t.bultos());
    archivo.setTotalUnidades(t.unidades());
    archivo.setTotalLineas(t.lineas());
    archivo.setTotalCod(t.cod());
    configuracion.tocar(archivo, actor);
  }

  /** Todos los clusters del escenario (también los vacíos), en orden natural (CL1, CL2…, CL10). */
  private static List<Long> todosLosClusters(Contexto ctx) {
    Set<Long> ids = new LinkedHashSet<>();
    ctx.parametros().values().forEach((porCluster) -> ids.addAll(porCluster.keySet()));
    return ids.stream()
        .sorted(Comparator.comparing(ctx::nombreCluster, SimulacionConfiguracionService.ORDEN_NATURAL))
        .toList();
  }

  private static boolean cerradoEnAlguno(Contexto ctx, Long clusterId) {
    return Arrays.stream(Balanceo.values())
        .map((b) -> ctx.parametro(b, clusterId))
        .anyMatch((p) -> p != null && ParametroCluster.ESTADO_CERRADO.equals(p.getEstado()));
  }

  /** Ya se balanceó algo del cluster (en cualquier balanceo). */
  private static boolean empezado(Contexto ctx, Long clusterId) {
    return Arrays.stream(Balanceo.values())
        .anyMatch((b) -> !SimulacionConfiguracionService.pendiente(ctx.parametro(b, clusterId)));
  }

  private static void exigirSinEmpezar(Contexto ctx, Long clusterId, String accion) {
    String nombre = ctx.nombreCluster(clusterId);
    if (cerradoEnAlguno(ctx, clusterId)) {
      throw new ConflictoException(nombre + " está cerrado: reábrelo y restablécelo para " + accion + ".");
    }
    if (empezado(ctx, clusterId)) {
      throw new ConflictoException(nombre + " ya empezó a balancearse: restablécelo para " + accion + ".");
    }
  }

  private void exigirNoBloqueado(Contexto ctx, long chute) {
    if (configuracion.bloqueado(ctx, chute)) {
      throw new ConflictoException(
          "El " + SimulacionConfiguracionService.etiquetaChute(ctx, chute) + " está bloqueado: no se pueden mover rutas ahí.");
    }
  }

  /** Un chute visible del plano (sin los bloqueados), al azar: la columna donde aparece en DATA. */
  private long chuteAlAzar(Contexto ctx) {
    List<Long> chutes =
        plano.idsVisibles().stream().filter(ctx.parametrosChute()::containsKey).toList();
    if (chutes.isEmpty()) {
      throw new ConflictoException("El escenario no tiene chutes visibles: revisa el plano.");
    }
    return chutes.get(ThreadLocalRandom.current().nextInt(chutes.size()));
  }

  private static List<RutasDataRepository.Local> locales(RutaNormalizada datos) {
    return datos.locales().stream()
        .map((l) -> new RutasDataRepository.Local(l.cod(), l.filas(), l.bultos(), l.unidades(), l.lineas()))
        .toList();
  }

  /** Número de «CL7» (0 si el nombre no tiene esa forma). */
  static int numero(String nombre) {
    Matcher m = NOMBRE_CLUSTER.matcher(nombre == null ? "" : nombre.trim().toUpperCase(Locale.ROOT));
    return m.matches() ? Integer.parseInt(m.group(1)) : 0;
  }

  /** «2002 B2C», o «2002 (sin tipo)» mientras no se le asigne. */
  static String nombre(String codigo, String tipo) {
    return codigo + (tipo == null ? " (sin tipo)" : " " + tipo);
  }

  private static List<Long> distintos(List<Long> ids) {
    return List.copyOf(new LinkedHashSet<>(ids.stream().filter(Objects::nonNull).toList()));
  }

  private static String avisoSumados(RutaNormalizada datos) {
    return datos.sumados() > 0 ? " Se sumaron los códigos repetidos." : "";
  }

  private static String omitidas(int n) {
    return n == 0 ? "" : " " + cantidad(n) + " en un cluster " + (n == 1 ? "se dejó" : "se dejaron") + " igual.";
  }

  private static String cantidad(int n) {
    return n == 1 ? "1 ruta" : n + " rutas";
  }

  /** «2002, 2015 y 3 más». */
  static String lista(List<String> valores) {
    int mostrar = Math.min(valores.size(), 5);
    String primeras = String.join(", ", valores.subList(0, mostrar));
    return valores.size() > mostrar ? primeras + " y " + (valores.size() - mostrar) + " más" : primeras;
  }

  private static ApiResponse<CambioDataResponse> listo(String mensaje) {
    return ApiResponse.ok(mensaje, new CambioDataResponse(mensaje));
  }

  private <T> T escribir(String operacion, Supplier<T> trabajo) {
    return Trazas.medir(log, "SimulacionDataService." + operacion, () -> transacciones.ejecutar(trabajo));
  }
}
