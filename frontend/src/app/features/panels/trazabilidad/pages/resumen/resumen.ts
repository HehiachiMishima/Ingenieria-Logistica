import { ChangeDetectionStrategy, Component } from '@angular/core';
import { PANEL_TRAZABILIDAD } from '../../../../../core/config/paneles';
import { PanelVacio } from '../../../panel-vacio/panel-vacio';

/** Resumen del panel trazabilidad: vista base mientras no exponga módulos con datos. */
@Component({
  selector: 'app-trazabilidad-resumen',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PanelVacio],
  template: `<app-panel-vacio [panel]="panel" />`,
})
export class TrazabilidadResumen {
  protected readonly panel = PANEL_TRAZABILIDAD;
}
