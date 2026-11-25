package oracle.simulacion.diccionario;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import oracle.common.config.Transacciones;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import oracle.common.support.ActorActual;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Cambio;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Cluster;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Detalle;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Diccionario;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Local;
import oracle.simulacion.diccionario.DiccionarioRespuestas.Ruta;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * El Registro de Simulación Titan: diccionario de rutas. Cada ruta se crea con uno o varios
 * códigos (locales) y nace en Datos, sin cluster, tipo, frecuencia ni grupo. Al arrastrarla a un
 * bloque de cluster toma ese cluster; de vuelta a Datos los pierde todos. Tipo, frecuencia y grupo
 * solo los reciben las rutas que están en un cluster.
 *
 * <p>Cada cambio pone al día, en la misma transacción, el escenario de Configuración ({@link
 * EscenarioDiccionario}): ahí solo entran las rutas completas. Un cluster cerrado en Configuración
 * no se muestra y no deja cambiar sus rutas hasta que se reabra.
 */
@Service
public class DiccionarioRutasService {

  /** Rutas que dibuja la vista de una vez (una de más avisa que hay más). */
  static final int MAX_RUTAS = 20_000;
  /** Lo más que cabe en una columna NUMERIC(12,2). */
  static final BigDecimal MAXIMO = new BigDecimal("9999999999.99");

  private final DiccionarioRutasRepository repo;
  private final Transacciones transacciones;
  private final EscenarioDiccionario escenario;

  public DiccionarioRutasService(
      DiccionarioRutasRepository repo, Transacciones transacciones, EscenarioDiccionario escenario) {
    this.repo = repo;
    this.transacciones = transacciones;
    this.escenario = escenario;
  }

  /**
   * El escenario de Configuración (lo crea si falta), frenando el cambio si toca un cluster cerrado
   * allá: el de destino o el de alguna de esas rutas.
   */
  private long abrir(Long actor, Collection<Long> rutaIds, Long clusterDestino) {
    long archivoId = escenario.asegurar(actor);
    Set<Long> cerrados = escenario.clustersCerrados(archivoId);
    if (cerrados.isEmpty()) {
      return archivoId;
    }
    if (clusterDestino != null && cerrados.contains(clusterDestino)) {
      throw new ConflictoException("Ese cluster está cerrado en Configuración: reábrelo allá para cambiar sus rutas.");
    }
    if (rutaIds != null && !rutaIds.isEmpty() && repo.algunaEnClusters(rutaIds, cerrados)) {
      throw new ConflictoException(
          "Alguna ruta está en un cluster cerrado en Configuración: reábrelo allá para cambiarla.");
    }
    return archivoId;
  }

  // ---------------- Lectura ----------------

  public ApiResponse<Diccionario> ver() {
    return ApiResponse.ok("Diccionario de rutas.", transacciones.consultar(this::leer));
  }

  public ApiResponse<Detalle> detalle(Long id) {
    return ApiResponse.ok(
        "Ruta del diccionario.",
        transacciones.consultar(() -> new Detalle(rutaExistente(id), repo.locales(id))));
  }

  // ---------------- Rutas ----------------

  /** Crea la ruta en Datos (sin tipo, cluster, frecuencia ni grupo) con sus códigos (los repetidos se suman). */
  public ApiResponse<Ruta> crear(DiccionarioPeticiones.Ruta req) {
    DatosRuta datos = normalizar(req);
    Long actor = ActorActual.id();
    Ruta ruta =
        sinRepetir(
            datos.codigo(),
            () ->
                transacciones.ejecutar(
                    () -> {
                      exigirUnica(datos.codigo(), null, null);
                      long id = repo.insertarRuta(datos.codigo(), null, actor);
                      repo.reemplazarLocales(id, datos.locales());
                      return repo.rutasPorIds(List.of(id)).getFirst();
                    }));
    return ApiResponse.ok("Ruta " + ruta.codigoRuta() + " creada en Datos." + avisoSumados(datos), ruta);
  }

  /** Cambia el número y los códigos; tipo, cluster, frecuencia y grupo se conservan. */
  public ApiResponse<Ruta> actualizar(Long id, DiccionarioPeticiones.Ruta req) {
    DatosRuta datos = normalizar(req);
    Long actor = ActorActual.id();
    Ruta ruta =
        sinRepetir(
            datos.codigo(),
            () ->
                transacciones.ejecutar(
                    () -> {
                      String tipo = rutaExistente(id).tipo();
                      long archivoId = abrir(actor, List.of(id), null);
                      exigirUnica(datos.codigo(), tipo, id);
                      if (!repo.actualizarRuta(id, datos.codigo(), tipo, actor)) {
                        throw new RecursoNoEncontradoException("La ruta ya no existe: recarga el diccionario.");
                      }
                      repo.reemplazarLocales(id, datos.locales());
                      escenario.sincronizar(archivoId, List.of(id), actor);
                      return repo.rutasPorIds(List.of(id)).getFirst();
                    }));
    return ApiResponse.ok("Ruta " + nombre(ruta) + " guardada." + avisoSumados(datos), ruta);
  }

  /** Borra la ruta y sus locales; si estaba en Configuración, sale de ahí. */
  public ApiResponse<Void> eliminar(Long id) {
    Long actor = ActorActual.id();
    Ruta ruta =
        transacciones.ejecutar(
            () -> {
              Ruta existente = rutaExistente(id);
              long archivoId = abrir(actor, List.of(id), null);
              repo.eliminar(id);
              escenario.sincronizar(archivoId, List.of(id), actor);
              return existente;
            });
    return ApiResponse.ok("Ruta " + nombre(ruta) + " eliminada.", null);
  }

  // ---------------- Bloques y acciones ----------------

  /**
   * Rutas a un cluster (conservan tipo, frecuencia y grupo) o a Datos (pierden cluster, tipo,
   * frecuencia y grupo). En Datos dos rutas no pueden quedar con el mismo número sin tipo: si
   * pasaría, no se mueve ninguna.
   */
  public ApiResponse<Cambio> mover(DiccionarioPeticiones.Mover req) {
    List<Long> ids = distintos(req.rutaIds());
    Long actor = ActorActual.id();
    Long clusterId = req.clusterId();
    Movimiento movimiento =
        transacciones.ejecutar(
            () -> {
              long archivoId = abrir(actor, ids, clusterId);
              String destino = "Datos";
              if (clusterId != null) {
                destino =
                    repo.clusters().stream()
                        .filter(c -> c.id().equals(clusterId))
                        .map(Cluster::nombre)
                        .findFirst()
                        .orElseThrow(() -> new ConflictoException("Ese cluster ya no existe: recarga el diccionario."));
              } else {
                List<String> repetidas = repo.repetidasConTipo(ids, null);
                if (!repetidas.isEmpty()) {
                  throw new ConflictoException(
                      "No se movió a Datos: quedarían repetidas sin tipo las rutas " + lista(repetidas) + ".");
                }
              }
              if (repo.mover(ids, clusterId, actor) != ids.size()) {
                throw new ConflictoException("Alguna ruta ya no existe: recarga el diccionario.");
              }
              escenario.sincronizar(archivoId, ids, actor);
              return new Movimiento(destino, new Cambio(repo.rutasPorIds(ids), 0));
            });
    String mensaje =
        cantidad(ids.size())
            + (ids.size() == 1 ? " movida a " : " movidas a ")
            + movimiento.destino()
            + (clusterId == null ? ": sin cluster, tipo, frecuencia ni grupo." : ".");
    return ApiResponse.ok(mensaje, movimiento.cambio());
  }

  /** Bloque al que fueron las rutas y cómo quedaron. */
  private record Movimiento(String destino, Cambio cambio) {}

  /** Frecuencia para las elegidas que están en un cluster; las de Datos se dejan como están. */
  public ApiResponse<Cambio> asignarFrecuencia(DiccionarioPeticiones.Frecuencia req) {
    List<Long> ids = distintos(req.rutaIds());
    Long actor = ActorActual.id();
    Cambio cambio =
        transacciones.ejecutar(
            () -> {
              exigirExistentes(ids);
              long archivoId = abrir(actor, ids, null);
              List<Long> con = repo.asignarFrecuencia(ids, req.frecuencia(), actor);
              if (con.isEmpty()) {
                throw new SolicitudInvalidaException(
                    "Esas rutas están en Datos: arrástralas a un cluster para asignarles frecuencia.");
              }
              escenario.sincronizar(archivoId, con, actor);
              return new Cambio(repo.rutasPorIds(con), ids.size() - con.size());
            });
    int omitidas = cambio.omitidas();
    String mensaje =
        "Frecuencia "
            + textoFrecuencia(req.frecuencia())
            + " asignada a "
            + cantidad(cambio.rutas().size())
            + "."
            + (omitidas > 0 ? " " + cantidad(omitidas) + " en Datos " + (omitidas == 1 ? "se dejó" : "se dejaron") + " sin frecuencia." : "");
    return ApiResponse.ok(mensaje, cambio);
  }

  /**
   * Grupo para las elegidas que están en un cluster; las de Datos se dejan como están. Se guarda
   * limpio («  a » → «A»), así el mismo grupo no queda escrito de dos formas.
   */
  public ApiResponse<Cambio> asignarGrupo(DiccionarioPeticiones.Grupo req) {
    List<Long> ids = distintos(req.rutaIds());
    String grupo = texto(req.grupo());
    if (grupo.isEmpty()) {
      throw new SolicitudInvalidaException("Escribe el grupo.");
    }
    Long actor = ActorActual.id();
    Cambio cambio =
        transacciones.ejecutar(
            () -> {
              exigirExistentes(ids);
              long archivoId = abrir(actor, ids, null);
              List<Long> con = repo.asignarGrupo(ids, grupo, actor);
              if (con.isEmpty()) {
                throw new SolicitudInvalidaException(
                    "Esas rutas están en Datos: arrástralas a un cluster para asignarles grupo.");
              }
              escenario.sincronizar(archivoId, con, actor);
              return new Cambio(repo.rutasPorIds(con), ids.size() - con.size());
            });
    int omitidas = cambio.omitidas();
    String mensaje =
        "Grupo "
            + grupo
            + " asignado a "
            + cantidad(cambio.rutas().size())
            + "."
            + (omitidas > 0 ? " " + cantidad(omitidas) + " en Datos " + (omitidas == 1 ? "se dejó" : "se dejaron") + " sin grupo." : "");
    return ApiResponse.ok(mensaje, cambio);
  }

  /**
   * Tipo para las elegidas que están en un cluster (las de Datos se dejan sin tipo): todas o
   * ninguna, sin dejar dos rutas con el mismo número y tipo.
   */
  public ApiResponse<Cambio> cambiarTipo(DiccionarioPeticiones.Tipo req) {
    List<Long> ids = distintos(req.rutaIds());
    Long actor = ActorActual.id();
    Cambio cambio =
        transacciones.ejecutar(
            () -> {
              exigirExistentes(ids);
              long archivoId = abrir(actor, ids, null);
              List<Long> con = repo.enCluster(ids);
              if (con.isEmpty()) {
                throw new SolicitudInvalidaException(
                    "Esas rutas están en Datos: arrástralas a un cluster para asignarles tipo.");
              }
              List<String> repetidas = repo.repetidasConTipo(con, req.tipo());
              if (!repetidas.isEmpty()) {
                throw new ConflictoException(
                    "No se cambió el tipo: quedarían repetidas como "
                        + req.tipo()
                        + " las rutas "
                        + lista(repetidas)
                        + ".");
              }
              repo.cambiarTipo(con, req.tipo(), actor);
              escenario.sincronizar(archivoId, con, actor);
              return new Cambio(repo.rutasPorIds(con), ids.size() - con.size());
            });
    int omitidas = cambio.omitidas();
    String mensaje =
        "Tipo "
            + req.tipo()
            + " asignado a "
            + cantidad(cambio.rutas().size())
            + "."
            + (omitidas > 0 ? " " + cantidad(omitidas) + " en Datos " + (omitidas == 1 ? "se dejó" : "se dejaron") + " sin tipo." : "");
    return ApiResponse.ok(mensaje, cambio);
  }

  /** Deshacer el último movimiento: cada ruta vuelve a su cluster, tipo, frecuencia y grupo de antes. */
  public ApiResponse<Cambio> restaurar(DiccionarioPeticiones.Restaurar req) {
    Set<Long> vistos = new HashSet<>();
    for (var u : req.rutas()) {
      if ((u.tipo() != null || u.frecuencia() != null || u.grupo() != null) && u.clusterId() == null) {
        throw new SolicitudInvalidaException("Una ruta en Datos no puede tener tipo, frecuencia ni grupo.");
      }
      if (u.grupo() != null && texto(u.grupo()).isEmpty()) {
        throw new SolicitudInvalidaException("El grupo no puede quedar en blanco.");
      }
      if (!vistos.add(u.id())) {
        throw new SolicitudInvalidaException("La ruta " + u.id() + " viene repetida.");
      }
    }
    List<Long> ids = List.copyOf(vistos);
    Long actor = ActorActual.id();
    List<Ruta> rutas =
        transacciones.ejecutar(
            () -> {
              long archivoId = abrir(actor, ids, null);
              Set<Long> cerrados = escenario.clustersCerrados(archivoId);
              Set<Long> clusters = new HashSet<>();
              repo.clusters().forEach(c -> clusters.add(c.id()));
              for (var u : req.rutas()) {
                if (u.clusterId() != null && !clusters.contains(u.clusterId())) {
                  throw new ConflictoException("Su cluster ya no existe: no se puede deshacer.");
                }
                if (u.clusterId() != null && cerrados.contains(u.clusterId())) {
                  throw new ConflictoException("Su cluster está cerrado en Configuración: no se puede deshacer.");
                }
              }
              if (repo.restaurar(req.rutas(), actor) != req.rutas().size()) {
                throw new ConflictoException("Alguna ruta ya no existe: no se puede deshacer.");
              }
              escenario.sincronizar(archivoId, ids, actor);
              return repo.rutasPorIds(ids);
            });
    return ApiResponse.ok("Movimiento deshecho: " + cantidad(rutas.size()) + " como estaban.", new Cambio(rutas, 0));
  }

  /** Cuántos bloques de cluster hay. Al quitar, las rutas de esos clusters vuelven a Datos. */
  public ApiResponse<Diccionario> cambiarClusters(DiccionarioPeticiones.Clusters req) {
    int cantidad = req.cantidad();
    Long actor = ActorActual.id();
    int[] cambio = new int[2];
    Diccionario diccionario =
        transacciones.ejecutar(
            () -> {
              long archivoId = escenario.asegurar(actor);
              repo.bloquearClusters();
              int actual = repo.ultimoOrden();
              cambio[0] = actual;
              if (cantidad > actual) {
                repo.agregarClusters(actual + 1, cantidad);
              } else if (cantidad < actual) {
                // Un cluster cerrado en Configuración no se quita hasta que se reabra.
                Set<Long> cerrados = escenario.clustersCerrados(archivoId);
                List<String> cerradosQueSeQuitan =
                    repo.clusters().stream()
                        .filter(c -> c.orden() > cantidad && cerrados.contains(c.id()))
                        .map(Cluster::nombre)
                        .toList();
                if (!cerradosQueSeQuitan.isEmpty()) {
                  throw new ConflictoException(
                      "No se quitaron los clusters: "
                          + lista(cerradosQueSeQuitan)
                          + (cerradosQueSeQuitan.size() == 1 ? " está cerrado" : " están cerrados")
                          + " en Configuración.");
                }
                // Sus rutas vuelven a Datos sin tipo: dos con el mismo número no pueden quedar ahí.
                List<Long> vuelven = repo.rutasDeClustersDespuesDe(cantidad);
                List<String> repetidas = vuelven.isEmpty() ? List.of() : repo.repetidasConTipo(vuelven, null);
                if (!repetidas.isEmpty()) {
                  throw new ConflictoException(
                      "No se quitaron los clusters: en Datos quedarían repetidas sin tipo las rutas "
                          + lista(repetidas)
                          + ". Mueve una de cada par a otro cluster.");
                }
                cambio[1] = repo.quitarClustersDespuesDe(cantidad, actor);
                if (!vuelven.isEmpty()) {
                  escenario.sincronizar(archivoId, vuelven, actor);
                }
              }
              return leer();
            });
    String mensaje;
    if (cantidad == cambio[0]) {
      mensaje = "Los clusters ya eran " + cantidad + ".";
    } else if (cantidad > cambio[0]) {
      mensaje = "Ahora hay " + cantidad + (cantidad == 1 ? " cluster." : " clusters.");
    } else {
      mensaje =
          "Ahora hay "
              + cantidad
              + (cantidad == 1 ? " cluster" : " clusters")
              + (cambio[1] > 0 ? "; " + cantidad(cambio[1]) + " volvieron a Datos sin tipo, frecuencia ni grupo." : ".");
    }
    return ApiResponse.ok(mensaje, diccionario);
  }

  // ---------------- Reglas ----------------

  /** Número y códigos ya limpios; los locales, con los códigos repetidos sumados. */
  record DatosRuta(String codigo, List<Local> locales, int sumados) {}

  /**
   * Limpia número y códigos (sin espacios de más y en mayúsculas: « f045 » y «F045» son el mismo)
   * y suma los códigos repetidos, como el Excel: el local queda una vez con {@code filas} > 1.
   */
  static DatosRuta normalizar(DiccionarioPeticiones.Ruta req) {
    String codigo = texto(req.codigoRuta());
    if (codigo.isEmpty()) {
      throw new SolicitudInvalidaException("Escribe el número de ruta.");
    }
    Map<String, Acumulado> porCodigo = new LinkedHashMap<>();
    int linea = 0;
    for (var local : req.locales()) {
      linea++;
      String cod = texto(local.cod());
      if (cod.isEmpty()) {
        throw new SolicitudInvalidaException("La línea " + linea + " no tiene código.");
      }
      porCodigo.computeIfAbsent(cod, Acumulado::new).sumar(local);
    }
    List<Local> locales = new ArrayList<>(porCodigo.size());
    for (Acumulado a : porCodigo.values()) {
      if (a.bultos.compareTo(MAXIMO) > 0 || a.unidades.compareTo(MAXIMO) > 0 || a.lineas.compareTo(MAXIMO) > 0) {
        throw new SolicitudInvalidaException(
            "El código " + a.cod + " suma más de 9,999,999,999.99: revisa sus líneas.");
      }
      locales.add(new Local(a.cod, a.filas, a.bultos, a.unidades, a.lineas));
    }
    return new DatosRuta(codigo, List.copyOf(locales), req.locales().size() - locales.size());
  }

  static String texto(String valor) {
    return valor == null ? "" : valor.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
  }

  private static final class Acumulado {
    private final String cod;
    private int filas;
    private BigDecimal bultos = BigDecimal.ZERO.setScale(2);
    private BigDecimal unidades = BigDecimal.ZERO.setScale(2);
    private BigDecimal lineas = BigDecimal.ZERO.setScale(2);

    Acumulado(String cod) {
      this.cod = cod;
    }

    void sumar(DiccionarioPeticiones.Local local) {
      filas++;
      bultos = bultos.add(local.bultos());
      unidades = unidades.add(local.unidades());
      lineas = lineas.add(local.lineas());
    }
  }

  private void exigirUnica(String codigo, String tipo, Long salvoId) {
    if (repo.existe(codigo, tipo, salvoId)) {
      throw new ConflictoException("La ruta " + nombre(codigo, tipo) + " ya existe.");
    }
  }

  /** Si otra persona creó la misma ruta en el mismo instante, el índice único lo frena aquí. */
  private static <T> T sinRepetir(String codigo, java.util.function.Supplier<T> trabajo) {
    try {
      return trabajo.get();
    } catch (DuplicateKeyException e) {
      throw new ConflictoException("Ya existe otra ruta " + codigo + " con el mismo tipo.");
    }
  }

  private void exigirExistentes(List<Long> ids) {
    if (repo.contar(ids) != ids.size()) {
      throw new ConflictoException("Alguna ruta ya no existe: recarga el diccionario.");
    }
  }

  private Ruta rutaExistente(Long id) {
    List<Ruta> rutas = repo.rutasPorIds(List.of(id));
    if (rutas.isEmpty()) {
      throw new RecursoNoEncontradoException("La ruta ya no existe: recarga el diccionario.");
    }
    return rutas.getFirst();
  }

  private Diccionario leer() {
    // Los cerrados en Configuración van marcados: el Registro no los muestra hasta que se reabran.
    Set<Long> cerrados = escenario.clustersCerrados(escenario.buscar());
    List<Cluster> clusters =
        repo.clusters().stream()
            .map((c) -> new Cluster(c.id(), c.orden(), c.nombre(), cerrados.contains(c.id())))
            .toList();
    List<Ruta> rutas = repo.rutas(MAX_RUTAS + 1);
    boolean truncado = rutas.size() > MAX_RUTAS;
    return new Diccionario(clusters, truncado ? List.copyOf(rutas.subList(0, MAX_RUTAS)) : rutas, truncado);
  }

  private static List<Long> distintos(List<Long> ids) {
    return List.copyOf(new LinkedHashSet<>(ids.stream().filter(Objects::nonNull).toList()));
  }

  private static String nombre(Ruta ruta) {
    return nombre(ruta.codigoRuta(), ruta.tipo());
  }

  /** «2002 B2C», o «2002 (sin tipo)» mientras no se le asigne. */
  static String nombre(String codigo, String tipo) {
    return codigo + (tipo == null ? " (sin tipo)" : " " + tipo);
  }

  private static String avisoSumados(DatosRuta datos) {
    return datos.sumados() > 0 ? " Se sumaron los códigos repetidos." : "";
  }

  private static String cantidad(int n) {
    return n == 1 ? "1 ruta" : n + " rutas";
  }

  static String textoFrecuencia(String frecuencia) {
    return switch (frecuencia) {
      case "LXV" -> "L-X-V";
      case "MJS" -> "M-J-S";
      default -> "Diaria";
    };
  }

  /** «2002, 2015 y 3 más». */
  private static String lista(List<String> valores) {
    int mostrar = Math.min(valores.size(), 5);
    String primeras = String.join(", ", valores.subList(0, mostrar));
    return valores.size() > mostrar ? primeras + " y " + (valores.size() - mostrar) + " más" : primeras;
  }
}
