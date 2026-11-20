import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { PermisoDelRol, RolPermisosLote, RolPermisosLoteRequest } from './permiso.model';
import { Rol, RolActualizarRequest, RolCrearRequest } from './rol.model';
import { Api } from '../../../../core/services/api';

@Service()
export class RolesApi {
  private readonly api = inject(Api);

  listar(): Observable<Rol[]> {
    return this.api.get<Rol[]>('/roles');
  }

  crear(peticion: RolCrearRequest): Observable<Rol> {
    return this.api.post<Rol>('/roles', peticion);
  }

  actualizar(id: number, peticion: RolActualizarRequest): Observable<Rol> {
    return this.api.put<Rol>(`/roles/${id}`, peticion);
  }

  eliminar(id: number): Observable<void> {
    return this.api.delete<void>(`/roles/${id}`);
  }

  cambiarActivo(id: number, activo: boolean): Observable<Rol> {
    return this.api.patch<Rol>(`/roles/${id}/activo`, { activo });
  }

  /** Catálogo completo con lo que el rol tiene y en cuántos roles está cada permiso. */
  tableroPermisos(id: number): Observable<PermisoDelRol[]> {
    return this.api.get<PermisoDelRol[]>(`/roles/${id}/permisos/tablero`);
  }

  /** Agrega y quita varios permisos del rol en una sola transacción. */
  actualizarPermisos(id: number, cambios: RolPermisosLoteRequest): Observable<RolPermisosLote> {
    return this.api.patch<RolPermisosLote>(`/roles/${id}/permisos`, cambios);
  }
}
