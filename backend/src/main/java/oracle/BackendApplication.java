package oracle;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BackendApplication {

	/**
	 * La JVM trabaja en UTC sin importar el servidor donde corra: asi Hibernate
	 * no desplaza las horas de negocio (TIME) y la auditoria queda en UTC. La
	 * zona del negocio se declara aparte (app.zona-horaria, ver ZonaNegocio).
	 */
	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		usarHilosVirtualesEnBoundedElastic();
		SpringApplication.run(BackendApplication.class, args);
	}

	/**
	 * Los servicios envuelven su trabajo JDBC en Mono.subscribeOn(boundedElastic).
	 * Por defecto ese scheduler usa hilos de plataforma con tope de 10 x CPU (unos
	 * 10 en el plan free de Render): con más peticiones a la vez hacían cola
	 * aunque Hikari tuviera conexiones libres. Con hilos virtuales el límite real
	 * es el pool de conexiones. Reactor lee la propiedad al cargar Schedulers, por
	 * eso va antes de arrancar Spring; un -D en la línea de comandos la sobrescribe.
	 */
	static void usarHilosVirtualesEnBoundedElastic() {
		if (System.getProperty(PROPIEDAD_BOUNDED_ELASTIC_VIRTUAL) == null) {
			System.setProperty(PROPIEDAD_BOUNDED_ELASTIC_VIRTUAL, "true");
		}
	}

	static final String PROPIEDAD_BOUNDED_ELASTIC_VIRTUAL =
			"reactor.schedulers.defaultBoundedElasticOnVirtualThreads";

}
