import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Icon } from '../../../../shared/ui/icon/icon';

/**
 * Pie de tabla para listas por cursor: página actual y Anterior / Siguiente, sin
 * total (contarlo costaría recorrer todo el período). Mismo aspecto que `ui-paginador`.
 */
@Component({
  selector: 'app-paginador-cursor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    @if (hayAnterior() || haySiguiente()) {
      <div class="ui-paginador">
        <span class="pc-texto">Página {{ pagina() }} · {{ porPagina() }} por página</span>
        <nav class="ui-paginador__nav" aria-label="Paginación">
          <button
            type="button"
            class="ui-paginador__btn pc-btn"
            [disabled]="!hayAnterior() || cargando()"
            (click)="anterior.emit()"
          >
            <ui-icon name="chevronLeft" [size]="16" /> Anterior
          </button>
          <button
            type="button"
            class="ui-paginador__btn pc-btn"
            [disabled]="!haySiguiente() || cargando()"
            (click)="siguiente.emit()"
          >
            Siguiente <ui-icon name="chevronRight" [size]="16" />
          </button>
        </nav>
      </div>
    }
  `,
  styles: `
    :host { display: block; }
    .pc-texto { font-size: var(--fs-sm); color: var(--text-3); }
    .pc-btn { gap: var(--space-1); padding-inline: var(--space-3); min-height: 44px; }
  `,
})
export class PaginadorCursor {
  readonly pagina = input(1);
  readonly porPagina = input(50);
  readonly hayAnterior = input(false);
  readonly haySiguiente = input(false);
  readonly cargando = input(false);
  readonly anterior = output<void>();
  readonly siguiente = output<void>();
}
