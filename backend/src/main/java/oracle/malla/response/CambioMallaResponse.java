package oracle.malla.response;

import java.time.Instant;

/** Un cambio en la malla de un centro: qué se hizo, con qué resultado, quién y cuándo. */
public record CambioMallaResponse(String accion, String resumen, Long creadoPor, Instant creadoEn) {
}
