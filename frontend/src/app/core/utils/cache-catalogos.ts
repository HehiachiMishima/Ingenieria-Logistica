import { WritableSignal, inject, signal } from '@angular/core';
import { Observable, catchError, forkJoin, throwError } from 'rxjs';
import { Notificaciones } from '../services/notificaciones';
import { SesionStore } from '../services/sesion';

type Listas = Record<string, readonly unknown[]>;

/** Error de carga etiquetado con el catálogo que lo produjo. */
interface FalloCatalogo<T> {
  clave: keyof T;
  err: unknown;
}

/**
 * Base de las cachés de catálogos de un panel (listas pequeñas que comparten
 * filtros, formularios y chips de varias páginas).
 *
 * - Se piden una sola vez por sesión con `cargar()`, en una única petición
 *   combinada: si algún catálogo falla se avisa una vez y la siguiente
 *   `cargar()` reintenta.
 * - `invalidar()` las marca obsoletas tras un CRUD en Control; la próxima
 *   `cargar()` las vuelve a pedir (también si se invalida con una carga en curso).
 * - Se vacían al cerrar la sesión, para que quien entre después en la misma
 *   pestaña no vea los catálogos del usuario anterior.
 */
export abstract class CacheCatalogos<T extends Listas> {
  private readonly notificaciones = inject(Notificaciones);
  private readonly senales: Partial<{ [K in keyof T]: WritableSignal<T[K]> }> = {};
  private estado: 'vacio' | 'cargando' | 'listo' = 'vacio';
  private obsoleto = false;
  /** Cambia al vaciar la caché: descarta la respuesta de una carga anterior. */
  private ronda = 0;

  constructor() {
    inject(SesionStore).alLimpiar(() => this.vaciar());
  }

  /** Petición de cada catálogo. */
  protected abstract fuentes(): { [K in keyof T]: Observable<T[K]> };

  /** Título y detalle del aviso cuando falla la carga de un catálogo. */
  protected abstract avisoError(clave: keyof T, err: unknown): [titulo: string, detalle?: string];

  cargar(): void {
    if (this.estado !== 'vacio') {
      return;
    }
    this.estado = 'cargando';
    this.obsoleto = false;
    const ronda = this.ronda;
    const fuentes = this.fuentes();
    const etiquetadas = Object.fromEntries(
      Object.entries(fuentes).map(([clave, fuente]) => [
        clave,
        fuente.pipe(catchError((err: unknown) => throwError(() => ({ clave, err })))),
      ]),
    ) as typeof fuentes;
    forkJoin(etiquetadas).subscribe({
      next: (listas) => {
        if (ronda !== this.ronda) {
          return;
        }
        for (const clave of Object.keys(listas) as (keyof T)[]) {
          this.senal(clave).set(listas[clave]);
        }
        this.estado = this.obsoleto ? 'vacio' : 'listo';
      },
      error: ({ clave, err }: FalloCatalogo<T>) => {
        if (ronda !== this.ronda) {
          return;
        }
        this.estado = 'vacio';
        this.notificaciones.error(...this.avisoError(clave, err));
      },
    });
  }

  /** Marca los catálogos como obsoletos; la próxima `cargar()` los vuelve a pedir. */
  invalidar(): void {
    if (this.estado === 'cargando') {
      this.obsoleto = true;
      return;
    }
    this.estado = 'vacio';
  }

  /** Lista de solo lectura de un catálogo (vacía hasta la primera carga). */
  protected lista<K extends keyof T>(clave: K) {
    return this.senal(clave).asReadonly();
  }

  private senal<K extends keyof T>(clave: K): WritableSignal<T[K]> {
    return (this.senales[clave] ??= signal([] as unknown as T[K]));
  }

  private vaciar(): void {
    this.ronda++;
    this.estado = 'vacio';
    for (const senal of Object.values(this.senales) as WritableSignal<unknown[]>[]) {
      senal.set([]);
    }
  }
}
