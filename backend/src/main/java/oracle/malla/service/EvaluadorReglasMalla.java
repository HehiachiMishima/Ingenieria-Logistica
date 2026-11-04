package oracle.malla.service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import oracle.malla.model.MallaDatos;
import oracle.malla.model.MallaDatos.Capacidad;
import oracle.malla.model.MallaDatos.Centro;
import oracle.malla.model.MallaDatos.Flujo;
import oracle.malla.model.MallaDatos.Ola;
import oracle.malla.model.MallaDatos.Turno;
import oracle.malla.model.MallaDatos.Zona;
import oracle.malla.response.HallazgoMallaResponse;
import org.springframework.stereotype.Component;

/**
 * Evalúa las reglas operativas sobre la malla capturada. Es el mismo motor que
 * usa el servidor: el módulo Configuración no recalcula nada en el navegador,
 * sólo pinta los hallazgos que devuelve este componente.
 *
 * Cada regla se evalúa de forma aislada. Si una regla falla por datos
 * inesperados, el service la marca NO_EVALUABLE y continúa con las demás.
 */
@Component
public class EvaluadorReglasMalla {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> ORDEN_PROCESOS = List.of("REC", "ALM", "REA", "OLA", "DES");
    private static final Map<String, String> NOMBRE_PROCESO = Map.of(
            "REC", "Recepción",
            "ALM", "Almacenamiento",
            "REA", "Reabastecimiento",
            "OLA", "Picking",
            "DES", "Despacho");

    public List<HallazgoMallaResponse> evaluar(String codigo, Map<String, String> parametros, MallaDatos datos) {
        return switch (codigo) {
            case ReglasMallaCatalogo.OLA_LANZAMIENTO -> olaLanzamiento(parametros, datos);
            case ReglasMallaCatalogo.OLA_SECUENCIA -> olaSecuencia(datos);
            case ReglasMallaCatalogo.OLA_MAX_PICKING -> olaMaxPicking(parametros, datos);
            case ReglasMallaCatalogo.OLA_CONFIRMADA -> olaConfirmada(datos);
            case ReglasMallaCatalogo.TURNO_COBERTURA -> turnoCobertura(parametros, datos);
            case ReglasMallaCatalogo.TURNO_REFRIGERIO -> turnoRefrigerio(parametros, datos);
            case ReglasMallaCatalogo.FLUJO_VENTANA_TURNO -> flujoVentanaTurno(parametros, datos);
            case ReglasMallaCatalogo.FLUJO_DURACION -> flujoDuracion(parametros, datos);
            case ReglasMallaCatalogo.FLUJO_CONFIRMADO -> flujoConfirmado(datos);
            case ReglasMallaCatalogo.CAPACIDAD_DECLARADA -> capacidadDeclarada(datos);
            case ReglasMallaCatalogo.DOTACION_PLANIFICADA -> dotacionPlanificada(datos);
            case ReglasMallaCatalogo.ZONA_LPH_DISENO -> zonaLphDiseno(datos);
            default -> throw new IllegalArgumentException("Regla sin evaluador: " + codigo);
        };
    }

    // ------------------------------------------------------------------
    // Olas
    // ------------------------------------------------------------------

    /**
     * El límite de cada ola sale de su posición dentro del cluster (la primera
     * que se lanza usa el primer límite, la segunda el segundo...), no de su
     * nombre. Las horas se miden desde el inicio del día operativo del centro
     * (su primer turno), así una ola lanzada después de medianoche cuenta como
     * más tarde y no como más temprano.
     */
    private List<HallazgoMallaResponse> olaLanzamiento(Map<String, String> parametros, MallaDatos datos) {
        List<LocalTime> limites = listaHoras(parametros.get("limites"), List.of(LocalTime.of(7, 0), LocalTime.of(15, 0)));
        int tolerancia = entero(parametros.get("toleranciaMin"), 0);
        List<HallazgoMallaResponse> hallazgos = new ArrayList<>();

        Map<String, List<Ola>> clusters = new LinkedHashMap<>();
        for (Ola ola : datos.olas()) {
            clusters.computeIfAbsent(ola.centroId() + "|" + ola.cluster(), clave -> new ArrayList<>()).add(ola);
        }
        for (List<Ola> grupo : clusters.values()) {
            Centro centro = centro(datos, grupo.getFirst().centroId());
            int inicioDia = inicioDia(datos, grupo.getFirst().centroId());
            List<Ola> ordenadas = new ArrayList<>(grupo);
            ordenadas.sort(Comparator.comparingInt(ola -> ola.lanzamiento() == null
                    ? Integer.MAX_VALUE : relativo(inicioDia, ola.lanzamiento())));
            for (int i = 0; i < ordenadas.size(); i++) {
                Ola ola = ordenadas.get(i);
                LocalTime limite = limites.get(Math.min(i, limites.size() - 1));
                if (ola.lanzamiento() == null) {
                    hallazgos.add(hallazgo(centro, entidadOla(ola),
                            "La ola no tiene hora de lanzamiento.",
                            "Lanzamiento ≤ " + hora(limite),
                            "Sin hora"));
                    continue;
                }
                int exceso = relativo(inicioDia, ola.lanzamiento()) - relativo(inicioDia, limite);
                if (exceso > tolerancia) {
                    hallazgos.add(hallazgo(centro, entidadOla(ola),
                            "El lanzamiento se pasa " + duracion(exceso) + " del límite de la ola.",
                            "Lanzamiento ≤ " + hora(limite) + " (±" + tolerancia + " min)",
                            "Lanzamiento " + hora(ola.lanzamiento())));
                }
            }
        }
        return hallazgos;
    }

    private List<HallazgoMallaResponse> olaSecuencia(MallaDatos datos) {
        List<HallazgoMallaResponse> hallazgos = new ArrayList<>();
        for (Ola ola : datos.olas()) {
            Centro centro = centro(datos, ola.centroId());
            if (ola.pedido() == null || ola.lanzamiento() == null || ola.cierre() == null || ola.despacho() == null) {
                hallazgos.add(hallazgo(centro, entidadOla(ola),
                        "La ola tiene hitos sin hora; no se puede verificar la secuencia.",
                        "Caída ≤ lanzamiento < cierre ≤ despacho",
                        "Hitos incompletos"));
                continue;
            }
            // Cada hito va después del anterior (puede cruzar la medianoche una vez),
            // así que un hito «antes» del previo solo encaja dando la vuelta al día:
            // si el ciclo completo no cabe en 24 h, el orden está mal.
            int pedido = minutos(ola.pedido());
            int lanzamiento = avance(pedido, ola.lanzamiento());
            int cierre = avance(lanzamiento, ola.cierre());
            int despacho = avance(cierre, ola.despacho());
            boolean ok = lanzamiento < cierre && despacho - pedido < 1440;
            if (!ok) {
                hallazgos.add(hallazgo(centro, entidadOla(ola),
                        "Los hitos de la ola no siguen el orden operativo.",
                        "Caída ≤ lanzamiento < cierre ≤ despacho",
                        hora(ola.pedido()) + " → " + hora(ola.lanzamiento()) + " → "
                                + hora(ola.cierre()) + " → " + hora(ola.despacho())));
            }
        }
        return hallazgos;
    }

    private List<HallazgoMallaResponse> olaMaxPicking(Map<String, String> parametros, MallaDatos datos) {
        int horasMax = entero(parametros.get("horasMax"), 8);
        List<HallazgoMallaResponse> hallazgos = new ArrayList<>();
        for (Ola ola : datos.olas()) {
            if (ola.lanzamiento() == null || ola.cierre() == null) {
                continue;
            }
            int duracion = duracion(ola.lanzamiento(), ola.cierre());
            if (duracion > horasMax * 60) {
                Centro centro = centro(datos, ola.centroId());
                hallazgos.add(hallazgo(centro, entidadOla(ola),
                        "El picking dura más de lo permitido.",
                        "Duración ≤ " + horasMax + " h",
                        duracion(duracion)));
            }
        }
        return hallazgos;
    }

    private List<HallazgoMallaResponse> olaConfirmada(MallaDatos datos) {
        Map<String, List<Ola>> grupos = new LinkedHashMap<>();
        for (Ola ola : datos.olas()) {
            if (!ola.confirmado()) {
                grupos.computeIfAbsent(ola.centroId() + "|" + ola.cluster(), clave -> new ArrayList<>()).add(ola);
            }
        }
        List<HallazgoMallaResponse> hallazgos = new ArrayList<>();
        for (List<Ola> grupo : grupos.values()) {
            Ola primera = grupo.getFirst();
            Centro centro = centro(datos, primera.centroId());
            hallazgos.add(hallazgo(centro, "Cluster " + primera.cluster(),
                    grupo.size() + " ola(s) sin confirmar: "
                            + grupo.stream().map(Ola::nombre).collect(Collectors.joining(", ")) + ".",
                    "Todas las olas confirmadas",
                    grupo.size() + " pendiente(s)"));
        }
        return hallazgos;
    }

    // ------------------------------------------------------------------
    // Turnos
    // ------------------------------------------------------------------

    /**
     * El día es un círculo: un hueco que cruza la medianoche (23:00–07:00) es
     * uno solo. Se reporta si algún hueco supera la tolerancia.
     */
    private List<HallazgoMallaResponse> turnoCobertura(Map<String, String> parametros, MallaDatos datos) {
        int gapMax = entero(parametros.get("minutosGapMax"), 0);
        List<HallazgoMallaResponse> hallazgos = new ArrayList<>();

        for (Centro centro : datos.centros()) {
            List<Turno> turnos = datos.turnos().stream()
                    .filter(t -> t.centroId().equals(centro.id()))
                    .toList();
            if (turnos.isEmpty()) {
                hallazgos.add(hallazgo(centro, "Turnos",
                        "El centro no tiene turnos definidos.",
                        "Cobertura de 24 h",
                        "Sin turnos"));
                continue;
            }
            boolean[] cubierto = new boolean[1440];
            for (Turno turno : turnos) {
                int inicio = minutos(turno.inicio());
                int largo = duracion(turno.inicio(), turno.fin());
                for (int m = 0; m < (largo == 0 ? 1440 : largo); m++) {
                    cubierto[(inicio + m) % 1440] = true;
                }
            }
            List<int[]> huecos = huecos(cubierto);
            int sinCubrir = huecos.stream().mapToInt(h -> h[1]).sum();
            List<String> grandes = huecos.stream()
                    .filter(h -> h[1] > gapMax)
                    .map(h -> hora(h[0]) + "–" + hora(h[0] + h[1]) + " (" + duracion(h[1]) + ")")
                    .toList();
            if (!grandes.isEmpty()) {
                hallazgos.add(hallazgo(centro, "Turnos",
                        "La cobertura diaria tiene " + grandes.size() + " hueco(s) sin turno.",
                        "Cobertura de 24 h (hueco máximo " + gapMax + " min)",
                        "Cubre " + duracion(1440 - sinCubrir) + " · " + String.join(", ", grandes)));
            }
        }
        return hallazgos;
    }

    /** Huecos [inicio, largo] de un día circular; un hueco que cruza la medianoche es uno solo. */
    private static List<int[]> huecos(boolean[] cubierto) {
        List<int[]> huecos = new ArrayList<>();
        int arranque = -1;
        for (int m = 0; m < 1440; m++) {
            if (cubierto[m]) {
                arranque = m;
                break;
            }
        }
        if (arranque < 0) {
            huecos.add(new int[] {0, 1440});
            return huecos;
        }
        int inicio = -1;
        for (int i = 1; i <= 1440; i++) {
            int m = (arranque + i) % 1440;
            if (!cubierto[m] && inicio < 0) {
                inicio = m;
            } else if (cubierto[m] && inicio >= 0) {
                huecos.add(new int[] {inicio, Math.floorMod(m - inicio, 1440)});
                inicio = -1;
            }
        }
        return huecos;
    }

    private List<HallazgoMallaResponse> turnoRefrigerio(Map<String, String> parametros, MallaDatos datos) {
        boolean exigir = booleano(parametros.get("exigirRefrigerio"), true);
        List<HallazgoMallaResponse> hallazgos = new ArrayList<>();
        for (Turno turno : datos.turnos()) {
            Centro centro = centro(datos, turno.centroId());
            if (turno.refrigerio() == null) {
                if (exigir) {
                    hallazgos.add(hallazgo(centro, entidadTurno(turno),
                            "El turno no tiene hora de refrigerio.",
                            "Refrigerio dentro del turno",
                            "Sin refrigerio"));
                }
                continue;
            }
            if (!dentro(turno.refrigerio(), turno.inicio(), turno.fin())) {
                hallazgos.add(hallazgo(centro, entidadTurno(turno),
                        "El refrigerio cae fuera de la ventana del turno.",
                        "Refrigerio entre " + hora(turno.inicio()) + " y " + hora(turno.fin()),
                        "Refrigerio " + hora(turno.refrigerio())));
            }
        }
        return hallazgos;
    }

    // ------------------------------------------------------------------
    // Flujos
    // ------------------------------------------------------------------

    private List<HallazgoMallaResponse> flujoVentanaTurno(Map<String, String> parametros, MallaDatos datos) {
        boolean incluirIL = booleano(parametros.get("incluirIL"), false);
        List<HallazgoMallaResponse> hallazgos = new ArrayList<>();
        Map<Long, List<Turno>> turnosPorCentro = datos.turnos().stream()
                .collect(Collectors.groupingBy(Turno::centroId));

        for (Flujo flujo : datos.flujos()) {
            if (!incluirIL && !"OPS".equals(flujo.responsable())) {
                continue;
            }
            List<Turno> turnos = turnosPorCentro.getOrDefault(flujo.centroId(), List.of());
            Centro centro = centro(datos, flujo.centroId());
            if (flujo.inicio() == null) {
                hallazgos.add(hallazgo(centro, entidadFlujo(flujo),
                        "El paso no tiene hora de inicio.",
                        "Inicio cubierto por un turno",
                        "Sin hora"));
                continue;
            }
            boolean inicioCubierto = turnos.stream().anyMatch(t -> dentro(flujo.inicio(), t.inicio(), t.fin()));
            boolean finCubierto = flujo.fin() == null
                    || turnos.stream().anyMatch(t -> terminaDentro(flujo.fin(), t.inicio(), t.fin()));
            if (!inicioCubierto || !finCubierto) {
                String ventana = hora(flujo.inicio()) + (flujo.fin() == null ? "" : "–" + hora(flujo.fin()));
                hallazgos.add(hallazgo(centro, entidadFlujo(flujo),
                        "La ventana del paso no queda cubierta por un turno.",
                        "Inicio y fin cubiertos por turnos",
                        ventana + (inicioCubierto ? " (fin fuera)" : " (inicio fuera)")));
            }
        }
        return hallazgos;
    }

    private List<HallazgoMallaResponse> flujoDuracion(Map<String, String> parametros, MallaDatos datos) {
        int horasMax = entero(parametros.get("horasMax"), 12);
        List<HallazgoMallaResponse> hallazgos = new ArrayList<>();
        for (Flujo flujo : datos.flujos()) {
            if (flujo.inicio() == null || flujo.fin() == null) {
                continue;
            }
            int duracion = duracion(flujo.inicio(), flujo.fin());
            if (duracion > horasMax * 60) {
                Centro centro = centro(datos, flujo.centroId());
                hallazgos.add(hallazgo(centro, entidadFlujo(flujo),
                        "El paso dura más de lo permitido.",
                        "Duración ≤ " + horasMax + " h",
                        duracion(duracion)));
            }
        }
        return hallazgos;
    }

    private List<HallazgoMallaResponse> flujoConfirmado(MallaDatos datos) {
        Map<String, List<Flujo>> grupos = new LinkedHashMap<>();
        for (Flujo flujo : datos.flujos()) {
            if (!flujo.confirmado()) {
                grupos.computeIfAbsent(flujo.centroId() + "|" + flujo.proceso(), clave -> new ArrayList<>()).add(flujo);
            }
        }
        List<HallazgoMallaResponse> hallazgos = new ArrayList<>();
        for (List<Flujo> grupo : grupos.values()) {
            Flujo primero = grupo.getFirst();
            Centro centro = centro(datos, primero.centroId());
            hallazgos.add(hallazgo(centro, nombreProceso(primero.proceso()),
                    grupo.size() + " paso(s) por confirmar.",
                    "Todos los pasos confirmados",
                    grupo.size() + " pendiente(s)"));
        }
        return hallazgos;
    }

    // ------------------------------------------------------------------
    // Datos maestros
    // ------------------------------------------------------------------

    private List<HallazgoMallaResponse> capacidadDeclarada(MallaDatos datos) {
        List<HallazgoMallaResponse> hallazgos = new ArrayList<>();
        for (Centro centro : datos.centros()) {
            Capacidad capacidad = datos.capacidades().stream()
                    .filter(c -> c.centroId().equals(centro.id()))
                    .findFirst().orElse(null);
            if (capacidad == null) {
                hallazgos.add(hallazgo(centro, "Capacidad diaria",
                        "El centro no tiene capacidad declarada.",
                        "Unidades, líneas, bultos y proveedores por día",
                        "Sin registro"));
                continue;
            }
            List<String> faltantes = new ArrayList<>();
            if (capacidad.unidades() == null || capacidad.unidades() <= 0) {
                faltantes.add("unidades/día");
            }
            if (capacidad.lineas() == null || capacidad.lineas() <= 0) {
                faltantes.add("líneas/día");
            }
            if (capacidad.bultos() == null || capacidad.bultos() <= 0) {
                faltantes.add("bultos recepción/día");
            }
            if (capacidad.proveedores() == null || capacidad.proveedores() <= 0) {
                faltantes.add("proveedores/día");
            }
            if (!faltantes.isEmpty()) {
                hallazgos.add(hallazgo(centro, "Capacidad diaria",
                        "Faltan dimensiones de capacidad: " + String.join(", ", faltantes) + ".",
                        "Capacidad diaria completa",
                        faltantes.size() + " sin definir"));
            }
        }
        return hallazgos;
    }

    /** Cada proceso necesita personas en cada turno del centro, haya o no fila guardada. */
    private List<HallazgoMallaResponse> dotacionPlanificada(MallaDatos datos) {
        List<HallazgoMallaResponse> hallazgos = new ArrayList<>();
        for (Centro centro : datos.centros()) {
            List<Turno> turnos = datos.turnos().stream()
                    .filter(t -> t.centroId().equals(centro.id()))
                    .toList();
            for (String proceso : ORDEN_PROCESOS) {
                List<String> sinPersonas = turnos.stream()
                        .filter(turno -> datos.dotaciones().stream()
                                .noneMatch(d -> d.turnoId().equals(turno.id()) && d.proceso().equals(proceso)
                                        && d.personas() != null && d.personas() > 0))
                        .map(Turno::codigo)
                        .toList();
                if (!sinPersonas.isEmpty()) {
                    hallazgos.add(hallazgo(centro, nombreProceso(proceso),
                            sinPersonas.size() + " turno(s) sin dotación planificada: "
                                    + String.join(", ", sinPersonas) + ".",
                            "Personas asignadas en todos los turnos",
                            sinPersonas.size() + " sin definir"));
                }
            }
        }
        return hallazgos;
    }

    private List<HallazgoMallaResponse> zonaLphDiseno(MallaDatos datos) {
        List<HallazgoMallaResponse> hallazgos = new ArrayList<>();
        for (Zona zona : datos.zonas()) {
            if (zona.lphDiseno() == null || zona.lphDiseno() <= 0) {
                Centro centro = centro(datos, zona.centroId());
                String real = zona.lphReal() == null ? "sin LPH real" : "LPH real " + zona.lphReal();
                hallazgos.add(hallazgo(centro, "Zona " + zona.nombre(),
                        "La zona no tiene LPH de diseño.",
                        "LPH de diseño definido",
                        real));
            }
        }
        return hallazgos;
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static HallazgoMallaResponse hallazgo(Centro centro, String entidad, String detalle,
            String esperado, String obtenido) {
        return new HallazgoMallaResponse(
                centro == null ? "—" : centro.codigo(),
                centro == null ? "General" : centro.nombre(),
                entidad,
                detalle,
                esperado,
                obtenido);
    }

    private static Centro centro(MallaDatos datos, Long centroId) {
        return datos.centros().stream()
                .filter(c -> c.id().equals(centroId))
                .findFirst().orElse(null);
    }

    private static String entidadOla(Ola ola) {
        return "Cluster " + ola.cluster() + " · " + ola.nombre();
    }

    private static String entidadTurno(Turno turno) {
        return turno.nombre() + " (" + hora(turno.inicio()) + "–" + hora(turno.fin()) + ")";
    }

    private static String entidadFlujo(Flujo flujo) {
        return nombreProceso(flujo.proceso()) + " · paso " + flujo.orden();
    }

    private static String nombreProceso(String proceso) {
        return NOMBRE_PROCESO.getOrDefault(proceso, proceso);
    }

    private static int minutos(LocalTime hora) {
        return hora.getHour() * 60 + hora.getMinute();
    }

    /** Inicio del día operativo del centro: su primer turno (07:00 si no tiene). */
    private static int inicioDia(MallaDatos datos, Long centroId) {
        return datos.turnos().stream()
                .filter(t -> t.centroId().equals(centroId))
                .findFirst()
                .map(t -> minutos(t.inicio()))
                .orElse(7 * 60);
    }

    /** Minutos desde el inicio del día operativo (da la vuelta a medianoche). */
    private static int relativo(int inicioDia, LocalTime hora) {
        return Math.floorMod(minutos(hora) - inicioDia, 1440);
    }

    private static int avance(int anterior, LocalTime hora) {
        int valor = minutos(hora);
        if (valor < anterior) {
            valor += 1440;
        }
        return valor;
    }

    private static int duracion(LocalTime inicio, LocalTime fin) {
        int valor = minutos(fin) - minutos(inicio);
        return valor < 0 ? valor + 1440 : valor;
    }

    private static boolean dentro(LocalTime hora, LocalTime inicio, LocalTime fin) {
        int h = minutos(hora);
        int i = minutos(inicio);
        int f = minutos(fin);
        if (i == f) {
            return true;
        }
        return i < f ? h >= i && h < f : h >= i || h < f;
    }

    /** Un fin de paso cae en el turno si está en (inicio, fin]: puede terminar justo al cierre. */
    private static boolean terminaDentro(LocalTime hora, LocalTime inicio, LocalTime fin) {
        int h = minutos(hora);
        int i = minutos(inicio);
        int f = minutos(fin);
        if (i == f) {
            return true;
        }
        return i < f ? h > i && h <= f : h > i || h <= f;
    }

    private static String hora(LocalTime hora) {
        return hora == null ? "—" : hora.format(HORA);
    }

    private static String hora(int minutos) {
        if (minutos == 1440) {
            return "24:00";
        }
        int normalizado = Math.floorMod(minutos, 1440);
        return String.format(Locale.ROOT, "%02d:%02d", normalizado / 60, normalizado % 60);
    }

    private static String duracion(int minutos) {
        int horas = minutos / 60;
        int resto = minutos % 60;
        if (horas == 0) {
            return resto + " min";
        }
        return resto == 0 ? horas + " h" : horas + " h " + resto + " min";
    }

    private static int entero(String valor, int porDefecto) {
        if (valor == null || valor.isBlank()) {
            return porDefecto;
        }
        try {
            return Integer.parseInt(valor.trim());
        } catch (NumberFormatException ex) {
            return porDefecto;
        }
    }

    private static boolean booleano(String valor, boolean porDefecto) {
        if (valor == null || valor.isBlank()) {
            return porDefecto;
        }
        return Boolean.parseBoolean(valor.trim());
    }

    private static List<LocalTime> listaHoras(String valor, List<LocalTime> porDefecto) {
        if (valor == null || valor.isBlank()) {
            return porDefecto;
        }
        List<LocalTime> horas = new ArrayList<>();
        for (String parte : valor.split(",")) {
            try {
                horas.add(LocalTime.parse(parte.trim(), HORA));
            } catch (RuntimeException ex) {
                // Ignora valores corruptos y conserva el resto.
            }
        }
        return horas.isEmpty() ? porDefecto : horas;
    }
}
