package oracle.simulacion.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import oracle.simulacion.model.Chute;
import oracle.simulacion.repository.ChuteRepository;
import oracle.simulacion.repository.SimulacionLotesRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Plano físico de chutes de Simulación: los chutes numerados de {@code primero}
 * a {@code ultimo} (1 a 33) existen siempre, aunque el Excel no los traiga. Los
 * {@code manuales} (19, 20 y 21) se ven con su franja de peligro y reciben rutas
 * solo a mano: el balanceo automático, el reparto al azar y las cargas no los
 * usan, y lo que se deja ahí a mano el balanceo no lo mueve. Los
 * {@code bloqueados} (ninguno por ahora) no se usan: no se muestran, el Excel no
 * puede traer rutas en ellos y nada se mueve ni se balancea hacia ellos.
 *
 * <p>Se configura en {@code application.properties} ({@code simulacion.chutes.*})
 * sin tocar código. El número de un chute se lee de su nombre: «19», «CHUTE 19»,
 * «CH-19», «CHUTE N° 19» y «019» son el mismo chute 19.
 *
 * <p>Al arrancar se asegura que el catálogo tenga los chutes del plano (los que
 * falten se crean con su número como nombre); los registros ya cargados los ven
 * sin que se modifiquen sus datos.
 */
@Slf4j
@Component
public class PlanoChutes {

  /** Prefijo opcional (CHUTE, CH), separadores y «N°» opcionales, y el número. */
  private static final Pattern NUMERO =
      Pattern.compile("^(?:CH(?:UTE)?)?[\\s\\-_#.:]*(?:N[°º.O]?\\s*)?0*(\\d{1,6})$");

  /** Id y nombre en el catálogo de cada número del plano (bloqueados incluidos). */
  private record Catalogo(Map<Integer, Long> ids, Map<Integer, String> nombres) {
    static final Catalogo VACIO = new Catalogo(Map.of(), Map.of());
  }
  private final int primero;
  private final int ultimo;
  private final Set<Integer> bloqueados;
  /** Chutes de uso manual: se ven y reciben rutas a mano; nada automático los usa. */
  private final Set<Integer> manuales;
  private final ChuteRepository chuteRepo;
  private final SimulacionLotesRepository lotes;
  /** Se reemplaza entero: nunca se lee un id de una versión y el nombre de otra. */
  private volatile Catalogo catalogo = Catalogo.VACIO;

  public PlanoChutes(
      @Value("${simulacion.chutes.primero:1}") int primero,
      @Value("${simulacion.chutes.ultimo:33}") int ultimo,
      @Value("${simulacion.chutes.bloqueados:}") List<Integer> bloqueados,
      @Value("${simulacion.chutes.manuales:19,20,21}") List<Integer> manuales,
      ChuteRepository chuteRepo,
      SimulacionLotesRepository lotes) {
    if (primero < 1 || ultimo < primero) {
      throw new IllegalArgumentException(
          "simulacion.chutes: el rango " + primero + "–" + ultimo + " no es válido.");
    }
    this.primero = primero;
    this.ultimo = ultimo;
    this.bloqueados = Collections.unmodifiableSet(new LinkedHashSet<>(bloqueados));
    // Un número en las dos listas queda bloqueado (lo más restrictivo).
    Set<Integer> soloManuales = new LinkedHashSet<>(manuales);
    soloManuales.removeAll(this.bloqueados);
    this.manuales = Collections.unmodifiableSet(soloManuales);
    this.chuteRepo = chuteRepo;
    this.lotes = lotes;
  }

  /** Plano sin base de datos (pruebas): ids por número; el nombre es el número. Sin chutes manuales. */
  static PlanoChutes fijo(int primero, int ultimo, Set<Integer> bloqueados, Map<Integer, Long> ids) {
    return fijo(primero, ultimo, bloqueados, Set.of(), ids);
  }

  /** Plano sin base de datos (pruebas), con sus chutes de uso manual. */
  static PlanoChutes fijo(
      int primero, int ultimo, Set<Integer> bloqueados, Set<Integer> manuales, Map<Integer, Long> ids) {
    PlanoChutes plano =
        new PlanoChutes(primero, ultimo, List.copyOf(bloqueados), List.copyOf(manuales), null, null);
    Map<Integer, String> nombres = new TreeMap<>();
    ids.keySet().forEach((n) -> nombres.put(n, String.valueOf(n)));
    plano.catalogo = new Catalogo(Collections.unmodifiableMap(new TreeMap<>(ids)), nombres);
    return plano;
  }

  /** Completa el catálogo con los chutes del plano que falten y guarda sus ids. */
  @EventListener(ApplicationReadyEvent.class)
  public void preparar() {
    try {
      Map<Integer, Long> ids = new TreeMap<>();
      Map<Integer, String> nombres = new TreeMap<>();
      for (Chute chute : chuteRepo.findAll()) {
        Integer numero = numero(chute.getNombre());
        if (numero != null && enPlano(numero)) {
          // Si hay dos nombres para el mismo número («5» y «CHUTE 5»), gana el
          // que es solo el número; entre iguales, el más antiguo (id menor).
          boolean soloNumero = chute.getNombre().trim().matches("\\d+");
          Long actual = ids.get(numero);
          boolean actualSoloNumero = actual != null && nombres.get(numero).trim().matches("\\d+");
          if (actual == null
              || (soloNumero && !actualSoloNumero)
              || (soloNumero == actualSoloNumero && chute.getId() < actual)) {
            ids.put(numero, chute.getId());
            nombres.put(numero, chute.getNombre());
          }
        }
      }
      List<String> faltan = new ArrayList<>();
      for (int n = primero; n <= ultimo; n++) {
        if (!ids.containsKey(n)) {
          faltan.add(String.valueOf(n));
        }
      }
      if (!faltan.isEmpty()) {
        lotes.asegurarChutes(faltan).forEach((clave, id) -> {
          Integer numero = numero(clave);
          if (numero != null) {
            ids.put(numero, id);
            nombres.put(numero, String.valueOf(numero));
          }
        });
        log.info("Plano de chutes: se agregaron al catálogo {}", faltan);
      }
      catalogo = new Catalogo(Collections.unmodifiableMap(ids), Collections.unmodifiableMap(nombres));
    } catch (RuntimeException error) {
      // Sin base al arrancar: el plano no agrega columnas, pero las reglas
      // (bloqueados, rango) siguen valiendo porque se leen del nombre.
      log.warn("Plano de chutes: no se pudo preparar el catálogo ({})", error.getMessage());
    }
  }

  /** Número del chute según su nombre («CHUTE 19», «CH-19», «19», «019» → 19), o null si no tiene. */
  public static Integer numero(String nombre) {
    if (nombre == null) {
      return null;
    }
    String limpio =
        Normalizer.normalize(nombre, Normalizer.Form.NFKC)
            .replaceAll("[\\s\\u00A0]+", " ")
            .trim()
            .toUpperCase(Locale.ROOT);
    Matcher m = NUMERO.matcher(limpio);
    return m.matches() ? Integer.valueOf(m.group(1)) : null;
  }

  public boolean enPlano(Integer numero) {
    return numero != null && numero >= primero && numero <= ultimo;
  }

  public boolean bloqueado(Integer numero) {
    return numero != null && bloqueados.contains(numero);
  }

  /** El chute de ese nombre está bloqueado. */
  public boolean bloqueado(String nombre) {
    return bloqueado(numero(nombre));
  }

  /**
   * Chute de uso manual (19, 20 y 21): se ve y recibe rutas solo a mano; el balanceo automático,
   * el reparto al azar y las cargas no lo usan, y lo que se deja ahí a mano no lo mueve el balanceo.
   */
  public boolean manual(Integer numero) {
    return numero != null && manuales.contains(numero);
  }

  /** El chute de ese nombre es de uso manual. */
  public boolean manual(String nombre) {
    return manual(numero(nombre));
  }

  /** Nada automático lo usa (balanceo, rango, buff, reparto al azar, cargas): bloqueado o manual. */
  public boolean fueraDelAutomatico(String nombre) {
    Integer numero = numero(nombre);
    return bloqueado(numero) || manual(numero);
  }

  /**
   * Motivo por el que un chute de una carga (Excel) no se puede usar, o null si sirve: fuera del
   * plano, bloqueado o de uso manual (ahí las rutas entran solo a mano).
   */
  public String problema(String nombre) {
    Integer numero = numero(nombre);
    if (!enPlano(numero)) {
      return "no está entre el CHUTE " + primero + " y el CHUTE " + ultimo;
    }
    if (bloqueado(numero)) {
      return "el CHUTE " + numero + " está bloqueado";
    }
    if (manual(numero)) {
      return "el CHUTE " + numero + " es de uso manual: sus rutas se mueven ahí a mano";
    }
    return null;
  }

  /**
   * Nombre con que se guarda el chute: el que ya tiene ese número en el
   * catálogo («CHUTE 5» → «5», o al revés si el catálogo usa «CHUTE 5»), así
   * nunca se crea un segundo chute 5.
   */
  public String nombreCanonico(String nombre) {
    Integer numero = numero(nombre);
    if (numero == null) {
      return nombre;
    }
    return catalogo.nombres().getOrDefault(numero, String.valueOf(numero));
  }

  /** Ids de los chutes del plano que se ven (sin bloqueados; los manuales sí), en orden. */
  public List<Long> idsVisibles() {
    return new ArrayList<>(idsVisiblesPorNumero().values());
  }

  /**
   * Ids de los chutes que usa lo automático (sin bloqueados ni manuales), en orden: el reparto al
   * azar de una ruta nueva y el número de los bloques de DATA (que así no cambia).
   */
  public List<Long> idsAutomaticos() {
    List<Long> ids = new ArrayList<>();
    idsVisiblesPorNumero().forEach((numero, id) -> {
      if (!manual(numero)) {
        ids.add(id);
      }
    });
    return ids;
  }

  /** Id del catálogo de cada número visible (sin bloqueados), en orden. */
  public Map<Integer, Long> idsVisiblesPorNumero() {
    Map<Integer, Long> visibles = new LinkedHashMap<>();
    catalogo.ids().forEach((numero, id) -> {
      if (!bloqueado(numero)) {
        visibles.put(numero, id);
      }
    });
    return visibles;
  }

  /** Nombre en el catálogo del chute de ese número. */
  public String nombreCatalogo(int numero) {
    return catalogo.nombres().getOrDefault(numero, String.valueOf(numero));
  }

  /**
   * Orden de los chutes por su número («CHUTE 18» antes que «22», aunque los
   * nombres mezclen estilos); los que no tienen número van al final, en orden natural.
   */
  public static final Comparator<String> ORDEN =
      Comparator.comparing(
              PlanoChutes::numero, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
          .thenComparing(SimulacionConfiguracionService::compararNatural);

  /** Nombres de los chutes bloqueados («19», «20», «21»), para avisos. */
  public List<String> nombresBloqueados() {
    return bloqueados.stream().sorted().map(String::valueOf).toList();
  }
}
