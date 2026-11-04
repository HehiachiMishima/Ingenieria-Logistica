package oracle.common.support;

import java.util.function.Supplier;
import oracle.common.config.CacheRespuestas;
import oracle.common.config.Transacciones;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Base de las caches de nombres de catalogo de un panel (p. ej. Bitacora).
 *
 * Guarda un snapshot inmutable de los catalogos y centraliza su ciclo de
 * vida: recarga programada y la invalidacion tras cada CRUD, que se difiere al
 * commit para que ninguna lectura concurrente vuelva a cachear datos viejos.
 */
public abstract class CatalogoCacheado<S> {

    private final SnapshotCache<S> cache;
    private final Transacciones transacciones;
    private final CacheRespuestas cacheRespuestas;
    private final String espacioRespuestas;

    /**
     * @param espacioRespuestas espacio de {@link CacheRespuestas} con los agregados
     *        del panel que muestran nombres de este catalogo (se invalida con el).
     */
    protected CatalogoCacheado(String nombre, Supplier<S> cargador,
            Transacciones transacciones, CacheRespuestas cacheRespuestas, String espacioRespuestas) {
        this.cache = new SnapshotCache<>(nombre, cargador);
        this.transacciones = transacciones;
        this.cacheRespuestas = cacheRespuestas;
        this.espacioRespuestas = espacioRespuestas;
    }

    /** Snapshot vigente (lo carga si fue invalidado). */
    protected S snapshot() {
        return cache.obtener();
    }

    /**
     * Un cambio de catalogo invalida los nombres cacheados y los agregados del
     * dashboard de su panel (no los de otros paneles), una vez confirmada la
     * transaccion en curso.
     */
    public void registrarCambio() {
        transacciones.alConfirmar(() -> {
            cache.invalidar();
            cacheRespuestas.invalidarEspacio(espacioRespuestas);
        });
    }

    @Scheduled(
            fixedDelayString = "${app.catalogo.cache.refresh-ms:300000}",
            initialDelayString = "${app.catalogo.cache.refresh-ms:300000}")
    public void refrescar() {
        cache.refrescar();
    }
}
