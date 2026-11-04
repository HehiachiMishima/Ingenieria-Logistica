import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { PanelDef } from '../../../core/config/paneles';
import { EmptyState } from '../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../shared/ui/icon/icon';

/**
 * Vista común de los paneles que aún no tienen lógica. Cada panel la usa desde
 * su propia página (`pages/resumen`), que es donde se agregará la lógica real.
 */
@Component({
  selector: 'app-en-construccion',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, EmptyState],
  template: `
    <header class="page-head">
      <div>
        <h1 class="page-head__title">{{ panel().nombre }}</h1>
        <p class="page-head__sub">{{ panel().descripcion }}</p>
      </div>
      <span class="ui-badge ui-badge--warning">
        <ui-icon name="reloj" [size]="14" />
        En construcción
      </span>
    </header>

    <div class="table-card">
      <ui-empty
        [icono]="panel().icono"
        titulo="Panel en construcción"
        mensaje="Este panel todavía no tiene módulos con datos. Estará disponible próximamente."
      />
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
  `,
})
export class EnConstruccion {
  readonly panel = input.required<PanelDef>();
}
