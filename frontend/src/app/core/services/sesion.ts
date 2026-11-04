import { Service, computed, signal } from '@angular/core';
import { CONFIG } from '../config/configuracion';
import { Sesion } from '../models';

const CLAVE = CONFIG.almacenamiento.sesion;

/**
 * Sesión del usuario, solo en memoria: el access token nunca se guarda en el
 * navegador (un XSS no puede leerlo). Al recargar, la sesión se recupera con
 * el refresh token de la cookie HttpOnly. En localStorage queda únicamente
 * una marca de «había sesión», para no pedir una renovación inútil a quien
 * nunca inició sesión.
 */
@Service()
export class SesionStore {
  private readonly _sesion = signal<Sesion | null>(null);
  private readonly _bloqueada = signal(false);
  private readonly _permisos = computed<readonly string[]>(() => this._sesion()?.permisos ?? []);

  readonly sesion = this._sesion.asReadonly();
  readonly permisos = this._permisos;
  readonly usuario = computed(() => this._sesion()?.usuario ?? null);
  /** Hay sesión aunque el access token haya vencido: se renueva al usarlo. */
  readonly autenticado = computed(() => this._sesion() !== null);
  /** La sesión venció: la pantalla pide la contraseña y las peticiones esperan. */
  readonly bloqueada = this._bloqueada.asReadonly();

  /** Acciones al cerrar la sesión: vaciar cachés con datos del usuario saliente. */
  private readonly alLimpiarAcciones: (() => void)[] = [];

  constructor() {
    // Versiones anteriores guardaban aquí la sesión con su token: se reduce a la marca.
    if (this.habiaSesion()) {
      this.marcar(true);
    }
  }

  guardar(sesion: Sesion): void {
    this._sesion.set(sesion);
    this.marcar(true);
  }

  /** Actualiza usuario y permisos conservando el token vigente. */
  actualizar(usuario: Sesion['usuario'], permisos: string[]): void {
    const actual = this._sesion();
    if (!actual) {
      return;
    }
    const mismos =
      actual.permisos.length === permisos.length &&
      actual.permisos.every((p, i) => p === permisos[i]);
    if (mismos && JSON.stringify(actual.usuario) === JSON.stringify(usuario)) {
      return;
    }
    this._sesion.set({ ...actual, usuario, permisos });
  }

  bloquear(): void {
    this._bloqueada.set(this._sesion() !== null);
  }

  desbloquear(): void {
    this._bloqueada.set(false);
  }

  limpiar(): void {
    this._sesion.set(null);
    this._bloqueada.set(false);
    this.marcar(false);
    this.alLimpiarAcciones.forEach((accion) => accion());
  }

  /** Registra una acción que se ejecuta cada vez que se limpia la sesión. */
  alLimpiar(accion: () => void): void {
    this.alLimpiarAcciones.push(accion);
  }

  /** Token vigente (con margen), o null si venció o está por vencer. */
  tokenVigente(margenMs = 0): string | null {
    const sesion = this._sesion();
    if (!sesion) {
      return null;
    }
    return new Date(sesion.expiraEn).getTime() - margenMs > Date.now() ? sesion.token : null;
  }

  /** Había una sesión iniciada en este navegador (puede seguir renovable). */
  habiaSesion(): boolean {
    try {
      return localStorage.getItem(CLAVE) !== null;
    } catch {
      return false;
    }
  }

  tienePermiso(permiso: string): boolean {
    return this._permisos().includes(permiso);
  }

  tieneAlguno(permisos: readonly string[]): boolean {
    const actuales = this._permisos();
    return permisos.some((permiso) => actuales.includes(permiso));
  }

  tieneTodos(permisos: readonly string[]): boolean {
    const actuales = this._permisos();
    return permisos.every((permiso) => actuales.includes(permiso));
  }

  /** Guarda o borra la marca. Reemplaza también la sesión completa que guardaban versiones anteriores. */
  private marcar(activa: boolean): void {
    try {
      if (activa) {
        localStorage.setItem(CLAVE, '1');
      } else {
        localStorage.removeItem(CLAVE);
      }
    } catch {
      /* almacenamiento no disponible */
    }
  }
}
