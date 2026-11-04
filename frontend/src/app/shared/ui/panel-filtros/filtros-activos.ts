import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Icon } from '../icon/icon';

export interface ChipFiltro {
  /** Identificador del filtro (lo recibe `quitar`). */
  clave: string;
  /** Nombre del filtro: «Proveedor», «Desde»… */
  etiqueta: string;
  /** Valor legible: «ALICORP S.A.A.», «01/10/2026»… */
  valor: string;
  icono?: string;
}

/**
 * Barra de filtros aplicados como chips removibles. Se muestra bajo la barra de
 * herramientas para que el usuario sepa siempre qué está viendo.
 */
@Component({
  selector: 'ui-filtros-activos',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    @if (chips().length > 0) {
      <div class="ui-chips" role="list" aria-label="Filtros aplicados">
        <span class="ui-chips__titulo"><ui-icon name="filtro" [size]="14" /> Filtrando por</span>
        @for (chip of chips(); track chip.clave) {
          <span class="ui-chip" role="listitem">
            @if (chip.icono) {
              <ui-icon [name]="chip.icono" [size]="13" />
            }
            <span class="ui-chip__etiqueta">{{ chip.etiqueta }}:</span>
            <strong class="ui-chip__valor" [title]="chip.valor">{{ chip.valor }}</strong>
            <button
              type="button"
              class="ui-chip__quitar"
              [attr.aria-label]="'Quitar filtro ' + chip.etiqueta"
              (click)="quitar.emit(chip.clave)"
            >
              <ui-icon name="close" [size]="12" [stroke]="2.4" />
            </button>
          </span>
        }
        @if (chips().length > 1) {
          <button type="button" class="ui-chips__limpiar" (click)="limpiar.emit()">Limpiar todo</button>
        }
      </div>
    }
  `,
})
export class FiltrosActivos {
  readonly chips = input<ChipFiltro[]>([]);
  readonly quitar = output<string>();
  readonly limpiar = output<void>();
}

/** «2026-10-07» → «07/10/2026» para mostrar fechas en los chips. */
export function fechaChip(iso: string): string {
  const [anio, mes, dia] = iso.split('-');
  return anio && mes && dia ? `${dia}/${mes}/${anio}` : iso;
}
