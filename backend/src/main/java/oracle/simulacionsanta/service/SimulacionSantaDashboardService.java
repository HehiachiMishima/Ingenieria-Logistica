package oracle.simulacionsanta.service;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import oracle.common.config.Transacciones;
import oracle.common.response.ApiResponse;
import oracle.common.support.Trazas;
import oracle.simulacionsanta.model.RegistroSanta;
import oracle.simulacionsanta.repository.AgregadoCluster;
import oracle.simulacionsanta.repository.RegistroSantaRepository;
import oracle.simulacionsanta.repository.SimulacionSantaLotesRepository;
import oracle.simulacionsanta.response.DashboardSanta;
import org.springframework.stereotype.Service;

/**
 * Dashboard de un registro. Cuatro lecturas en una transacción de solo lectura, sin recorrer
 * filas en Java: el registro (por su PK), sus 43 ubicaciones, los agregados por cluster (un
 * GROUP BY en SQL) y el candado. Las paletas sin ubicar salen de las posiciones de la
 * configuración guardada con la misma regla del asignador ({@link AsignadorUbicaciones#sinUbicar}).
 */
@Slf4j
@Service
public class SimulacionSantaDashboardService {

  private final RegistroSantaRepository registros;
  private final SimulacionSantaLotesRepository lotes;
  private final Transacciones transacciones;

  public SimulacionSantaDashboardService(
      RegistroSantaRepository registros,
      SimulacionSantaLotesRepository lotes,
      Transacciones transacciones) {
    this.registros = registros;
    this.lotes = lotes;
    this.transacciones = transacciones;
  }

  /** 404 si el registro no existe. */
  public ApiResponse<DashboardSanta> dashboard(Long id) {
    var datos =
        Trazas.medir(
            log,
            "SimulacionSantaDashboardService.dashboard id=" + id,
            () ->
                transacciones.consultar(
                    () -> {
                      RegistroSanta registro =
                          registros
                              .findById(id)
                              .orElseThrow(() -> SimulacionSantaRegistroService.noExiste(id));
                      ConfiguracionSanta config =
                          ConfiguracionSanta.guardada(
                              registro.getOperarios(),
                              registro.getBultosHora(),
                              SimulacionSantaRespuestas.ordenDe(registro),
                              lotes.ubicaciones(id));
                      return armar(
                          registro,
                          config,
                          lotes.agregadosPorCluster(id),
                          lotes.candado().bloqueado());
                    }));
    return ApiResponse.ok("Dashboard del registro.", datos);
  }

  /** Arma el dashboard; los clusters sin filas salen en cero. */
  static DashboardSanta armar(
      RegistroSanta registro,
      ConfiguracionSanta config,
      List<AgregadoCluster> agregados,
      boolean bloqueado) {
    AgregadoCluster[] porCluster = new AgregadoCluster[PlanoSantaAnita.CLUSTERS];
    for (int c = 1; c <= PlanoSantaAnita.CLUSTERS; c++) {
      porCluster[c - 1] = AgregadoCluster.vacio(c);
    }
    for (AgregadoCluster agregado : agregados) {
      if (agregado.cluster() >= 1 && agregado.cluster() <= PlanoSantaAnita.CLUSTERS) {
        porCluster[agregado.cluster() - 1] = agregado;
      }
    }
    int[] bultosEnvio = new int[AsignadorUbicaciones.ENVIOS];
    int paletasTotal = 0;
    List<DashboardSanta.Cluster> clusters = new ArrayList<>(PlanoSantaAnita.CLUSTERS);
    for (AgregadoCluster agregado : porCluster) {
      int c = agregado.cluster();
      int posiciones = AsignadorUbicaciones.posiciones(config, c);
      List<DashboardSanta.EnvioCluster> porEnvio = new ArrayList<>(AsignadorUbicaciones.ENVIOS);
      for (int e = 1; e <= AsignadorUbicaciones.ENVIOS; e++) {
        int bultos = agregado.bultosEnvio().get(e - 1);
        bultosEnvio[e - 1] += bultos;
        porEnvio.add(
            new DashboardSanta.EnvioCluster(e, bultos, agregado.completasEnvio().get(e - 1)));
      }
      paletasTotal += agregado.paletas();
      clusters.add(
          new DashboardSanta.Cluster(
              c,
              AsignadorUbicaciones.rampasDe(config, c).size(),
              AsignadorUbicaciones.pasillosDe(config, c).size(),
              posiciones,
              agregado.paletas(),
              AsignadorUbicaciones.sinUbicar(agregado.paletas(), posiciones),
              agregado.bultos(),
              porEnvio));
    }
    List<DashboardSanta.Envio> envios = new ArrayList<>(AsignadorUbicaciones.ENVIOS);
    int bultosTotal = 0;
    for (int e = 1; e <= AsignadorUbicaciones.ENVIOS; e++) {
      envios.add(new DashboardSanta.Envio(e, bultosEnvio[e - 1]));
      bultosTotal += bultosEnvio[e - 1];
    }
    return new DashboardSanta(
        registro.getId(),
        registro.getNombre(),
        registro.getEnvioActual(),
        registro.getOperarios(),
        registro.getBultosHora(),
        bloqueado,
        bultosTotal,
        paletasTotal,
        envios,
        clusters);
  }
}
