package oracle.common.response;

/** Punto de una serie temporal: periodo (yyyy-MM-dd o yyyy-MM), valor y valor secundario. */
public record PuntoSerie(String periodo, long valor, long secundario) {
}
