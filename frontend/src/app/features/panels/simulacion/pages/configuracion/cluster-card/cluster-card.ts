import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { LIMITES, formatearHoras, formatearNumero } from '../../../simulacion-formato';
import { Paso, ResumenCluster, normalizarLimite } from '../configuracion.logica';

/**
 * Tarjeta de un cluster: en curso muestra sus tres pasos (rutas, operarios y
 * cerrar); cerrado, su resultado; en espera, cuándo se habilita. Solo pinta y
 * avisa: las acciones las ejecuta la página.
 */
@Component({
  selector: 'app-simulacion-cluster-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  templateUrl: './cluster-card.html',
  styleUrl: './cluster-card.scss',
  host: {
    '[class.en-curso]': 'cluster().enCurso',
    '[style.--cluster-color]': 'cluster().color',
  },
})
export class SimulacionClusterCard {
  readonly cluster = input.required<ResumenCluster>();
  /** Operarios del equipo. */
  readonly equipo = input.required<number>();
  /** Cluster que se trabaja ahora (para los que esperan). */
  readonly enCurso = input<string | null>(null);
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
  readonly formatearNumero = formatearNumero;
  readonly formatearHoras = formatearHoras;

  cambiarLimite(evento: Event): void {
    const input = evento.target as HTMLInputElement;
    const limite = normalizarLimite(input.value);
    input.value = String(limite);
    this.limiteCambia.emit(limite);
  }
}
