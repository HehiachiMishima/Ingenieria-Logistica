import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Permiso,
  PermisoActualizarRequest,
  PermisoCrearRequest,
  PermisoRolesLote,
  PermisoRolesLoteRequest,
  RolesPermisoTablero,
} from './permiso.model';
import { Api } from '../../../../core/services/api';

@Service()
export class PermisosApi {
  private readonly api = inject(Api);

  listar(): Observable<Permiso[]> {
    return this.api.get<Permiso[]>('/permisos');
  }

  crear(peticion: PermisoCrearRequest): Observable<Permiso> {
    return this.api.post<Permiso>('/permisos', peticion);
  }

  actualizar(id: number, peticion: PermisoActualizarRequest): Observable<Permiso> {
    return this.api.put<Permiso>(`/permisos/${id}`, peticion);
  }

  eliminar(id: number): Observable<void> {
    return this.api.delete<void>(`/permisos/${id}`);
  }

  cambiarActivo(id: number, activo: boolean): Observable<Permiso> {
    return this.api.patch<Permiso>(`/permisos/${id}/activo`, { activo });
  }

  /** Catálogo de roles con los que ya tienen el permiso y a cuántos usuarios llega. */
  tableroRoles(id: number): Observable<RolesPermisoTablero> {
    return this.api.get<RolesPermisoTablero>(`/permisos/${id}/roles/tablero`);
  }

  /** Da o quita el permiso a varios roles en una sola transacción. */
  actualizarRoles(id: number, cambios: PermisoRolesLoteRequest): Observable<PermisoRolesLote> {
    return this.api.patch<PermisoRolesLote>(`/permisos/${id}/roles`, cambios);
  }
}
