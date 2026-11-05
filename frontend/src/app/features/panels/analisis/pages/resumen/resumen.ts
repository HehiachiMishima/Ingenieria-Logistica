import { ChangeDetectionStrategy, Component } from '@angular/core';
import { PANEL_ANALISIS } from '../../../../../core/config/paneles';
import { PanelVacio } from '../../../panel-vacio/panel-vacio';

/** Resumen del panel analisis: vista base mientras no exponga módulos con datos. */
@Component({
  selector: 'app-analisis-resumen',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PanelVacio],
  template: `<app-panel-vacio [panel]="panel" />`,
})
export class AnalisisResumen {
  protected readonly panel = PANEL_ANALISIS;
}
