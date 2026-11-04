import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Sistema,
  SistemaActualizarRequest,
  SistemaCrearRequest,
  TipoIncidencia,
  TipoIncidenciaActualizarRequest,
  TipoIncidenciaCrearRequest,
} from './bitacora.model';
import { Api } from '../../../../core/services/api';

@Service()
export class SistemasApi {
  private readonly api = inject(Api);

  listar(): Observable<Sistema[]> {
    return this.api.get<Sistema[]>('/sistemas');
  }

  crear(peticion: SistemaCrearRequest): Observable<Sistema> {
    return this.api.post<Sistema>('/sistemas', peticion);
  }

  actualizar(id: number, peticion: SistemaActualizarRequest): Observable<Sistema> {
    return this.api.put<Sistema>(`/sistemas/${id}`, peticion);
  }

  eliminar(id: number): Observable<void> {
    return this.api.delete<void>(`/sistemas/${id}`);
  }

  tiposDe(sistemaId: number): Observable<TipoIncidencia[]> {
    return this.api.get<TipoIncidencia[]>(`/sistemas/${sistemaId}/tipos-incidencia`);
  }

  crearTipo(sistemaId: number, peticion: TipoIncidenciaCrearRequest): Observable<TipoIncidencia> {
    return this.api.post<TipoIncidencia>(`/sistemas/${sistemaId}/tipos-incidencia`, peticion);
  }

  actualizarTipo(
    sistemaId: number,
    tipoId: number,
    peticion: TipoIncidenciaActualizarRequest,
  ): Observable<TipoIncidencia> {
    return this.api.put<TipoIncidencia>(
      `/sistemas/${sistemaId}/tipos-incidencia/${tipoId}`,
      peticion,
    );
  }

  eliminarTipo(sistemaId: number, tipoId: number): Observable<void> {
    return this.api.delete<void>(`/sistemas/${sistemaId}/tipos-incidencia/${tipoId}`);
  }
}
