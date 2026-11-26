package oracle.simulacion.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
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
import oracle.simulacion.repository.RutasDataRepository.LocalGuardado;
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

  /**
   * Cambia el número y los locales de una ruta (tipo, frecuencia y grupo se conservan): en DATA o en
   * un cluster que no esté cerrado; ese cluster vuelve al paso de operarios si ya los tenía.
   */
  public ApiResponse<CambioDataResponse> actualizar(Long archivoId, Long rutaId, DataRequest.Ruta req) {
    RutaNormalizada datos = RutaNormalizada.de(req);
    Long actor = ActorActual.id();
    String mensaje =
        escribir(
            "actualizar id=" + archivoId + " ruta=" + rutaId,
            () -> {
              Contexto ctx = bloquear(archivoId);
              RutaData ruta = existente(ctx, rutaId);
              // Control manual, como mover locales: en DATA y en cualquier cluster que no esté cerrado.
              if (!ruta.enData() && cerradoEnAlguno(ctx, ruta.clusterId())) {
                throw new ConflictoException(
                    ctx.nombreCluster(ruta.clusterId()) + " está cerrado: reábrelo para editar la ruta "
                        + ruta.codigo() + ".");
              }
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
              // Cambió su carga: su cluster vuelve al paso de operarios en los días donde aparece.
              List<String> vuelven = invalidarOperarios(ctx, List.of(ruta), Set.of());
              alCambiarRutas(ctx, actor);
              return "Ruta " + nombre(datos.codigo(), ruta.tipo()) + " guardada." + avisoSumados(datos)
                  + pasoOperarios(vuelven);
            });
    return listo(mensaje);
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

  /**
   * Varias rutas de DATA a la vez (las elegidas en la barra): todas o ninguna. Si alguna está en un
   * cluster no se borra ninguna (primero vuelve a DATA); las que otra persona ya borró se cuentan.
   */
  public ApiResponse<CambioDataResponse> eliminarVarias(Long archivoId, DataRequest.Eliminar req) {
    List<Long> ids = distintos(req.rutaIds());
    Long actor = ActorActual.id();
    int[] hechas =
        escribir(
            "eliminar varias id=" + archivoId + " rutas=" + ids.size(),
            () -> {
              Contexto ctx = bloquear(archivoId);
              List<RutaData> rutas = repo.rutas(archivoId, ids);
              List<String> enCluster = rutas.stream().filter((r) -> !r.enData()).map((r) -> nombre(r.codigo(), r.tipo())).toList();
              if (!enCluster.isEmpty()) {
                throw new ConflictoException(
                    "Solo se eliminan rutas de DATA: " + lista(enCluster) + (enCluster.size() == 1 ? " está" : " están")
                        + " en un cluster. Devuélvelas a DATA o quítalas de la selección. No se eliminó ninguna.");
              }
              int n = rutas.isEmpty() ? 0 : repo.eliminarVarias(archivoId, rutas.stream().map(RutaData::id).toList());
              configuracion.tocar(ctx.archivo(), actor);
              return new int[] {n, ids.size() - n};
            });
    String faltaban = hechas[1] == 0 ? "" : " " + cantidad(hechas[1]) + (hechas[1] == 1 ? " ya no existía." : " ya no existían.");
    return listo((hechas[0] == 1 ? "1 ruta eliminada" : hechas[0] + " rutas eliminadas") + " de DATA." + faltaban);
  }

  // ---------------- Tipo, frecuencia y grupo (DATA y clusters no cerrados) ----------------

  /**
   * Tipo para las rutas elegidas (DATA o clusters no cerrados): todas o ninguna, sin dejar dos con
   * el mismo número y tipo. Es solo una etiqueta: no cambia el balanceo.
   */
  public ApiResponse<CambioDataResponse> asignarTipo(Long archivoId, DataRequest.Tipo req) {
    List<Long> ids = distintos(req.rutaIds());
    Long actor = ActorActual.id();
    int hechas =
        escribir(
            "tipo id=" + archivoId + " rutas=" + ids.size(),
            () -> {
              Contexto ctx = bloquear(archivoId);
              List<Long> elegidas = idsDe(asignables(ctx, ids, "el tipo"));
              List<String> repetidas = repo.repetidasConTipo(archivoId, elegidas, req.tipo());
              if (!repetidas.isEmpty()) {
                throw new ConflictoException(
                    "No se cambió el tipo: quedarían repetidas como " + req.tipo() + " las rutas " + lista(repetidas) + ".");
              }
              int n = repo.asignarTipo(archivoId, elegidas, req.tipo());
              configuracion.tocar(ctx.archivo(), actor);
              return n;
            });
    return listo("Tipo " + req.tipo() + " asignado a " + cantidad(hechas) + ".");
  }

  /**
   * Frecuencia para las rutas elegidas (DATA o clusters no cerrados). Cambia en qué días aparece cada
   * una: en los días de antes y en los nuevos, su cluster vuelve al paso de operarios.
   */
  public ApiResponse<CambioDataResponse> asignarFrecuencia(Long archivoId, DataRequest.Frecuencia req) {
    List<Long> ids = distintos(req.rutaIds());
    Long actor = ActorActual.id();
    String mensaje =
        escribir(
            "frecuencia id=" + archivoId + " rutas=" + ids.size(),
            () -> {
              Contexto ctx = bloquear(archivoId);
              List<RutaData> rutas = asignables(ctx, ids, "la frecuencia");
              String balanceo = alCambiarBalanceo(ctx, rutas, diasDe(req.frecuencia()));
              int n = repo.asignarFrecuencia(archivoId, idsDe(rutas), req.frecuencia());
              alCambiarRutas(ctx, actor);
              return "Frecuencia " + NombresSimulacion.textoFrecuencia(req.frecuencia()) + " asignada a "
                  + cantidad(n) + "." + balanceo;
            });
    return listo(mensaje);
  }

  /**
   * Grupo para las rutas elegidas (DATA o clusters no cerrados). Se guarda limpio («  a » → «A»). El
   * grupo decide el tramo de chutes: su cluster vuelve al paso de operarios.
   */
  public ApiResponse<CambioDataResponse> asignarGrupo(Long archivoId, DataRequest.Grupo req) {
    List<Long> ids = distintos(req.rutaIds());
    String grupo = RutaNormalizada.texto(req.grupo());
    if (grupo.isEmpty()) {
      throw new SolicitudInvalidaException("Escribe el grupo.");
    }
    Long actor = ActorActual.id();
    String mensaje =
        escribir(
            "grupo id=" + archivoId + " rutas=" + ids.size(),
            () -> {
              Contexto ctx = bloquear(archivoId);
              List<RutaData> rutas = asignables(ctx, ids, "el grupo");
              String balanceo = alCambiarBalanceo(ctx, rutas, Set.of());
              int n = repo.asignarGrupo(archivoId, idsDe(rutas), grupo);
              alCambiarRutas(ctx, actor);
              return "Grupo " + grupo + " asignado a " + cantidad(n) + "." + balanceo;
            });
    return listo(mensaje);
  }

  /**
   * Grupo o frecuencia de rutas de un cluster: en sus días (y en {@code diasNuevos}) el cluster vuelve
   * al paso de operarios. Las rutas se quedan en su chute: si el cluster ya se balanceó, lo dice para
   * que se restablezca y se vuelva a balancear si se quiere un reparto limpio.
   */
  private String alCambiarBalanceo(Contexto ctx, List<RutaData> rutas, Set<Balanceo> diasNuevos) {
    List<String> vuelven = invalidarOperarios(ctx, rutas, diasNuevos);
    List<String> balanceados = new ArrayList<>();
    for (RutaData ruta : rutas) {
      if (!ruta.enData() && SimulacionConfiguracionService.empezadoEnAlguno(ctx, ruta.clusterId())) {
        String nombre = ctx.nombreCluster(ruta.clusterId());
        if (!balanceados.contains(nombre)) {
          balanceados.add(nombre);
        }
      }
    }
    String reparto =
        balanceados.isEmpty()
            ? ""
            : " Se quedan en su chute; para repartirlas de nuevo, restablece y vuelve a balancear "
                + String.join(" y ", balanceados) + ".";
    return pasoOperarios(vuelven) + reparto;
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
              // La columna: un chute en un cluster o un bloque en DATA (se guarda igual, como su chute).
              Long chute = req.chuteId();
              if (chute == null) {
                throw new SolicitudInvalidaException("Elige la columna donde van las rutas.");
              }
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

  /**
   * A un bloque de DATA (control manual absoluto: la ruta queda donde se suelta). Desde un cluster
   * pierde el cluster y conserva tipo, frecuencia y grupo; dentro de DATA solo cambia de bloque. DATA
   * no se balancea: cualquier bloque recibe rutas.
   */
  private String aData(Contexto ctx, List<RutaData> rutas, long chute) {
    Long archivoId = ctx.archivo().getId();
    List<RutaData> cambian = rutas.stream().filter((r) -> !r.enData() || r.chuteId() != chute).toList();
    if (cambian.isEmpty()) {
      throw new ConflictoException("Las rutas ya están en ese bloque de DATA.");
    }
    // Un cluster que ya empezó a balancearse (o está cerrado) no devuelve rutas a DATA.
    Set<Long> origenes = new LinkedHashSet<>();
    cambian.stream().filter((r) -> !r.enData()).forEach((r) -> origenes.add(r.clusterId()));
    for (Long origen : origenes) {
      exigirSinEmpezar(ctx, origen, "devolver sus rutas a DATA");
    }
    // Tampoco una que llegó a mano desde un cluster que ya empezó: su «antes» está en ese cluster.
    for (RutaData ruta : cambian) {
      exigirOrigenSinEmpezar(ctx, ruta, "pasarla a DATA");
    }
    repo.aData(archivoId, cambian.stream().map(RutaData::id).toList(), chute);
    // Solo las que salieron de un cluster tenían historial manual.
    List<Long> deClusters = cambian.stream().filter((r) -> !r.enData()).map(RutaData::id).toList();
    if (!deClusters.isEmpty()) {
      repo.borrarHistorialDeRutas(archivoId, deClusters);
    }
    return cantidad(cambian.size()) + " → DATA · bloque " + bloque(chute) + ".";
  }

  private String aCluster(Contexto ctx, List<RutaData> rutas, long destino, Long chute) {
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
    exigirNoBloqueadoSiCambia(ctx, rutas, chute);
    // Un cluster vacío entra a la secuencia del balanceo: no puede quedar antes de uno que ya empezó.
    configuracion.exigirOrdenAlLlenar(ctx, destino);
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

  // ---------------- Locales ----------------

  /**
   * Locales de una ruta a otra (se arrastran desde la ficha): todos o ninguno. Como mover una ruta a
   * mano, vale en DATA y en cualquier cluster que no esté cerrado (antes, durante o después de
   * balancear); un cluster que ya tenía operarios vuelve a ese paso porque cambió su carga. La origen no
   * se queda sin locales; el código que la destino ya tiene se suma ahí (como en el formulario). Los
   * totales de las dos vuelven a ser la suma de sus locales.
   */
  public ApiResponse<CambioDataResponse> moverLocales(Long archivoId, DataRequest.MoverLocales req) {
    List<Integer> ordenes = List.copyOf(new LinkedHashSet<>(req.ordenes()));
    Map<Integer, String> vistos = codigosVistos(req);
    Long actor = ActorActual.id();
    String mensaje =
        escribir(
            "locales id=" + archivoId + " origen=" + req.origenId() + " destino=" + req.destinoId()
                + " locales=" + ordenes.size(),
            () -> {
              if (req.origenId().equals(req.destinoId())) {
                throw new SolicitudInvalidaException("Suelta los locales sobre otra ruta.");
              }
              Contexto ctx = bloquear(archivoId);
              List<RutaData> rutas = repo.rutas(archivoId, List.of(req.origenId(), req.destinoId()));
              RutaData origen = rutas.stream().filter((r) -> r.id() == req.origenId()).findFirst().orElse(null);
              RutaData destino = rutas.stream().filter((r) -> r.id() == req.destinoId()).findFirst().orElse(null);
              if (origen == null || destino == null) {
                throw new ConflictoException("Alguna ruta ya no existe: pulsa «Actualizar».");
              }
              exigirAbierto(ctx, origen);
              exigirAbierto(ctx, destino);

              Map<Integer, LocalGuardado> deOrigen = new LinkedHashMap<>();
              repo.locales(origen.id()).forEach((l) -> deOrigen.put(l.orden(), l));
              List<LocalGuardado> elegidos = new ArrayList<>(ordenes.size());
              for (Integer orden : ordenes) {
                LocalGuardado local = deOrigen.get(orden);
                if (local == null) {
                  throw new ConflictoException(
                      "Algún local ya no está en la ruta " + origen.codigo() + ": pulsa «Actualizar».");
                }
                // El número puede ser ya de otro local (otra persona cambió los de esta ruta).
                if (vistos != null && !Objects.equals(local.cod(), vistos.get(orden))) {
                  throw new ConflictoException(
                      "Los locales de la ruta " + origen.codigo()
                          + " cambiaron mientras tenías abierta su ficha: vuelve a abrirla y elígelos otra vez.");
                }
                elegidos.add(local);
              }
              if (elegidos.size() == deOrigen.size()) {
                throw new ConflictoException(
                    "La ruta " + origen.codigo() + " se quedaría sin locales: deja al menos uno o elimínala.");
              }
              List<LocalGuardado> enDestino = repo.locales(destino.id());
              if (enDestino.isEmpty()) {
                throw new ConflictoException(
                    "La ruta " + destino.codigo() + " no tiene el detalle de sus locales: no puede recibir otros.");
              }

              // El mismo código (como lo escribe el formulario) se suma; los demás pasan al final.
              Map<String, LocalGuardado> porCodigo = new HashMap<>();
              for (LocalGuardado l : enDestino) {
                if (l.cod() != null) {
                  porCodigo.putIfAbsent(RutaNormalizada.texto(l.cod()), l);
                }
              }
              Map<Integer, LocalGuardado> sumados = new LinkedHashMap<>();
              List<Integer> fusionados = new ArrayList<>();
              List<int[]> pasan = new ArrayList<>();
              int siguiente = enDestino.getLast().orden() + 1;
              for (LocalGuardado local : elegidos) {
                LocalGuardado igual = local.cod() == null ? null : porCodigo.get(RutaNormalizada.texto(local.cod()));
                if (igual == null) {
                  pasan.add(new int[] {local.orden(), siguiente++});
                  continue;
                }
                LocalGuardado base = sumados.getOrDefault(igual.orden(), igual);
                LocalGuardado suma =
                    new LocalGuardado(
                        base.orden(), base.cod(), base.filas() + local.filas(), base.bultos().add(local.bultos()),
                        base.unidades().add(local.unidades()), base.lineas().add(local.lineas()));
                if (excede(suma.bultos(), suma.unidades(), suma.lineas())) {
                  throw new ConflictoException(
                      "El código " + base.cod() + " sumaría más de 9,999,999,999.99 en la ruta " + destino.codigo() + ".");
                }
                sumados.put(base.orden(), suma);
                fusionados.add(local.orden());
              }
              if (enDestino.size() + pasan.size() > DataRequest.MAX_LOCALES) {
                throw new ConflictoException(
                    "La ruta " + destino.codigo() + " pasaría de " + DataRequest.MAX_LOCALES + " locales.");
              }
              List<LocalGuardado> todos = new ArrayList<>(enDestino);
              todos.addAll(elegidos);
              if (excede(
                  suma(todos, LocalGuardado::bultos), suma(todos, LocalGuardado::unidades), suma(todos, LocalGuardado::lineas))) {
                throw new ConflictoException("La ruta " + destino.codigo() + " sumaría más de 9,999,999,999.99.");
              }

              if (!sumados.isEmpty()) {
                repo.fijarLocales(destino.id(), List.copyOf(sumados.values()));
                repo.quitarLocales(origen.id(), fusionados);
              }
              if (!pasan.isEmpty()) {
                repo.pasarLocales(origen.id(), destino.id(), pasan);
              }
              repo.recalcularTotales(archivoId, List.of(origen.id(), destino.id()));
              // Cambió la carga de las dos: el cluster que ya tenía operarios vuelve a ese paso en los
              // días donde aparece la ruta (como al mover una ruta a mano).
              List<String> vuelven = invalidarOperarios(ctx, List.of(origen, destino), Set.of());
              alCambiarRutas(ctx, actor);
              String cuantos = elegidos.size() == 1 ? "1 local" : elegidos.size() + " locales";
              String juntos =
                  fusionados.isEmpty()
                      ? ""
                      : fusionados.size() == 1
                          ? " 1 se sumó a su mismo código."
                          : " " + fusionados.size() + " se sumaron a su mismo código.";
              return cuantos + " de " + origen.codigo() + " → " + destino.codigo() + "." + juntos + pasoOperarios(vuelven);
            });
    return listo(mensaje);
  }

  /**
   * Código que el usuario vio en cada local elegido (por su número), o null si no se enviaron.
   * Deben venir uno por número y un mismo número no puede traer dos códigos.
   */
  private static Map<Integer, String> codigosVistos(DataRequest.MoverLocales req) {
    if (req.cods() == null) {
      return null;
    }
    if (req.cods().size() != req.ordenes().size()) {
      throw new SolicitudInvalidaException("Falta el código de algún local: vuelve a abrir la ficha.");
    }
    Map<Integer, String> vistos = new HashMap<>();
    for (int i = 0; i < req.ordenes().size(); i++) {
      Integer orden = req.ordenes().get(i);
      String cod = req.cods().get(i);
      if (vistos.containsKey(orden) && !Objects.equals(vistos.get(orden), cod)) {
        throw new SolicitudInvalidaException("Un mismo local llegó con dos códigos: vuelve a abrir la ficha.");
      }
      vistos.put(orden, cod);
    }
    return vistos;
  }

  /** Los locales se mueven a mano como las rutas: en DATA o en un cluster que no esté cerrado. */
  private static void exigirAbierto(Contexto ctx, RutaData ruta) {
    if (!ruta.enData() && cerradoEnAlguno(ctx, ruta.clusterId())) {
      throw new ConflictoException(
          ctx.nombreCluster(ruta.clusterId()) + " está cerrado: reábrelo para mover los locales de la ruta "
              + ruta.codigo() + ".");
    }
  }

  private static boolean excede(BigDecimal bultos, BigDecimal unidades, BigDecimal lineas) {
    return bultos.compareTo(RutaNormalizada.MAXIMO) > 0
        || unidades.compareTo(RutaNormalizada.MAXIMO) > 0
        || lineas.compareTo(RutaNormalizada.MAXIMO) > 0;
  }

  private static BigDecimal suma(List<LocalGuardado> locales, Function<LocalGuardado, BigDecimal> campo) {
    return locales.stream().map(campo).reduce(BigDecimal.ZERO, BigDecimal::add);
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
              List<Long> todos = SimulacionConfiguracionService.todosLosClusters(ctx);
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
                List<Long> vuelven = quitar(ctx, todos.subList(cantidad, actual));
                hecho = "Ahora hay " + cantidad + (cantidad == 1 ? " cluster" : " clusters") + volvieron(vuelven.size()) + ".";
              } else {
                hecho = "Los clusters ya eran " + cantidad + ".";
              }
              alCambiarRutas(ctx, actor);
              return hecho;
            });
    return listo(mensaje);
  }

  /**
   * Agrega «CL{numero}» con el número que elige el usuario: puede llenar un hueco (CL2 entre CL1 y
   * CL4). El balanceo ordena por nombre, así que queda en su lugar. Si va antes de uno que ya empezó,
   * se crea igual, pero no recibe rutas hasta restablecer ese (lo avisa el mensaje).
   */
  public ApiResponse<CambioDataResponse> agregarCluster(Long archivoId, DataRequest.NuevoCluster req) {
    int numero = req.numero();
    String nombre = "CL" + numero;
    Long actor = ActorActual.id();
    String mensaje =
        escribir(
            "agregarCluster id=" + archivoId + " " + nombre,
            () -> {
              Contexto ctx = bloquear(archivoId);
              List<Long> todos = SimulacionConfiguracionService.todosLosClusters(ctx);
              Long repetido = todos.stream().filter((id) -> numero(ctx.nombreCluster(id)) == numero).findFirst().orElse(null);
              if (repetido != null) {
                throw new ConflictoException(ctx.nombreCluster(repetido) + " ya existe en este escenario.");
              }
              if (todos.size() >= DataRequest.MAX_CLUSTERS) {
                throw new ConflictoException(
                    "Ya hay " + DataRequest.MAX_CLUSTERS + " clusters, el máximo: quita uno antes de agregar otro.");
              }
              String bloqueadoPor =
                  todos.stream()
                      .filter((id) -> SimulacionConfiguracionService.ORDEN_NATURAL.compare(nombre, ctx.nombreCluster(id)) < 0)
                      .filter((id) -> ctx.conRutas().contains(id) && SimulacionConfiguracionService.empezadoEnAlguno(ctx, id))
                      .findFirst()
                      .map(ctx::nombreCluster)
                      .orElse(null);
              lotes.asegurarParametrosCluster(archivoId, lotes.asegurarClusters(List.of(nombre)).values());
              alCambiarRutas(ctx, actor);
              int total = todos.size() + 1;
              return nombre + " agregado. Ahora hay " + total + (total == 1 ? " cluster." : " clusters.")
                  + (bloqueadoPor == null
                      ? ""
                      : " Va antes de " + bloqueadoPor + ", que ya empezó: recibirá rutas cuando restablezcas "
                          + bloqueadoPor + ".");
            });
    return listo(mensaje);
  }

  /** Quita el cluster elegido (no solo el último) si no empezó; sus rutas vuelven a DATA. */
  public ApiResponse<CambioDataResponse> quitarCluster(Long archivoId, Long clusterId) {
    Long actor = ActorActual.id();
    String mensaje =
        escribir(
            "quitarCluster id=" + archivoId + " cluster=" + clusterId,
            () -> {
              Contexto ctx = bloquear(archivoId);
              List<Long> todos = SimulacionConfiguracionService.todosLosClusters(ctx);
              if (!todos.contains(clusterId)) {
                throw new RecursoNoEncontradoException("Ese cluster ya no está en el escenario. Actualiza la página.");
              }
              String nombre = ctx.nombreCluster(clusterId);
              List<Long> vuelven = quitar(ctx, List.of(clusterId));
              alCambiarRutas(ctx, actor);
              int total = todos.size() - 1;
              return nombre + " quitado" + volvieron(vuelven.size()) + ". Ahora hay " + total
                  + (total == 1 ? " cluster." : " clusters.");
            });
    return listo(mensaje);
  }

  /**
   * Quita esos clusters del escenario (ninguno puede haber empezado); sus rutas vuelven a DATA en su
   * chute. Devuelve las rutas que volvieron.
   */
  private List<Long> quitar(Contexto ctx, List<Long> quitar) {
    Long archivoId = ctx.archivo().getId();
    for (Long id : quitar) {
      exigirSinEmpezar(ctx, id, "quitarlo");
    }
    // Rutas movidas a mano entre un cluster que se quita y otro: las que llegaron desde uno
    // que ya empezó no se pierden; las que salieron hacia otro toman ese como su original
    // (si no, al restablecer volverían a un cluster que ya no existe).
    List<Long> fijar = new ArrayList<>();
    for (RutaData ruta : repo.rutasCruzadas(archivoId, quitar)) {
      boolean sale = quitar.contains(ruta.clusterId());
      boolean venia = quitar.contains(ruta.clusterOriginalId());
      if (sale && !venia) {
        exigirOrigenSinEmpezar(ctx, ruta, "quitar " + ctx.nombreCluster(ruta.clusterId()));
      } else if (venia && !sale) {
        if (SimulacionConfiguracionService.empezadoEnAlguno(ctx, ruta.clusterId())) {
          throw new ConflictoException(
              "La ruta " + ruta.codigo() + " salió de " + ctx.nombreCluster(ruta.clusterOriginalId())
                  + " a " + ctx.nombreCluster(ruta.clusterId()) + ", que ya empezó a balancearse: devuélvela a "
                  + ctx.nombreCluster(ruta.clusterOriginalId()) + " o restablece "
                  + ctx.nombreCluster(ruta.clusterId()) + " antes de quitar el cluster.");
        }
        fijar.add(ruta.id());
      }
    }
    repo.fijarOrigenEnSuCluster(archivoId, fijar);
    repo.borrarHistorialDeRutas(archivoId, fijar);
    List<Long> vuelven = repo.devolverADataEnSuChute(archivoId, quitar);
    repo.borrarHistorialDeRutas(archivoId, vuelven);
    repo.quitarClusters(archivoId, quitar);
    return vuelven;
  }

  /** «; 1 ruta volvió a DATA», «; 3 rutas volvieron a DATA» o nada. */
  private static String volvieron(int n) {
    return n == 0 ? "" : "; " + cantidad(n) + (n == 1 ? " volvió" : " volvieron") + " a DATA";
  }

  // ---------------- Reglas ----------------

  /** Bloquea el escenario (como toda escritura de Configuración) y arma su contexto. */
  private Contexto bloquear(Long archivoId) {
    return configuracion.contexto(archivoId, Balanceo.LXV, true);
  }

  /** La ruta existe y está en DATA; si no, se dice dónde está. */
  private RutaData existente(Contexto ctx, Long rutaId) {
    List<RutaData> rutas = repo.rutas(ctx.archivo().getId(), List.of(rutaId));
    if (rutas.isEmpty()) {
      throw new RecursoNoEncontradoException("La ruta ya no existe: pulsa «Actualizar».");
    }
    return rutas.getFirst();
  }

  private RutaData enData(Contexto ctx, Long rutaId, String accion) {
    RutaData ruta = existente(ctx, rutaId);
    if (!ruta.enData()) {
      throw new ConflictoException(
          "La ruta " + ruta.codigo() + " está en " + ctx.nombreCluster(ruta.clusterId())
              + ": devuélvela a DATA para " + accion + ".");
    }
    return ruta;
  }

  /**
   * Las elegidas reciben tipo, frecuencia o grupo si están en DATA o en un cluster que todavía no
   * empezó a balancearse (ahí no cambia ningún balanceo). Todas o ninguna: si alguna está en un
   * cluster que ya empezó o está cerrado, no se cambia ninguna.
   */
  private List<RutaData> asignables(Contexto ctx, List<Long> ids, String que) {
    List<RutaData> rutas = repo.rutas(ctx.archivo().getId(), ids);
    if (rutas.size() != ids.size()) {
      throw new ConflictoException("Alguna ruta ya no existe: pulsa «Actualizar».");
    }
    // Control manual: DATA y cualquier cluster que no esté cerrado (antes, durante o después de balancear).
    Set<Long> clusters = new LinkedHashSet<>();
    rutas.stream().filter((r) -> !r.enData()).forEach((r) -> clusters.add(r.clusterId()));
    for (Long cluster : clusters) {
      if (cerradoEnAlguno(ctx, cluster)) {
        throw new ConflictoException(
            ctx.nombreCluster(cluster) + " está cerrado: reábrelo para cambiar " + que + " de sus rutas.");
      }
    }
    return rutas;
  }

  private static List<Long> idsDe(List<RutaData> rutas) {
    return rutas.stream().map(RutaData::id).toList();
  }

  /** Días (balanceos) donde aparece una ruta de esa frecuencia. */
  private static Set<Balanceo> diasDe(String frecuencia) {
    Set<Balanceo> dias = EnumSet.noneOf(Balanceo.class);
    Arrays.stream(Balanceo.values()).filter((b) -> b.incluye(frecuencia)).forEach(dias::add);
    return dias;
  }

  /**
   * Cambió la carga de esas rutas (sus locales, su grupo o su frecuencia): en los días donde aparecen
   * (y en {@code otrosDias}) su cluster vuelve al paso de operarios, como al mover una ruta a mano.
   * Devuelve los clusters que ya tenían operarios.
   */
  private List<String> invalidarOperarios(Contexto ctx, List<RutaData> rutas, Set<Balanceo> otrosDias) {
    List<String> vuelven = new ArrayList<>();
    for (RutaData ruta : rutas) {
      if (ruta.enData()) {
        continue;
      }
      Set<Balanceo> dias = diasDe(ruta.frecuencia());
      dias.addAll(otrosDias);
      boolean conOperarios =
          dias.stream()
              .filter(Balanceo::esDia)
              .map((b) -> ctx.parametro(b, ruta.clusterId()))
              .anyMatch((p) -> p != null && ParametroCluster.ESTADO_LISTO.equals(p.getEstado()));
      configuracion.invalidarOperariosEn(ctx, dias, List.of(ruta.clusterId()));
      String nombre = ctx.nombreCluster(ruta.clusterId());
      if (conOperarios && !vuelven.contains(nombre)) {
        vuelven.add(nombre);
      }
    }
    return vuelven;
  }

  /** « CL1 vuelve al paso de operarios.» o nada. */
  private static String pasoOperarios(List<String> vuelven) {
    return vuelven.isEmpty()
        ? ""
        : " " + String.join(" y ", vuelven) + (vuelven.size() == 1 ? " vuelve" : " vuelven") + " al paso de operarios.";
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

  private static boolean cerradoEnAlguno(Contexto ctx, Long clusterId) {
    return Arrays.stream(Balanceo.values())
        .map((b) -> ctx.parametro(b, clusterId))
        .anyMatch((p) -> p != null && ParametroCluster.ESTADO_CERRADO.equals(p.getEstado()));
  }

  private static void exigirSinEmpezar(Contexto ctx, Long clusterId, String accion) {
    String nombre = ctx.nombreCluster(clusterId);
    if (cerradoEnAlguno(ctx, clusterId)) {
      throw new ConflictoException(nombre + " está cerrado: reábrelo y restablécelo para " + accion + ".");
    }
    if (SimulacionConfiguracionService.empezadoEnAlguno(ctx, clusterId)) {
      throw new ConflictoException(nombre + " ya empezó a balancearse: restablécelo para " + accion + ".");
    }
  }

  /**
   * La ruta llegó a mano desde otro cluster que ya empezó a balancearse: su «antes» está ahí y no
   * se puede perder (pasarla a DATA o quitar su cluster).
   */
  private static void exigirOrigenSinEmpezar(Contexto ctx, RutaData ruta, String accion) {
    Long origen = ruta.clusterOriginalId();
    if (origen != null && !origen.equals(ruta.clusterId()) && SimulacionConfiguracionService.empezadoEnAlguno(ctx, origen)) {
      throw new ConflictoException(
          "La ruta " + ruta.codigo() + " llegó a mano desde " + ctx.nombreCluster(origen)
              + ", que ya empezó a balancearse: devuélvela a " + ctx.nombreCluster(origen)
              + " o restablece " + ctx.nombreCluster(origen) + " antes de " + accion + ".");
    }
  }

  /** Un chute bloqueado no recibe rutas; quedarse en el suyo sí se permite (como al mover a mano). */
  private void exigirNoBloqueadoSiCambia(Contexto ctx, List<RutaData> rutas, long chute) {
    if (rutas.stream().anyMatch((r) -> r.chuteId() != chute) && configuracion.bloqueado(ctx, chute)) {
      throw new ConflictoException(
          "El " + SimulacionConfiguracionService.etiquetaChute(ctx, chute) + " está bloqueado: no se pueden mover rutas ahí.");
    }
  }

  /** Número del bloque de DATA: la posición del chute entre los visibles del plano (como en la pantalla). */
  private String bloque(long chute) {
    int indice = plano.idsVisibles().indexOf(chute);
    return indice < 0 ? "—" : String.valueOf(indice + 1);
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
