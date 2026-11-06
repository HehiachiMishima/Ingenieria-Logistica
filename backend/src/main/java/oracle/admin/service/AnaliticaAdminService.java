
package oracle.admin.service;

import java.util.List;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import oracle.admin.repository.AnaliticaAdminRepository;
import oracle.admin.response.AnaliticaAdminResponse;
import oracle.common.config.CacheRespuestas;
import oracle.common.response.ApiResponse;
import oracle.common.support.EjecutorAnalitica;
import oracle.common.support.Trazas;
import oracle.admin.response.AnaliticaAdminResponse.Conteo;
import oracle.admin.response.AnaliticaAdminResponse.Kpis;
import oracle.admin.response.AnaliticaAdminResponse.Mes;
import org.springframework.stereotype.Service;

/** Dashboard ejecutivo de Administracion: 4 agregaciones en paralelo, cacheadas (TTL). */
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

    public ApiResponse<AnaliticaAdminResponse> analitica() {
        AnaliticaAdminResponse datos = Trazas.medir(log, "AnaliticaAdminService.analitica", () ->
                cacheRespuestas.obtener(CLAVE, this::calcular));
        return ApiResponse.ok("Analitica calculada.", datos);
    }

    private AnaliticaAdminResponse calcular() {
        try (EjecutorAnalitica.Lote lote = ejecutor.lote()) {
            Future<Kpis> kpis = lote.consulta(repo::kpis);
            Future<List<Conteo>> cobertura = lote.consulta(repo::coberturaPaneles);
            Future<List<Conteo>> porRol = lote.consulta(repo::usuariosPorRol);
            Future<List<Mes>> crecimiento = lote.consulta(repo::crecimiento);
            return new AnaliticaAdminResponse(
                    lote.esperar(kpis), lote.esperar(cobertura), lote.esperar(porRol), lote.esperar(crecimiento));
        }
    }
}
