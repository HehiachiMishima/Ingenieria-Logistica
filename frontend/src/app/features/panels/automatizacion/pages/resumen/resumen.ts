import { ChangeDetectionStrategy, Component } from '@angular/core';
import { PANEL_AUTOMATIZACION } from '../../../../../core/config/paneles';
import { PanelVacio } from '../../../panel-vacio/panel-vacio';

/** Resumen del panel automatizacion: vista base mientras no exponga módulos con datos. */
@Component({
  selector: 'app-automatizacion-resumen',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PanelVacio],
  template: `<app-panel-vacio [panel]="panel" />`,
})
export class AutomatizacionResumen {
  protected readonly panel = PANEL_AUTOMATIZACION;
}
