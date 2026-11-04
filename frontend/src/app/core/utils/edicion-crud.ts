import { inject, signal } from '@angular/core';
import { Observable, finalize } from 'rxjs';
import { mensajeDeError } from '../errors/error.util';
import { Notificaciones } from '../services/notificaciones';

/**
 * Estado y flujo de alta, edición y baja con modales: qué modal está abierto,
 * qué elemento se edita o se elimina y si hay una petición en curso. La página
 * aporta el formulario, las peticiones y los avisos de éxito; los errores se
 * notifican aquí («No se pudo guardar» / «No se pudo eliminar»).
 *
 * Debe crearse en un contexto de inyección (campo o constructor de componente).
 */
export class EdicionCrud<T> {
  private readonly notificaciones = inject(Notificaciones);

  readonly formAbierto = signal(false);
  readonly editando = signal<T | null>(null);
  readonly guardando = signal(false);

  readonly eliminarAbierto = signal(false);
  readonly aEliminar = signal<T | null>(null);
  readonly eliminando = signal(false);

  /** Abre el formulario para crear (null) o editar un elemento. */
  abrir(elemento: T | null): void {
    this.editando.set(elemento);
    this.formAbierto.set(true);
  }

  cerrar(): void {
    this.formAbierto.set(false);
    this.editando.set(null);
  }

  /** Envía el alta o la edición; `alGuardar` recibe la respuesta y el elemento editado (o null). */
  guardar<R>(peticion$: Observable<R>, alGuardar: (respuesta: R, editado: T | null) => void): void {
    const editado = this.editando();
    this.guardando.set(true);
    peticion$.pipe(finalize(() => this.guardando.set(false))).subscribe({
      next: (respuesta) => alGuardar(respuesta, editado),
      error: (err: unknown) => this.notificaciones.error('No se pudo guardar', mensajeDeError(err)),
    });
  }

  confirmarEliminar(elemento: T): void {
    this.aEliminar.set(elemento);
    this.eliminarAbierto.set(true);
  }

  /** Elimina el elemento confirmado y cierra el diálogo tras `alEliminar`. */
  eliminar(
    peticion: (elemento: T) => Observable<unknown>,
    alEliminar: (elemento: T) => void,
  ): void {
    const elemento = this.aEliminar();
    if (elemento === null || this.eliminando()) {
      return;
    }
    this.eliminando.set(true);
    peticion(elemento)
      .pipe(finalize(() => this.eliminando.set(false)))
      .subscribe({
        next: () => {
          alEliminar(elemento);
          this.eliminarAbierto.set(false);
          this.aEliminar.set(null);
        },
        error: (err: unknown) =>
          this.notificaciones.error('No se pudo eliminar', mensajeDeError(err)),
      });
  }
}
