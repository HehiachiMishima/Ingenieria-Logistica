package oracle.simulacion.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.simulacion.request.DataRequest;

/**
 * Una ruta de DATA lista para guardar: número y códigos limpios (sin espacios de más y en
 * mayúsculas: « f045 » y «F045» son el mismo) y los códigos repetidos sumados, de modo que cada
 * local queda una vez con {@code filas} > 1. Los totales de la ruta son la suma de sus locales.
 */
record RutaNormalizada(String codigo, List<RutaNormalizada.Local> locales, int sumados) {

  /** Lo más que cabe en una columna NUMERIC(12,2). */
  static final BigDecimal MAXIMO = new BigDecimal("9999999999.99");

  /** Un local ya sumado. */
  record Local(String cod, int filas, BigDecimal bultos, BigDecimal unidades, BigDecimal lineas) {}

  static RutaNormalizada de(DataRequest.Ruta req) {
    String codigo = texto(req.codigoRuta());
    if (codigo.isEmpty()) {
      throw new SolicitudInvalidaException("Escribe el número de ruta.");
    }
    Map<String, Acumulado> porCodigo = new LinkedHashMap<>();
    int linea = 0;
    for (var local : req.locales()) {
      linea++;
      String cod = texto(local.cod());
      if (cod.isEmpty()) {
        throw new SolicitudInvalidaException("La línea " + linea + " no tiene código.");
      }
      porCodigo.computeIfAbsent(cod, Acumulado::new).sumar(local);
    }
    List<Local> locales = new ArrayList<>(porCodigo.size());
    for (Acumulado a : porCodigo.values()) {
      if (a.bultos.compareTo(MAXIMO) > 0 || a.unidades.compareTo(MAXIMO) > 0 || a.lineas.compareTo(MAXIMO) > 0) {
        throw new SolicitudInvalidaException(
            "El código " + a.cod + " suma más de 9,999,999,999.99: revisa sus líneas.");
      }
      locales.add(new Local(a.cod, a.filas, a.bultos, a.unidades, a.lineas));
    }
    RutaNormalizada ruta = new RutaNormalizada(codigo, List.copyOf(locales), req.locales().size() - locales.size());
    if (ruta.bultos().compareTo(MAXIMO) > 0 || ruta.unidades().compareTo(MAXIMO) > 0 || ruta.lineas().compareTo(MAXIMO) > 0) {
      throw new SolicitudInvalidaException("La ruta suma más de 9,999,999,999.99: revisa sus códigos.");
    }
    return ruta;
  }

  BigDecimal bultos() {
    return locales.stream().map(Local::bultos).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  BigDecimal unidades() {
    return locales.stream().map(Local::unidades).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  BigDecimal lineas() {
    return locales.stream().map(Local::lineas).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** Sin espacios al borde ni dobles, en mayúsculas. */
  static String texto(String valor) {
    return valor == null ? "" : valor.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
  }

  private static final class Acumulado {
    private final String cod;
    private int filas;
    private BigDecimal bultos = BigDecimal.ZERO.setScale(2);
    private BigDecimal unidades = BigDecimal.ZERO.setScale(2);
    private BigDecimal lineas = BigDecimal.ZERO.setScale(2);

    Acumulado(String cod) {
      this.cod = cod;
    }

    void sumar(DataRequest.Local local) {
      filas += local.filas() == null ? 1 : local.filas();
      bultos = bultos.add(local.bultos());
      unidades = unidades.add(local.unidades());
      lineas = lineas.add(local.lineas());
    }
  }
}
