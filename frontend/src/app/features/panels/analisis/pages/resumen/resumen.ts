import { ChangeDetectionStrategy, Component } from '@angular/core';
import { PANEL_ANALISIS } from '../../../../../core/config/paneles';
import { EnConstruccion } from '../../../en-construccion/en-construccion';

/** Resumen del panel analisis: sin lógica todavía, muestra la vista en construcción. */
@Component({
  selector: 'app-analisis-resumen',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EnConstruccion],
  template: `<app-en-construccion [panel]="panel" />`,
})
export class AnalisisResumen {
  protected readonly panel = PANEL_ANALISIS;
}
