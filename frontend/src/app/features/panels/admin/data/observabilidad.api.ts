import { HttpContext } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Api } from '../../../../core/services/api';
import { PETICION_SILENCIOSA } from '../../../../core/interceptors/error.interceptor';
import { PaginaRemota } from '../../../../core/utils/listado-remoto';
import {
  EndpointMonitoreo,
  Evento,
  FiltroPeticiones,
  FiltrosAuditoria,
  Periodo,
  ResumenActividad,
  ResumenAuditoria,
  ResumenMonitoreo,
} from './observabilidad.model';

/**
 * Consultas de Monitoreo, Actividad y Auditoría. Son silenciosas: cada pantalla
 * muestra el error en su lugar en vez de un aviso por petición. Los listados son
 * páginas numeradas con total (PaginaResponse), como el resto de tablas del sistema.
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

  /** Peticiones de una API; «lentas» = más lentas que su p95 (el backend recibe ese umbral en ms). */
  peticiones(
    dias: Periodo,
    endpoint: Pick<EndpointMonitoreo, 'metodo' | 'ruta' | 'p95'>,
    filtro: FiltroPeticiones,
    pagina: number,
    tamano: number,
  ): Observable<PaginaRemota<Evento>> {
    return this.api.get<PaginaRemota<Evento>>(
      '/admin/monitoreo/peticiones',
      {
        dias,
        metodo: endpoint.metodo,
        ruta: endpoint.ruta,
        errores: filtro === 'errores' || undefined,
        lentas: filtro === 'lentas' ? endpoint.p95 : undefined,
        pagina,
        tamano,
      },
      this.silenciosa,
    );
  }

  resumenActividad(dias: Periodo, usuario: string): Observable<ResumenActividad> {
    return this.api.get<ResumenActividad>('/admin/actividad/resumen', { dias, usuario }, this.silenciosa);
  }

  /** Con {@code soloCambios}, solo las peticiones que cambiaron datos (con evento de auditoría). */
  actividad(
    dias: Periodo,
    usuario: string,
    soloCambios: boolean,
    pagina: number,
    tamano: number,
  ): Observable<PaginaRemota<Evento>> {
    return this.api.get<PaginaRemota<Evento>>(
      '/admin/actividad',
      { dias, usuario, cambios: soloCambios || null, pagina, tamano },
      this.silenciosa,
    );
  }

  resumenAuditoria(f: FiltrosAuditoria): Observable<ResumenAuditoria> {
    return this.api.get<ResumenAuditoria>(
      '/admin/auditoria/resumen',
      { dias: f.dias, usuario: f.usuario, registro: f.registro },
      this.silenciosa,
    );
  }

  auditoria(f: FiltrosAuditoria, pagina: number, tamano: number): Observable<PaginaRemota<Evento>> {
    return this.api.get<PaginaRemota<Evento>>('/admin/auditoria', { ...f, pagina, tamano }, this.silenciosa);
  }
}
