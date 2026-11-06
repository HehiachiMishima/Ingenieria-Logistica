import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { HorasPipe, NumeroPipe } from '../../../numero.pipe';
import { LIMITES } from '../../../simulacion-formato';
import { Paso, ResumenCluster, normalizarLimite } from '../configuracion.logica';

/**
 * Un cluster en dos formas: ficha compacta en la fila de olas (estado y dato
 * clave) o panel de trabajo del cluster en curso con sus tres pasos. Solo
 * pinta y avisa: las acciones las ejecuta la página.
 */
@Component({
  selector: 'app-simulacion-cluster-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, NumeroPipe, HorasPipe],
  templateUrl: './cluster-card.html',
  styleUrl: './cluster-card.scss',
  host: {
    '[style.--cluster-color]': 'cluster().color',
  },
})
export class SimulacionClusterCard {
  readonly cluster = input.required<ResumenCluster>();
  /** Ficha de la fila de olas (true) o panel de trabajo del cluster en curso. */
  readonly compacto = input(false);
  /** Operarios del equipo. */
  readonly equipo = input.required<number>();
  readonly puedeModificar = input(false);
  readonly ocupado = input(false);
  readonly trabajando = input<Paso | null>(null);

  readonly balancear = output<void>();
  readonly asignar = output<void>();
  readonly cerrar = output<void>();
  readonly restablecer = output<void>();
  readonly reabrir = output<void>();
  readonly limiteCambia = output<number>();

  readonly maximoHoras = LIMITES.horas;

  cambiarLimite(evento: Event): void {
    const input = evento.target as HTMLInputElement;
    const limite = normalizarLimite(input.value);
    input.value = String(limite);
    this.limiteCambia.emit(limite);
  }
}
