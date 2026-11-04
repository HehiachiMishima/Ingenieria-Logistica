import { ChangeDetectionStrategy, Component } from '@angular/core';
import { PANEL_LOGISTICA } from '../../../../../core/config/paneles';
import { EnConstruccion } from '../../../en-construccion/en-construccion';

/** Resumen del panel logistica: sin lógica todavía, muestra la vista en construcción. */
@Component({
  selector: 'app-logistica-resumen',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EnConstruccion],
  template: `<app-en-construccion [panel]="panel" />`,
})
export class LogisticaResumen {
  protected readonly panel = PANEL_LOGISTICA;
}
