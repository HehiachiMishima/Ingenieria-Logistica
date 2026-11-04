import { Service, effect, inject } from '@angular/core';
import { CONFIG } from '../config/configuracion';
import { Sesion } from '../models';
import { RenovacionSesion } from './renovacion-sesion';
import { SesionStore } from './sesion';

/** setTimeout no admite más de ~24,8 días. */
const ESPERA_MAXIMA_MS = 2_147_483_647;

/**
 * Mantiene viva la sesión mientras se usa: renueva el access token un poco
 * antes de que venza si hubo actividad desde la última renovación. Sin
 * actividad deja que venza y, cuando tampoco se puede renovar (pasó el tiempo
 * de inactividad del servidor), bloquea la pantalla: se pide solo la
 * contraseña y la página no se mueve.
 */
@Service()
export class SesionVigilante {
  private readonly store = inject(SesionStore);
  private readonly renovacion = inject(RenovacionSesion);
  private temporizador?: ReturnType<typeof setTimeout>;
  private token: string | null = null;
  private ultimaActividad = Date.now();
  private ultimaRenovacion = Date.now();
  /** Tras fallar por falta de conexión, la actividad no reintenta antes de esto. */
  private pausaHasta = 0;

  constructor() {
    effect(() => this.programar(this.store.sesion()));
    const alActividad = () => this.alActividad();
    for (const evento of ['pointerdown', 'keydown']) {
      document.addEventListener(evento, alActividad, { capture: true, passive: true });
    }
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        alActividad();
      }
    });
  }

  private programar(sesion: Sesion | null): void {
    // Solo un token nuevo reprograma: actualizar los permisos no es una renovación.
    if (sesion?.token === this.token) {
      return;
    }
    this.token = sesion?.token ?? null;
    this.cancelar();
    if (!sesion) {
      return;
    }
    this.ultimaRenovacion = Date.now();
    const vence = new Date(sesion.expiraEn).getTime() - CONFIG.sesion.anticipoRenovacionMs;
    this.esperar(vence - Date.now(), () => this.alVencer());
  }

  private alVencer(): void {
    const sesion = this.store.sesion();
    if (!sesion || this.store.bloqueada()) {
      return;
    }
    if (this.ultimaActividad > this.ultimaRenovacion) {
      this.renovar();
      return;
    }
    // Inactivo: cuando el servidor ya no permita renovar, se bloquea.
    this.esperar(new Date(sesion.renovableHasta).getTime() - Date.now(), () => {
      if (
        this.store.sesion()?.token === sesion.token &&
        this.ultimaActividad <= this.ultimaRenovacion
      ) {
        this.store.bloquear();
      }
    });
  }

  /** Al volver a usar la página con el token vencido o por vencer, se renueva enseguida. */
  private alActividad(): void {
    this.ultimaActividad = Date.now();
    if (
      this.store.sesion() !== null &&
      !this.store.bloqueada() &&
      Date.now() >= this.pausaHasta &&
      this.store.tokenVigente(CONFIG.sesion.anticipoRenovacionMs) === null
    ) {
      this.renovar();
    }
  }

  private renovar(): void {
    void this.renovacion.renovarOBloquear().then((resultado) => {
      if (resultado === 'sin-conexion') {
        this.pausaHasta = Date.now() + CONFIG.sesion.reintentoMs;
        this.esperar(CONFIG.sesion.reintentoMs, () => this.alVencer());
      }
    });
  }

  private esperar(ms: number, accion: () => void): void {
    this.cancelar();
    this.temporizador = setTimeout(accion, Math.min(Math.max(ms, 0), ESPERA_MAXIMA_MS));
  }

  private cancelar(): void {
    if (this.temporizador !== undefined) {
      clearTimeout(this.temporizador);
      this.temporizador = undefined;
    }
  }
}
