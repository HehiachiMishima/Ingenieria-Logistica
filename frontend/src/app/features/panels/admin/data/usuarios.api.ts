import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { PermisoEfectivo, UsuarioPermisoDetalle, UsuarioPermisoOverride } from './permiso.model';
import { Rol } from './rol.model';
import { Usuario } from '../../../../core/models';
import { UsuarioActualizarRequest, UsuarioCrearRequest, UsuarioResumen } from './usuario.model';
import { Api } from '../../../../core/services/api';

@Service()
export class UsuariosApi {
  private readonly api = inject(Api);

  listar(): Observable<Usuario[]> {
    return this.api.get<Usuario[]>('/usuarios');
  }

  /** Roles y permisos efectivos de todos los usuarios en una sola petición. */
  resumen(): Observable<UsuarioResumen[]> {
    return this.api.get<UsuarioResumen[]>('/usuarios/resumen');
  }

  crear(peticion: UsuarioCrearRequest): Observable<Usuario> {
    return this.api.post<Usuario>('/usuarios', peticion);
  }

  actualizar(id: number, peticion: UsuarioActualizarRequest): Observable<Usuario> {
    return this.api.put<Usuario>(`/usuarios/${id}`, peticion);
  }

  eliminar(id: number): Observable<void> {
    return this.api.delete<void>(`/usuarios/${id}`);
  }

  cambiarActivo(id: number, activo: boolean): Observable<Usuario> {
    return this.api.patch<Usuario>(`/usuarios/${id}/activo`, { activo });
  }

  roles(id: number): Observable<Rol[]> {
    return this.api.get<Rol[]>(`/usuarios/${id}/roles`);
  }

  asignarRol(id: number, rolId: number): Observable<unknown> {
    return this.api.post(`/usuarios/${id}/roles`, { rolId });
  }

  quitarRol(id: number, rolId: number): Observable<void> {
    return this.api.delete<void>(`/usuarios/${id}/roles/${rolId}`);
  }

  /** Overrides individuales (grant/deny) del usuario. */
  permisos(id: number): Observable<UsuarioPermisoDetalle[]> {
    return this.api.get<UsuarioPermisoDetalle[]>(`/usuarios/${id}/permisos`);
  }

  /** Concede (otorgado=true) o deniega (otorgado=false) un permiso al usuario. */
  otorgarPermiso(id: number, override: UsuarioPermisoOverride): Observable<UsuarioPermisoDetalle> {
    return this.api.put<UsuarioPermisoDetalle>(`/usuarios/${id}/permisos`, override);
  }

  /** Elimina el override; el permiso vuelve a heredarse del rol. */
  quitarPermiso(id: number, permisoId: number): Observable<void> {
    return this.api.delete<void>(`/usuarios/${id}/permisos/${permisoId}`);
  }

  /** Resolución: aportes del rol y de los overrides, antes de aplicar MIN(). */
  resolucionEfectivos(id: number): Observable<PermisoEfectivo[]> {
    return this.api.get<PermisoEfectivo[]>(`/resolucion/usuarios/${id}/efectivos`);
  }
}
