import { HttpContext } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Api } from '../../../../core/services/api';
import { PETICION_SILENCIOSA } from '../../../../core/interceptors/error.interceptor';
import {
  EndpointMonitoreo,
  Evento,
  FiltrosAuditoria,
  PaginaCursor,
  Periodo,
  ResumenActividad,
  ResumenAuditoria,
  ResumenMonitoreo,
} from './observabilidad.model';

/**
 * Consultas de Monitoreo, Actividad y Auditoría. Son silenciosas: cada pantalla
 * muestra el error en su lugar en vez de un aviso por petición.
 */
@Service()
export class ObservabilidadApi {
  private readonly api = inject(Api);
  private readonly silenciosa = new HttpContext().set(PETICION_SILENCIOSA, true);

  resumenMonitoreo(dias: Periodo): Observable<ResumenMonitoreo> {
    return this.api.get<ResumenMonitoreo>('/admin/monitoreo/resumen', { dias }, this.silenciosa);
  }

  endpoints(dias: Periodo): Observable<EndpointMonitoreo[]> {
    return this.api.get<EndpointMonitoreo[]>('/admin/monitoreo', { dias }, this.silenciosa);
  }

  peticiones(
    dias: Periodo,
    endpoint: Pick<EndpointMonitoreo, 'metodo' | 'ruta'>,
    soloErrores: boolean,
    antes: number | null,
  ): Observable<PaginaCursor<Evento>> {
    return this.api.get<PaginaCursor<Evento>>(
      '/admin/monitoreo/peticiones',
      { dias, metodo: endpoint.metodo, ruta: endpoint.ruta, errores: soloErrores || undefined, antes },
      this.silenciosa,
    );
  }

  resumenActividad(dias: Periodo, usuario: string): Observable<ResumenActividad> {
    return this.api.get<ResumenActividad>('/admin/actividad/resumen', { dias, usuario }, this.silenciosa);
  }

  actividad(dias: Periodo, usuario: string, antes: number | null): Observable<PaginaCursor<Evento>> {
    return this.api.get<PaginaCursor<Evento>>('/admin/actividad', { dias, usuario, antes }, this.silenciosa);
  }

  resumenAuditoria(f: FiltrosAuditoria): Observable<ResumenAuditoria> {
    return this.api.get<ResumenAuditoria>(
      '/admin/auditoria/resumen',
      { dias: f.dias, usuario: f.usuario, registro: f.registro },
      this.silenciosa,
    );
  }

  auditoria(f: FiltrosAuditoria, antes: number | null): Observable<PaginaCursor<Evento>> {
    return this.api.get<PaginaCursor<Evento>>('/admin/auditoria', { ...f, antes }, this.silenciosa);
  }
}
