package oracle.simulacionsanta.service;

import java.time.Instant;
import java.util.List;
import oracle.simulacionsanta.model.EstadoRegistro;
import oracle.simulacionsanta.model.RegistroSanta;
import oracle.simulacionsanta.response.ConfiguracionResponse;
import oracle.simulacionsanta.response.PasilloConfig;
import oracle.simulacionsanta.response.RampaConfig;
import oracle.simulacionsanta.response.RegistroResponse;
import oracle.simulacionsanta.response.ValidacionExcelResponse;

/** Arma las respuestas del panel a partir de entidades y objetos del dominio. */
public final class SimulacionSantaRespuestas {

  private SimulacionSantaRespuestas() {}

  public static RegistroResponse registro(RegistroSanta r) {
    return new RegistroResponse(
        r.getId(),
        r.getNombre(),
        r.getNombreArchivo(),
        EstadoRegistro.de(r.getEnvioActual()),
        r.getEnvioActual(),
        r.getPaletas(),
        r.getRutas(),
        r.getFilasOmitidas(),
        r.getBultosEnvio1() + r.getBultosEnvio2() + r.getBultosEnvio3(),
        List.of(r.getBultosEnvio1(), r.getBultosEnvio2(), r.getBultosEnvio3()),
        List.of(r.getPaletasCluster1(), r.getPaletasCluster2(), r.getPaletasCluster3()),
        r.getCreadoPor(),
        r.getCreadoEn(),
        r.getActualizadoPor(),
        r.getActualizadoEn());
  }

  public static ValidacionExcelResponse validacion(ExcelSantaAnita.Resultado r) {
    return new ValidacionExcelResponse(
        r.nombreArchivo(),
        r.filasLeidas(),
        r.filas().size(),
        r.rutas(),
        r.bultos(),
        r.bultosEnvios(),
        r.paletasCluster(),
        r.errores(),
        r.totalErrores());
  }

  /**
   * Configuración de un registro (registroId) o de los predeterminados (registroId null).
   *
   * @param actualizadoPor null en los predeterminados de fábrica
   */
  public static ConfiguracionResponse configuracion(
      Long registroId, ConfiguracionSanta c, Long actualizadoPor, Instant actualizadoEn) {
    List<RampaConfig> rampas =
        c.rampas().stream()
            .map((r) -> new RampaConfig(r.numero(), r.cluster(), r.capacidad(), r.posiciones()))
            .toList();
    List<PasilloConfig> pasillos =
        c.pasillos().stream()
            .map(
                (p) -> new PasilloConfig(p.numero(), p.cluster(), PlanoSantaAnita.POSICIONES_PASILLO))
            .toList();
    return new ConfiguracionResponse(
        registroId,
        c.operarios(),
        c.bultosHora(),
        c.ordenClusters(),
        rampas,
        pasillos,
        actualizadoPor,
        actualizadoEn);
  }

  /** La configuración guardada en un registro (sin sus ubicaciones, que van aparte). */
  public static List<String> ordenDe(RegistroSanta r) {
    return List.of(r.getOrdenCluster1(), r.getOrdenCluster2(), r.getOrdenCluster3());
  }
}
