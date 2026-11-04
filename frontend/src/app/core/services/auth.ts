import { HttpClient } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, map, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { opcionesAcceso } from '../interceptors/rutas-acceso';
import { ApiResponse, AuthResponse, CambiarContrasenaRequest, LoginRequest } from '../models';
import { Api } from './api';
import { Carga } from './carga';
import { RenovacionSesion, sesionDe } from './renovacion-sesion';
import { SesionStore } from './sesion';

@Service()
export class Auth {
  private readonly http = inject(HttpClient);
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly sesionStore = inject(SesionStore);
  private readonly carga = inject(Carga);
  private readonly renovacion = inject(RenovacionSesion);
  private readonly base = environment.apiUrl.replace(/\/+$/, '');

  readonly sesion = this.sesionStore.sesion;
  readonly permisos = this.sesionStore.permisos;
  readonly usuario = this.sesionStore.usuario;
  readonly autenticado = this.sesionStore.autenticado;

  iniciarSesion(credenciales: LoginRequest): Observable<AuthResponse> {
    return this.login(credenciales).pipe(tap((datos) => this.sesionStore.guardar(sesionDe(datos))));
  }

  /**
   * Pantalla de sesión vencida: la misma cuenta vuelve a entrar con su
   * contraseña y las peticiones en espera siguen sin salir de la página.
   */
  desbloquear(contrasena: string): Observable<void> {
    const usuario = this.sesionStore.usuario()?.usuario ?? '';
    return this.login({ usuario, contrasena }).pipe(
      map((datos) => this.renovacion.desbloquear(sesionDe(datos))),
    );
  }

  /** Cambia la contraseña de la propia cuenta (exige la actual). */
  cambiarContrasena(peticion: CambiarContrasenaRequest): Observable<void> {
    return this.api.put<void>('/auth/contrasena', peticion);
  }

  cerrarSesion(redirigir = true): void {
    // Revoca el refresh token de este navegador; si falla, igual vence solo.
    this.http.post(`${this.base}/auth/logout`, {}, opcionesAcceso()).subscribe({ error: () => {} });
    this.sesionStore.limpiar();
    this.renovacion.cerrada();
    if (!redirigir) {
      return;
    }
    this.carga.mostrar('Saliendo de Atlas', 'Cerrando tu sesión de forma segura');
    void this.router.navigate(['/login']).then(() => this.carga.ocultar());
  }

  private login(credenciales: LoginRequest): Observable<AuthResponse> {
    return this.http
      .post<ApiResponse<AuthResponse>>(`${this.base}/auth/login`, credenciales, opcionesAcceso())
      .pipe(map((respuesta) => respuesta.datos));
  }

  tienePermiso(permiso: string): boolean {
    return this.sesionStore.tienePermiso(permiso);
  }
}
