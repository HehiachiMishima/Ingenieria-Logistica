package oracle.simulacion.service;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import oracle.common.config.Transacciones;
import oracle.common.response.ApiResponse;
import oracle.common.support.ActorActual;
import oracle.common.support.Trazas;
import oracle.simulacion.repository.ArchivoExcelRepository;
import oracle.simulacion.response.ArchivoSimulacionResponse;
import org.springframework.stereotype.Service;

/**
 * El registro con el que trabajan Configuración y el Dashboard. Ya no se suben Excel: hay un solo
 * escenario, donde las rutas se crean en DATA y se ordenan en los clusters. Se crea la primera vez
 * que alguien lo pide.
 */
@Slf4j
@Service
public class SimulacionRegistroService {

  private final ArchivoExcelRepository archivoRepo;
  private final Transacciones transacciones;
  private final EscenarioUnico escenario;

  public SimulacionRegistroService(
      ArchivoExcelRepository archivoRepo, Transacciones transacciones, EscenarioUnico escenario) {
    this.archivoRepo = archivoRepo;
    this.transacciones = transacciones;
    this.escenario = escenario;
  }

  /** El escenario único (en una lista, como esperan Configuración y el Dashboard). */
  public ApiResponse<List<ArchivoSimulacionResponse>> opciones() {
    var datos =
        Trazas.medir(
            log,
            "SimulacionRegistroService.opciones",
            () -> {
              Long id = transacciones.consultar(escenario::buscar);
              if (id == null) {
                Long actor = ActorActual.id();
                id = transacciones.ejecutar(() -> escenario.asegurar(actor));
              }
              Long escenarioId = id;
              return transacciones.consultar(
                  () ->
                      archivoRepo.findById(escenarioId).stream()
                          .map(SimulacionRespuestas::archivo)
                          .toList());
            });
    return ApiResponse.ok("Escenario de simulación.", datos);
  }
}
