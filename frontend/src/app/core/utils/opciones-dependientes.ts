import { DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, Observable, Subject, catchError, finalize, map, switchMap } from 'rxjs';
import { mensajeDeError } from '../errors/error.util';
import { Notificaciones } from '../services/notificaciones';

interface Eleccion<T> {
  id: number | null;
  alCargar?: (opciones: T[]) => void;
}

/**
 * Opciones de un select que dependen de lo elegido en otro (área →
 * responsables, sistema → tipos). Cada elección cancela la petición anterior
 * con `switchMap`: una respuesta lenta nunca pisa a la de una elección
 * posterior, y elegir «ninguno» (null) vacía la lista al momento.
 *
 * Debe crearse en un contexto de inyección (campo o constructor de componente).
 */
export class OpcionesDependientes<T> {
  private readonly elecciones = new Subject<Eleccion<T>>();

  readonly opciones = signal<T[]>([]);
  readonly cargando = signal(false);

  constructor(pedir: (id: number) => Observable<T[]>, mensajeError: string) {
    const notificaciones = inject(Notificaciones);
    this.elecciones
      .pipe(
        switchMap(({ id, alCargar }) => {
          this.opciones.set([]);
          if (id === null) {
            return EMPTY;
          }
          this.cargando.set(true);
          return pedir(id).pipe(
            map((opciones) => ({ opciones, alCargar })),
            catchError((err: unknown) => {
              notificaciones.error(mensajeError, mensajeDeError(err));
              return EMPTY;
            }),
            finalize(() => this.cargando.set(false)),
          );
        }),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe(({ opciones, alCargar }) => {
        this.opciones.set(opciones);
        alCargar?.(opciones);
      });
  }

  /** Pide las opciones del elemento elegido; `alCargar` corre solo si esta elección sigue vigente. */
  elegir(id: number | null, alCargar?: (opciones: T[]) => void): void {
    this.elecciones.next({ id, alCargar });
  }
}
