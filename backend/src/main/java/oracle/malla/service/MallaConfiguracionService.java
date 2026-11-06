package oracle.malla.service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import oracle.common.config.Transacciones;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import oracle.common.support.ActorActual;
import oracle.common.support.Auditoria;
import oracle.common.support.Trazas;
import oracle.malla.model.MallaDatos;
import oracle.malla.model.MallaRegla;
import oracle.malla.repository.MallaDatosRepository;
import oracle.malla.repository.MallaReglaRepository;
import oracle.malla.request.MallaReglaActualizarRequest;
import oracle.malla.response.EvaluacionMallaResponse;
import oracle.malla.response.HallazgoMallaResponse;
import oracle.malla.response.ParametroMallaResponse;
import oracle.malla.response.ReglaEvaluadaResponse;
import oracle.malla.response.ReglaMallaResponse;
import oracle.malla.response.ResumenEvaluacionMallaResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Orquesta el módulo Configuración de la Malla Operativa: lee el catálogo de
 * reglas, evalúa la malla capturada y permite ajustar parámetros/activación.
 * El navegador nunca evalúa reglas: sólo pinta el resultado del servidor.
 */
@Slf4j
@Service
public class MallaConfiguracionService {

    private static final DateTimeFormatter HORA_ENTRADA = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter HORA_SALIDA = DateTimeFormatter.ofPattern("HH:mm");
    private static final String SIN_INSTALAR =
            "La malla operativa no está instalada. Ejecute backend/postgresql.sql.";

    private final MallaReglaRepository reglas;
    private final MallaDatosRepository datos;
    private final EvaluadorReglasMalla evaluador;
    private final Transacciones transacciones;
    private final ObjectMapper json;

    public MallaConfiguracionService(MallaReglaRepository reglas, MallaDatosRepository datos,
            EvaluadorReglasMalla evaluador, Transacciones transacciones, ObjectMapper json) {
        this.reglas = reglas;
        this.datos = datos;
        this.evaluador = evaluador;
        this.transacciones = transacciones;
        this.json = json;
    }

    /** Evalúa las reglas sobre todos los centros o, con centroId, solo sobre ese. */
    public ApiResponse<EvaluacionMallaResponse> evaluar(Long centroId) {
        EvaluacionMallaResponse resultado = Trazas.medir(log, "MallaConfiguracionService.evaluar centro=" + centroId,
                () -> transacciones.consultar(() -> evaluacion(centroId)));
        return ApiResponse.ok("Malla evaluada.", resultado);
    }

    public ApiResponse<ReglaMallaResponse> actualizar(Long id, MallaReglaActualizarRequest req) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "MallaConfiguracionService.actualizar id=" + id,
                () -> transacciones.ejecutar(() -> {
                    MallaRegla regla = reglas.findById(id)
                            .orElseThrow(() -> new RecursoNoEncontradoException(
                                    "No existe la regla de malla con id " + id + "."));
                    if (req.activa() != null) {
                        regla.setActiva(req.activa());
                    }
                    if (req.parametros() != null) {
                        Map<String, String> valores = parametros(regla);
                        valores.putAll(validarParametros(regla.getCodigo(), req.parametros()));
                        try {
                            regla.setParametros(json.writeValueAsString(valores));
                        } catch (Exception ex) {
                            throw new SolicitudInvalidaException(
                                    "No se pudieron guardar los parámetros de la regla.");
                        }
                    }
                    Auditoria.alActualizar(regla, actor);
                    MallaRegla guardada = reglas.save(regla);
                    return ApiResponse.ok("Regla actualizada.", reglaResponse(guardada));
                }));
    }

    private EvaluacionMallaResponse evaluacion(Long centroId) {
        List<MallaRegla> catalogo = catalogo();
        MallaDatos foto;
        try {
            foto = datos.cargar(centroId);
        } catch (DataAccessException ex) {
            log.error("No se pudo leer la malla capturada: {}", ex.getMessage());
            throw new ConflictoException(SIN_INSTALAR);
        }

        List<ReglaEvaluadaResponse> resultados = new ArrayList<>();
        int cumplen = 0;
        int noCumplen = 0;
        int inactivas = 0;
        int noEvaluables = 0;
        int totalHallazgos = 0;

        for (MallaRegla regla : catalogo) {
            List<ParametroMallaResponse> parametros = parametrosResponse(regla);
            String estado;
            List<HallazgoMallaResponse> detalles;

            if (!regla.isActiva()) {
                estado = "INACTIVA";
                detalles = List.of();
                inactivas++;
            } else {
                try {
                    detalles = evaluador.evaluar(regla.getCodigo(), parametros(regla), foto);
                    estado = detalles.isEmpty() ? "CUMPLE" : "NO_CUMPLE";
                    if (detalles.isEmpty()) {
                        cumplen++;
                    } else {
                        noCumplen++;
                    }
                } catch (RuntimeException ex) {
                    log.warn("No se pudo evaluar la regla {}: {}", regla.getCodigo(), ex.getMessage());
                    estado = "NO_EVALUABLE";
                    noEvaluables++;
                    detalles = List.of(new HallazgoMallaResponse(
                            "—", "General", regla.getNombre(),
                            "La regla no se pudo evaluar: " + ex.getMessage(),
                            "Evaluación completa", "Error"));
                }
            }

            totalHallazgos += detalles.size();
            resultados.add(new ReglaEvaluadaResponse(
                    regla.getId(),
                    regla.getCodigo(),
                    regla.getNombre(),
                    regla.getDescripcion(),
                    regla.getAmbito(),
                    regla.getSeveridad(),
                    regla.isActiva(),
                    regla.getOrden(),
                    estado,
                    detalles.size(),
                    parametros,
                    detalles));
        }

        ResumenEvaluacionMallaResponse resumen = new ResumenEvaluacionMallaResponse(
                catalogo.size(), catalogo.size() - inactivas, cumplen, noCumplen,
                inactivas, noEvaluables, totalHallazgos);
        return new EvaluacionMallaResponse(java.time.Instant.now(), resumen, resultados);
    }

    private List<MallaRegla> catalogo() {
        try {
            List<MallaRegla> lista = reglas.findAllByOrderByOrdenAscIdAsc();
            if (lista.isEmpty()) {
                throw new ConflictoException(SIN_INSTALAR);
            }
            return lista;
        } catch (DataAccessException ex) {
            log.error("No se pudo leer el catálogo de reglas de la malla: {}", ex.getMessage());
            throw new ConflictoException(SIN_INSTALAR);
        }
    }

    private ReglaMallaResponse reglaResponse(MallaRegla regla) {
        return new ReglaMallaResponse(
                regla.getId(),
                regla.getCodigo(),
                regla.getNombre(),
                regla.getDescripcion(),
                regla.getAmbito(),
                regla.getSeveridad(),
                regla.isActiva(),
                regla.getOrden(),
                parametrosResponse(regla));
    }

    private List<ParametroMallaResponse> parametrosResponse(MallaRegla regla) {
        Map<String, String> valores = parametros(regla);
        return ReglasMallaCatalogo.parametros(regla.getCodigo()).stream()
                .map(def -> new ParametroMallaResponse(
                        def.clave(), def.etiqueta(), def.tipo(),
                        valores.getOrDefault(def.clave(), def.porDefecto()), def.min(), def.max()))
                .toList();
    }

    private Map<String, String> parametros(MallaRegla regla) {
        Map<String, String> valores = new LinkedHashMap<>(ReglasMallaCatalogo.porDefecto(regla.getCodigo()));
        String guardados = regla.getParametros();
        if (guardados == null || guardados.isBlank()) {
            return valores;
        }
        try {
            Map<String, String> leidos = json.readValue(guardados, new TypeReference<Map<String, String>>() {
            });
            valores.putAll(leidos);
        } catch (Exception ex) {
            log.warn("Parámetros inválidos en la regla {}: {}", regla.getCodigo(), ex.getMessage());
        }
        return valores;
    }

    private Map<String, String> validarParametros(String codigo, Map<String, String> enviados) {
        Map<String, ReglasMallaCatalogo.ParametroDef> definidos = new LinkedHashMap<>();
        for (ReglasMallaCatalogo.ParametroDef def : ReglasMallaCatalogo.parametros(codigo)) {
            definidos.put(def.clave(), def);
        }
        Map<String, String> normalizados = new LinkedHashMap<>();
        for (Map.Entry<String, String> entrada : enviados.entrySet()) {
            ReglasMallaCatalogo.ParametroDef def = definidos.get(entrada.getKey());
            if (def == null) {
                throw new SolicitudInvalidaException(
                        "La regla " + codigo + " no admite el parámetro '" + entrada.getKey() + "'.");
            }
            normalizados.put(def.clave(), normalizar(def, entrada.getValue()));
        }
        return normalizados;
    }

    private String normalizar(ReglasMallaCatalogo.ParametroDef def, String valor) {
        String texto = valor == null ? "" : valor.trim();
        return switch (def.tipo()) {
            case "ENTERO" -> {
                int numero;
                try {
                    numero = Integer.parseInt(texto);
                } catch (NumberFormatException ex) {
                    throw new SolicitudInvalidaException(
                            "El parámetro '" + def.etiqueta() + "' debe ser un número entero.");
                }
                if (numero < def.min() || numero > def.max()) {
                    throw new SolicitudInvalidaException(
                            "El parámetro '" + def.etiqueta() + "' debe estar entre " + def.min()
                                    + " y " + def.max() + ".");
                }
                yield Integer.toString(numero);
            }
            case "BOOLEANO" -> {
                if (!"true".equalsIgnoreCase(texto) && !"false".equalsIgnoreCase(texto)) {
                    throw new SolicitudInvalidaException(
                            "El parámetro '" + def.etiqueta() + "' debe ser true o false.");
                }
                yield texto.toLowerCase();
            }
            case "HORA" -> {
                try {
                    yield LocalTime.parse(texto, HORA_ENTRADA).format(HORA_SALIDA);
                } catch (RuntimeException ex) {
                    throw new SolicitudInvalidaException(
                            "El parámetro '" + def.etiqueta() + "' debe tener formato HH:mm.");
                }
            }
            case "LISTA_HORAS" -> {
                List<String> horas = new ArrayList<>();
                for (String parte : texto.split(",")) {
                    if (parte.isBlank()) {
                        continue;
                    }
                    try {
                        horas.add(LocalTime.parse(parte.trim(), HORA_ENTRADA).format(HORA_SALIDA));
                    } catch (RuntimeException ex) {
                        throw new SolicitudInvalidaException(
                                "El parámetro '" + def.etiqueta() + "' debe ser una lista de horas HH:mm.");
                    }
                }
                if (horas.isEmpty()) {
                    throw new SolicitudInvalidaException(
                            "El parámetro '" + def.etiqueta() + "' necesita al menos una hora.");
                }
                yield String.join(",", horas);
            }
            default -> throw new SolicitudInvalidaException(
                    "Tipo de parámetro no soportado: " + def.tipo() + ".");
        };
    }
}
