import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Area,
  AreaActualizarRequest,
  AreaCrearRequest,
  Responsable,
  ResponsableActualizarRequest,
  ResponsableCrearRequest,
} from './bitacora.model';
import { Api } from '../../../../core/services/api';

@Service()
export class AreasApi {
  private readonly api = inject(Api);

  listar(): Observable<Area[]> {
    return this.api.get<Area[]>('/areas');
  }

  crear(peticion: AreaCrearRequest): Observable<Area> {
    return this.api.post<Area>('/areas', peticion);
  }

  actualizar(id: number, peticion: AreaActualizarRequest): Observable<Area> {
    return this.api.put<Area>(`/areas/${id}`, peticion);
  }

  eliminar(id: number): Observable<void> {
    return this.api.delete<void>(`/areas/${id}`);
  }

  responsablesDe(areaId: number): Observable<Responsable[]> {
    return this.api.get<Responsable[]>(`/areas/${areaId}/responsables`);
  }

  crearResponsable(areaId: number, peticion: ResponsableCrearRequest): Observable<Responsable> {
    return this.api.post<Responsable>(`/areas/${areaId}/responsables`, peticion);
  }

  actualizarResponsable(
    areaId: number,
    responsableId: number,
    peticion: ResponsableActualizarRequest,
  ): Observable<Responsable> {
    return this.api.put<Responsable>(`/areas/${areaId}/responsables/${responsableId}`, peticion);
  }

  eliminarResponsable(areaId: number, responsableId: number): Observable<void> {
    return this.api.delete<void>(`/areas/${areaId}/responsables/${responsableId}`);
  }
}
