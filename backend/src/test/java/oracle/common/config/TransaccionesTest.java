package oracle.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** alConfirmar() debe diferir los efectos hasta el commit y descartarlos en rollback. */
class TransaccionesTest {

    private final List<String> eventos = new ArrayList<>();
    private final Transacciones transacciones = new Transacciones(new GestorEnMemoria(eventos));

    @Test
    void sinTransaccionActivaSeEjecutaDeInmediato() {
        transacciones.alConfirmar(() -> eventos.add("invalidar"));

        assertThat(eventos).containsExactly("invalidar");
    }

    @Test
    void dentroDeEjecutarEsperaAlCommit() {
        transacciones.ejecutar(() -> {
            transacciones.alConfirmar(() -> eventos.add("invalidar"));
            eventos.add("fin del trabajo");
            return null;
        });

        assertThat(eventos).containsExactly("fin del trabajo", "commit", "invalidar");
    }

    @Test
    void siHayRollbackNoSeEjecuta() {
        assertThatThrownBy(() -> transacciones.ejecutar(() -> {
            transacciones.alConfirmar(() -> eventos.add("invalidar"));
            throw new IllegalStateException("conflicto");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(eventos).containsExactly("rollback");
    }

    /** Gestor transaccional sin recursos que solo anota commit/rollback. */
    private static final class GestorEnMemoria extends AbstractPlatformTransactionManager {

        private final List<String> eventos;

        GestorEnMemoria(List<String> eventos) {
            this.eventos = eventos;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaccion, TransactionDefinition definicion) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus estado) {
            eventos.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus estado) {
            eventos.add("rollback");
        }
    }
}
