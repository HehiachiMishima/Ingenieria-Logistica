package oracle.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import oracle.common.exception.LimiteExcedidoException;
import oracle.common.support.ActorActual;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Límite de uso por usuario de un panel (mismo patrón que LimiteUsoSimulacion):
 * cada petición cuenta en el cubo de su categoría y, al pasarse, responde 429
 * con Retry-After. Cada panel lo registra en sus rutas con sus propios cupos.
 *
 * Las categorías son cubos independientes (ventana de 1 minuto), así una ráfaga
 * de exportaciones no bloquea los listados ni el guardado:
 * <ul>
 *   <li>EXPORTACION: GET que termina en /exportar (recorre todo el filtro).</li>
 *   <li>ANALITICA: GET de dashboards (/analitica) o resúmenes globales (/resumen).</li>
 *   <li>ESCRITURA: cualquier método distinto de GET.</li>
 *   <li>LECTURA: el resto de GET (listados, catálogos, detalle).</li>
 * </ul>
 */
public class LimiteUsoPanel implements HandlerInterceptor {

    public enum Categoria { LECTURA, ESCRITURA, ANALITICA, EXPORTACION }

    /** Peticiones admitidas por usuario y minuto en cada categoría. */
    public record Cupos(int lecturas, int escrituras, int analiticas, int exportaciones) {

        int de(Categoria categoria) {
            return switch (categoria) {
                case LECTURA -> lecturas;
                case ESCRITURA -> escrituras;
                case ANALITICA -> analiticas;
                case EXPORTACION -> exportaciones;
            };
        }
    }

    static final long VENTANA_MS = 60_000L;

    private final LimiteSolicitudes limite;
    private final String panel;
    private final Cupos cupos;

    public LimiteUsoPanel(LimiteSolicitudes limite, String panel, Cupos cupos) {
        this.limite = limite;
        this.panel = panel;
        this.cupos = cupos;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Categoria categoria = categoria(request.getMethod(), request.getRequestURI());
        long espera = limite.consumir(
                panel + ":" + categoria.name().toLowerCase() + ":" + ActorActual.id(),
                cupos.de(categoria),
                VENTANA_MS);
        if (espera == 0) {
            return true;
        }
        long segundos = Math.max(1, (espera + 999) / 1000);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(segundos));
        throw new LimiteExcedidoException(
                "Demasiadas solicitudes seguidas. Intenta de nuevo en " + segundos + " s.");
    }

    static Categoria categoria(String metodo, String ruta) {
        if (!HttpMethod.GET.matches(metodo)) {
            return Categoria.ESCRITURA;
        }
        if (ruta.endsWith("/exportar")) {
            return Categoria.EXPORTACION;
        }
        if (ruta.endsWith("/analitica") || ruta.endsWith("/resumen")) {
            return Categoria.ANALITICA;
        }
        return Categoria.LECTURA;
    }
}
