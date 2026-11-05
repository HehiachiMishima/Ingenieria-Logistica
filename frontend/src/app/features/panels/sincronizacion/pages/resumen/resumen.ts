import { ChangeDetectionStrategy, Component } from '@angular/core';
import { PANEL_SINCRONIZACION } from '../../../../../core/config/paneles';
import { PanelVacio } from '../../../panel-vacio/panel-vacio';

/** Resumen del panel sincronizacion: vista base mientras no exponga módulos con datos. */
@Component({
  selector: 'app-sincronizacion-resumen',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PanelVacio],
  template: `<app-panel-vacio [panel]="panel" />`,
})
export class SincronizacionResumen {
  protected readonly panel = PANEL_SINCRONIZACION;
}
