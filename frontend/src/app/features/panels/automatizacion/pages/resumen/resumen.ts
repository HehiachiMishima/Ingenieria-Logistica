import { ChangeDetectionStrategy, Component } from '@angular/core';
import { PANEL_AUTOMATIZACION } from '../../../../../core/config/paneles';
import { EnConstruccion } from '../../../en-construccion/en-construccion';

/** Resumen del panel automatizacion: sin lógica todavía, muestra la vista en construcción. */
@Component({
  selector: 'app-automatizacion-resumen',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EnConstruccion],
  template: `<app-en-construccion [panel]="panel" />`,
})
export class AutomatizacionResumen {
  protected readonly panel = PANEL_AUTOMATIZACION;
}
