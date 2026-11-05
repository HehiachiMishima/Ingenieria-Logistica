import { ChangeDetectionStrategy, Component } from '@angular/core';
import { PANEL_LOGISTICA } from '../../../../../core/config/paneles';
import { PanelVacio } from '../../../panel-vacio/panel-vacio';

/** Resumen del panel logistica: vista base mientras no exponga módulos con datos. */
@Component({
  selector: 'app-logistica-resumen',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PanelVacio],
  template: `<app-panel-vacio [panel]="panel" />`,
})
export class LogisticaResumen {
  protected readonly panel = PANEL_LOGISTICA;
}
