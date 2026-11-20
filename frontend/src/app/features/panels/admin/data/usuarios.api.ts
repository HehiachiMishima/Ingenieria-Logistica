import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { PermisosUsuarioTablero, UsuarioPermisosLote, UsuarioPermisosLoteRequest } from './permiso.model';
import { RolesUsuarioTablero, UsuarioRolesLote, UsuarioRolesLoteRequest } from './rol.model';
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

  /** Catálogo de roles con lo que el usuario tiene, su uso, sus paneles y si se pueden otorgar. */
  tableroRoles(id: number): Observable<RolesUsuarioTablero> {
    return this.api.get<RolesUsuarioTablero>(`/usuarios/${id}/roles/tablero`);
  }

  /** Agrega y quita varios roles del usuario en una sola transacción. */
  actualizarRoles(id: number, cambios: UsuarioRolesLoteRequest): Observable<UsuarioRolesLote> {
    return this.api.patch<UsuarioRolesLote>(`/usuarios/${id}/roles`, cambios);
  }

  /** Catálogo con lo que dan los roles del usuario, sus excepciones y si se pueden conceder. */
  tableroPermisos(id: number): Observable<PermisosUsuarioTablero> {
    return this.api.get<PermisosUsuarioTablero>(`/usuarios/${id}/permisos/tablero`);
  }

  /** Concede, deniega o devuelve a la herencia del rol varios permisos en una sola transacción. */
  actualizarPermisos(id: number, cambios: UsuarioPermisosLoteRequest): Observable<UsuarioPermisosLote> {
    return this.api.patch<UsuarioPermisosLote>(`/usuarios/${id}/permisos`, cambios);
  }
}
