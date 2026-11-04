import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AnaliticaBitacora } from './analitica.model';
import {
  Incidencia,
  IncidenciaActualizarRequest,
  IncidenciaCrearRequest,
  IncidenciaFiltros,
} from './bitacora.model';
import { Pagina } from '../../../../core/models';
import { Api } from '../../../../core/services/api';

@Service()
export class IncidenciasApi {
  private readonly api = inject(Api);

  listar(filtros: IncidenciaFiltros = {}, pagina = 1, tamano = 10): Observable<Pagina<Incidencia>> {
    return this.api.get<Pagina<Incidencia>>('/incidencias', {
      ...filtros,
      pagina,
      tamano,
    } as Record<string, unknown>);
  }

  exportar(filtros: IncidenciaFiltros = {}): Observable<Incidencia[]> {
    return this.api.get<Incidencia[]>('/incidencias/exportar', filtros as Record<string, unknown>);
  }

  /** Analítica completa del dashboard (KPIs, rankings y cruces). */
  analitica(filtros: IncidenciaFiltros = {}): Observable<AnaliticaBitacora> {
    return this.api.get<AnaliticaBitacora>(
      '/incidencias/analitica',
      filtros as Record<string, unknown>,
    );
  }

  crear(peticion: IncidenciaCrearRequest): Observable<Incidencia> {
    return this.api.post<Incidencia>('/incidencias', peticion);
  }

  actualizar(id: number, peticion: IncidenciaActualizarRequest): Observable<Incidencia> {
    return this.api.put<Incidencia>(`/incidencias/${id}`, peticion);
  }

  eliminar(id: number): Observable<void> {
    return this.api.delete<void>(`/incidencias/${id}`);
  }
}
