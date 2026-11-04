package oracle.simulacion.service;

import oracle.simulacion.model.ArchivoExcel;
import oracle.simulacion.response.ArchivoSimulacionResponse;

/** Mapeo de entidades del panel a respuestas, compartido por los servicios. */
final class SimulacionRespuestas {

  private SimulacionRespuestas() {}

  /** Registro con los totales de su Excel (guardados al cargarlo). */
  static ArchivoSimulacionResponse archivo(ArchivoExcel archivo) {
    return new ArchivoSimulacionResponse(
        archivo.getId(),
        archivo.getNombreRegistro(),
        archivo.getNombreArchivo(),
        archivo.getEtiqueta(),
        archivo.getColorHex(),
        archivo.getEstado(),
        archivo.getTotalRutas(),
        archivo.getTotalChutes(),
        archivo.getTotalBultos(),
        archivo.getTotalUnidades(),
        archivo.getTotalLineas(),
        archivo.getTotalCod(),
        archivo.getCreadoEn(),
        archivo.getActualizadoEn(),
        archivo.getCreadoPor(),
        archivo.getActualizadoPor());
  }
}
