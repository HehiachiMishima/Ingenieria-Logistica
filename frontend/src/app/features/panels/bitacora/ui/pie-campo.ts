import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  signal,
} from '@angular/core';
import { AbstractControl, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';

/**
 * Campos de texto obligatorios de Bitácora (nombres de catálogo, ticket,
 * descripción): mismos validadores y mismo pie en todos los formularios, para
 * que el mensaje diga el motivo real (vacío o demasiado largo) y no siempre
 * «es obligatorio».
 */

/** Largo máximo de los nombres de catálogo (VARCHAR(50) en la base). */
export const MAX_NOMBRE = 50;

/** Rechaza un texto hecho solo de espacios (el backend lo recorta y lo rechazaría). */
export const sinSoloEspacios: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const valor = (control.value as string | null) ?? '';
  return valor.length > 0 && valor.trim().length === 0 ? { soloEspacios: true } : null;
};

/** Obligatorio, sin solo espacios y con largo máximo. */
export function textoRequerido(max: number): ValidatorFn[] {
  return [Validators.required, sinSoloEspacios, Validators.maxLength(max)];
}

/** Mensaje según el motivo real; null si es válido o aún no se tocó. */
export function errorDeTexto(control: AbstractControl, obligatorio: string, max: number): string | null {
  if (!control.touched || control.valid) {
    return null;
  }
  if (control.hasError('required') || control.hasError('soloEspacios')) {
    return obligatorio;
  }
  if (control.hasError('maxlength')) {
    return `Máximo ${max} caracteres (tiene ${((control.value as string | null) ?? '').length}).`;
  }
  return 'El valor no es válido.';
}

/**
 * Pie de un campo de texto: mensaje de error a la izquierda y contador de
 * caracteres a la derecha (en rojo al pasarse). Se actualiza con los eventos
 * del control (valor, estado y «tocado»).
 */
@Component({
  selector: 'app-pie-campo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let e = estado();
    <div class="pie">
      @if (e.error) {
        <span class="ui-error">{{ e.error }}</span>
      }
      <span class="pie__contador" [class.pie__contador--excedido]="e.largo > max()" aria-live="polite">
        {{ e.largo }}/{{ max() }}
      </span>
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .pie { display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--space-1) var(--space-2); }
    .pie__contador { margin-left: auto; font-size: var(--fs-xs); color: var(--text-3); font-variant-numeric: tabular-nums; }
    .pie__contador--excedido { color: var(--danger); font-weight: 700; }
  `,
})
export class PieCampo {
  readonly control = input.required<AbstractControl>();
  /** Mensaje cuando falta el valor, p. ej. «El ticket es obligatorio.». */
  readonly obligatorio = input.required<string>();
  readonly max = input.required<number>();

  /** Cambia con cada evento del control: el pie se recalcula aunque el padre no cambie de entrada. */
  private readonly cambios = signal(0);

  constructor() {
    effect((alLimpiar) => {
      const sub = this.control().events.subscribe(() => this.cambios.update((n) => n + 1));
      alLimpiar(() => sub.unsubscribe());
    });
  }

  readonly estado = computed(() => {
    this.cambios();
    const control = this.control();
    return {
      error: errorDeTexto(control, this.obligatorio(), this.max()),
      largo: ((control.value as string | null) ?? '').length,
    };
  });
}
