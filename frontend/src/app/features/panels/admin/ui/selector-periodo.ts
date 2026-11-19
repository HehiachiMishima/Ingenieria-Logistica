import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

export interface OpcionPeriodo<T> {
  valor: T;
  etiqueta: string;
}

/** Botones segmentados para elegir el período (estilo `.ui-rango` del sistema de diseño). */
@Component({
  selector: 'app-selector-periodo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ui-rango" role="group" [attr.aria-label]="etiqueta()">
      @for (o of opciones(); track o.valor) {
        <button
          type="button"
          class="ui-rango__btn"
          [class.ui-rango__btn--on]="o.valor === valor()"
          [attr.aria-pressed]="o.valor === valor()"
          (click)="cambio.emit(o.valor)"
        >
          {{ o.etiqueta }}
        </button>
      }
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    /* Misma altura que los botones de la barra; área táctil completa en pantallas táctiles. */
    .ui-rango__btn { min-height: 40px; padding-inline: var(--space-3); }
    @media (pointer: coarse) { .ui-rango__btn { min-height: 44px; } }
  `,
})
export class SelectorPeriodo<T> {
  readonly opciones = input<OpcionPeriodo<T>[]>([]);
  readonly valor = input.required<T>();
  readonly etiqueta = input('Período');
  readonly cambio = output<T>();
}
