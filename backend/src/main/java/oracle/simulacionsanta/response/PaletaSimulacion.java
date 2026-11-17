package oracle.simulacionsanta.response;

import java.util.List;
import oracle.simulacionsanta.model.EstadoPaleta;

/** Una paleta en el envío mostrado; ubicacion null = sin ubicación (no cupo en su cluster). */
public record PaletaSimulacion(
    Long id,
    String ruta,
    String local,
    int cluster,
    List<Integer> envios,
    int recibidos,
    int total,
    EstadoPaleta estado,
    Ubicacion ubicacion) {}
