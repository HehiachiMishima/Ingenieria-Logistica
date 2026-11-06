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
 * Los services abren la transaccion con este helper (en lugar de @Transactional)
 * para que cubra exactamente el trabajo medido con Trazas.medir: las lecturas y
 * la escritura de una operacion comparten la misma transaccion (evita ventanas
 * TOCTOU inconsistentes) y el armado de la respuesta queda fuera de ella.
 *
 * Las lecturas puramente de solo consulta usan consultar() (readOnly=true).
 * Los efectos fuera de la BD (invalidar caches) se registran con alConfirmar()
 * para que ocurran solo despues del commit.
 */
@Component
public class Transacciones {

    private final TransactionTemplate escritura;
    private final TransactionTemplate lectura;
    private final PlatformTransactionManager gestor;

    public Transacciones(PlatformTransactionManager gestor) {
        this.gestor = gestor;
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
     * Lectura con tope TOTAL en segundos: Spring aplica a cada sentencia JDBC/JPA
     * el tiempo que le queda a la transacción, así una consulta de varias
     * sentencias no supera el tope aunque cada una quede bajo statement_timeout.
     */
    public <T> T consultar(Supplier<T> trabajo, int topeSegundos) {
        TransactionTemplate conTope = new TransactionTemplate(gestor, lectura);
        conTope.setTimeout(topeSegundos);
        return conTope.execute(estado -> trabajo.get());
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
