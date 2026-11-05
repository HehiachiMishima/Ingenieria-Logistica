import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { PanelDef } from '../../../core/config/paneles';
import { EmptyState } from '../../../shared/ui/empty-state/empty-state';

/**
 * Vista base de los paneles que todavía no exponen módulos con datos. Muestra
 * un estado vacío neutro: el usuario final no debe ver el estado de desarrollo
 * del panel.
 */
@Component({
  selector: 'app-panel-vacio',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EmptyState],
  template: `
    <header class="page-head">
      <div>
        <h1 class="page-head__title">{{ panel().nombre }}</h1>
        <p class="page-head__sub">{{ panel().descripcion }}</p>
      </div>
    </header>

    <div class="table-card">
      <ui-empty
        [icono]="panel().icono"
        titulo="Sin información disponible"
        mensaje="No hay datos para mostrar en este módulo por el momento."
      />
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
  `,
})
export class PanelVacio {
  readonly panel = input.required<PanelDef>();
}
