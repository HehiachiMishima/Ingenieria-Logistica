import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ClusterVista } from '../../models/santa.model';
import { porciento } from '../../santa-calculo';
import { colorCluster, entero } from '../../santa-formato';

/**
 * Avance de cada cluster en cada envío: % acumulado de sus bultos, paletas ya
 * completas y operarios que pide para la ventana. Una fila por cluster, una
 * columna por envío (crece si hay más envíos). Presentación pura.
 */
@Component({
  selector: 'app-avance-clusters',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './avance-clusters.html',
  styleUrl: './avance-clusters.scss',
})
export class AvanceClusters {
  readonly clusters = input.required<ClusterVista[]>();
  readonly envioActual = input(0);

  readonly entero = entero;
  readonly porciento = porciento;
  readonly colorCluster = colorCluster;

  /** Números de envío (de la primera fila: todos los clusters tienen los mismos). */
  readonly envios = computed(() => this.clusters()[0]?.envios.map((e) => e.envio) ?? []);
}
