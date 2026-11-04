package oracle.common.config;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ejecuta bloques de trabajo dentro de una transaccion.
 *
 * Los services devuelven Mono y ejecutan su cuerpo en Schedulers.boundedElastic,
 * por lo que @Transactional en el metodo publico no abarcaria el trabajo real
 * (solo la construccion del Mono). Este helper permite abrir la transaccion
 * DENTRO del hilo de trabajo, de modo que las lecturas + escritura de una
 * operacion compartan la misma transaccion (evita ventanas TOCTOU inconsistentes).
 *
 * Las lecturas puramente de solo consulta usan consultar() (readOnly=true).
 * Los efectos fuera de la BD (invalidar caches) se registran con alConfirmar()
 * para que ocurran solo despues del commit.
 */
@Component
public class Transacciones {

    private final TransactionTemplate escritura;
    private final TransactionTemplate lectura;

    public Transacciones(PlatformTransactionManager gestor) {
        this.escritura = new TransactionTemplate(gestor);
        this.lectura = new TransactionTemplate(gestor);
        this.lectura.setReadOnly(true);
    }

    public <T> T ejecutar(Supplier<T> trabajo) {
        return escritura.execute(estado -> trabajo.get());
    }

    public <T> T consultar(Supplier<T> trabajo) {
        return lectura.execute(estado -> trabajo.get());
    }

    /**
     * Ejecuta {@code accion} cuando la transaccion en curso confirma (commit).
     * Si la transaccion hace rollback no se ejecuta; si no hay transaccion
     * activa se ejecuta de inmediato.
     *
     * Se usa para invalidar caches: invalidar antes del commit deja una ventana
     * en la que otro hilo recarga los datos viejos y los vuelve a cachear.
     */
    public void alConfirmar(Runnable accion) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            accion.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                accion.run();
            }
        });
    }
}
