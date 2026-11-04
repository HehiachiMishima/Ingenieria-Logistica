import { ChangeDetectionStrategy, Component } from '@angular/core';
import { PANEL_SINCRONIZACION } from '../../../../../core/config/paneles';
import { EnConstruccion } from '../../../en-construccion/en-construccion';

/** Resumen del panel sincronizacion: sin lógica todavía, muestra la vista en construcción. */
@Component({
  selector: 'app-sincronizacion-resumen',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EnConstruccion],
  template: `<app-en-construccion [panel]="panel" />`,
})
export class SincronizacionResumen {
  protected readonly panel = PANEL_SINCRONIZACION;
}
