import { ChangeDetectionStrategy, Component } from '@angular/core';
import { PANEL_TRAZABILIDAD } from '../../../../../core/config/paneles';
import { EnConstruccion } from '../../../en-construccion/en-construccion';

/** Resumen del panel trazabilidad: sin lógica todavía, muestra la vista en construcción. */
@Component({
  selector: 'app-trazabilidad-resumen',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EnConstruccion],
  template: `<app-en-construccion [panel]="panel" />`,
})
export class TrazabilidadResumen {
  protected readonly panel = PANEL_TRAZABILIDAD;
}
