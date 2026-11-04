
package oracle.admin.service;

import lombok.extern.slf4j.Slf4j;
import oracle.admin.repository.AnaliticaAdminRepository;
import oracle.admin.response.AnaliticaAdminResponse;
import oracle.common.config.CacheRespuestas;
import oracle.common.response.ApiResponse;
import oracle.common.support.EjecutorAnalitica;
import oracle.common.support.Trazas;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Dashboard ejecutivo de Administracion: 2 agregaciones en paralelo, cacheadas (TTL). */
@Slf4j
@Service
public class AnaliticaAdminService {

    private static final String CLAVE = "admin-analitica";

    private final AnaliticaAdminRepository repo;
    private final EjecutorAnalitica ejecutor;
    private final CacheRespuestas cacheRespuestas;

    public AnaliticaAdminService(AnaliticaAdminRepository repo, EjecutorAnalitica ejecutor,
            CacheRespuestas cacheRespuestas) {
        this.repo = repo;
        this.ejecutor = ejecutor;
        this.cacheRespuestas = cacheRespuestas;
    }

    public Mono<ApiResponse<AnaliticaAdminResponse>> analitica() {
        return Mono.fromCallable(() -> Trazas.medir(log, "AnaliticaAdminService.analitica", () ->
                        cacheRespuestas.obtener(CLAVE, () -> ejecutor.esperar(calcular()))))
                .subscribeOn(Schedulers.boundedElastic())
                .map(datos -> ApiResponse.ok("Analitica calculada.", datos));
    }

    private Mono<AnaliticaAdminResponse> calcular() {
        return Mono.zip(ejecutor.consulta(repo::kpis), ejecutor.consulta(repo::coberturaPaneles))
                .map(r -> new AnaliticaAdminResponse(r.getT1(), r.getT2()));
    }
}
