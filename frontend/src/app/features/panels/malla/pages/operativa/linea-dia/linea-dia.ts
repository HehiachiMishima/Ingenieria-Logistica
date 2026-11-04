import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Proceso } from '../../../data/malla.model';
import { LineaDia } from '../malla.logica';

/**
 * Línea del día: 24 h desde el inicio del primer turno, con turnos, pasos de
 * cada proceso y olas por cluster. Solo dibuja; al pulsar un proceso avisa.
 */
@Component({
  selector: 'app-malla-linea-dia',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './linea-dia.html',
  styleUrl: './linea-dia.scss',
  host: { '[class.compacta]': 'compacta()' },
})
export class MallaLineaDia {
  readonly linea = input.required<LineaDia>();
  readonly compacta = input(false);
  readonly verProceso = output<Proceso>();
}
