import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, Subject, firstValueFrom, from, map, of, switchMap, take } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CONFIG } from '../config/configuracion';
import { opcionesAcceso } from '../interceptors/rutas-acceso';
import { ApiResponse, AuthResponse, Sesion } from '../models';
import { SesionStore } from './sesion';

export type ResultadoRenovacion = 'renovada' | 'vencida' | 'sin-conexion';

type MensajeSesion = { tipo: 'cerrada' } | { tipo: 'desbloqueada' };

const CANAL = 'atlas-sesion';
const CANDADO = 'atlas-renovar-sesion';
/** Un token que vence en menos de esto ya no se envía: se renueva antes. */
const MARGEN_TOKEN_MS = 10_000;

export function sesionDe(datos: AuthResponse): Sesion {
  return {
    token: datos.token,
    tipo: datos.tipo,
    expiraEn: datos.expiraEn,
    renovableHasta: datos.renovableHasta,
    usuario: datos.usuario,
    permisos: datos.permisos,
  };
}

/**
 * Renovación del access token con el refresh token (cookie HttpOnly):
 * - una sola renovación a la vez en la pestaña y, con Web Locks, entre
 *   pestañas: cada token sirve una vez y dos usos seguidos cerrarían la sesión;
 * - si la sesión venció, la bloquea: las peticiones esperan a que el usuario
 *   escriba su contraseña y siguen solas, sin salir de la pantalla;
 * - avisa a las demás pestañas al desbloquear o cerrar (BroadcastChannel).
 */
@Service()
export class RenovacionSesion {
  private readonly http = inject(HttpClient);
  private readonly store = inject(SesionStore);
  private readonly router = inject(Router);
  private readonly url = `${environment.apiUrl.replace(/\/+$/, '')}/auth/refresh`;
  private readonly canal =
    typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(CANAL);
  /** true = se desbloqueó; false = se cerró la sesión. */
  private readonly desbloqueos = new Subject<boolean>();
  private enCurso: Promise<ResultadoRenovacion> | null = null;

  constructor() {
    this.canal?.addEventListener('message', (evento: MessageEvent<MensajeSesion>) =>
      this.alMensaje(evento.data),
    );
  }

  /** Al abrir la app: recupera la sesión con la cookie si en este navegador había una. */
  async iniciar(): Promise<void> {
    if (!this.store.habiaSesion()) {
      return;
    }
    const espera = new Promise<ResultadoRenovacion>((resolver) =>
      setTimeout(() => resolver('sin-conexion'), CONFIG.sesion.esperaInicioMs),
    );
    if ((await Promise.race([this.renovar(), espera])) === 'vencida') {
      this.store.limpiar();
    }
  }

  /** Pide un access token nuevo. Llamadas simultáneas comparten la misma petición. */
  renovar(): Promise<ResultadoRenovacion> {
    this.enCurso ??= this.conCandado(() => this.pedir()).finally(() => (this.enCurso = null));
    return this.enCurso;
  }

  /** Renueva y, si la sesión venció, la bloquea. */
  async renovarOBloquear(): Promise<ResultadoRenovacion> {
    const resultado = await this.renovar();
    if (resultado === 'vencida') {
      this.store.bloquear();
    }
    return resultado;
  }

  /**
   * Token para enviar una petición: el vigente o, si venció, uno renovado. Con
   * la sesión bloqueada espera al desbloqueo; null si el usuario cerró sesión.
   */
  tokenListo(): Observable<string | null> {
    if (this.store.bloqueada()) {
      return this.esperarDesbloqueo();
    }
    const token = this.store.tokenVigente(MARGEN_TOKEN_MS);
    return token ? of(token) : this.recuperar();
  }

  /** Tras un 401 o con el token vencido: renueva, o bloquea y espera la contraseña. */
  recuperar(): Observable<string | null> {
    return from(this.renovarOBloquear()).pipe(
      switchMap((resultado) => {
        if (resultado === 'vencida') {
          return this.esperarDesbloqueo();
        }
        // Sin conexión se intenta con el token que haya: la petición dirá qué pasa.
        return of(this.store.sesion()?.token ?? null);
      }),
    );
  }

  /** El usuario volvió a escribir su contraseña: siguen las peticiones en espera. */
  desbloquear(sesion: Sesion): void {
    this.store.guardar(sesion);
    this.store.desbloquear();
    this.desbloqueos.next(true);
    this.publicar({ tipo: 'desbloqueada' });
  }

  /** Se cerró la sesión aquí: las peticiones en espera terminan y las otras pestañas también salen. */
  cerrada(): void {
    this.desbloqueos.next(false);
    this.publicar({ tipo: 'cerrada' });
  }

  private esperarDesbloqueo(): Observable<string | null> {
    return this.desbloqueos.pipe(
      take(1),
      map((desbloqueada) => (desbloqueada ? (this.store.sesion()?.token ?? null) : null)),
    );
  }

  private async pedir(): Promise<ResultadoRenovacion> {
    try {
      const respuesta = await firstValueFrom(
        this.http.post<ApiResponse<AuthResponse>>(this.url, {}, opcionesAcceso()),
      );
      this.store.guardar(sesionDe(respuesta.datos));
      return 'renovada';
    } catch (err) {
      return err instanceof HttpErrorResponse && (err.status === 401 || err.status === 400)
        ? 'vencida'
        : 'sin-conexion';
    }
  }

  private conCandado(trabajo: () => Promise<ResultadoRenovacion>): Promise<ResultadoRenovacion> {
    const candados = typeof navigator === 'undefined' ? undefined : navigator.locks;
    return candados ? candados.request(CANDADO, trabajo) : trabajo();
  }

  private alMensaje(mensaje: MensajeSesion): void {
    if (mensaje.tipo === 'cerrada' && this.store.sesion() !== null) {
      this.store.limpiar();
      this.desbloqueos.next(false);
      void this.router.navigate([CONFIG.rutas.login]);
    } else if (mensaje.tipo === 'desbloqueada' && this.store.bloqueada()) {
      // La otra pestaña abrió una sesión nueva: esta renueva con la cookie compartida.
      void this.renovar().then((resultado) => {
        if (resultado === 'renovada') {
          this.store.desbloquear();
          this.desbloqueos.next(true);
        }
      });
    }
  }

  private publicar(mensaje: MensajeSesion): void {
    this.canal?.postMessage(mensaje);
  }
}
