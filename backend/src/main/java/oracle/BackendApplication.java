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
		SpringApplication.run(BackendApplication.class, args);
	}

}
