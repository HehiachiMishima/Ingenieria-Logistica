import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { IconoSim } from '../../../icono-sim/icono-sim';
import { EnteroPipe, HorasPipe } from '../../../numero.pipe';
import type { PosicionDetalle, SimulacionConfiguracion } from '../configuracion';

/** Lo que muestra la ficha de una ruta (lo arma Configuración). */
export type RutaDetalle = NonNullable<ReturnType<SimulacionConfiguracion['rutaDetalle']>>;
/** Lo que muestra la ficha de un cluster (lo arma Configuración). */
export type ClusterDetalle = NonNullable<ReturnType<SimulacionConfiguracion['clusterDetalle']>>;

/**
 * Ficha flotante de la matriz: la de una ruta o la de un cluster, junto a lo que se tocó (hoja
 * inferior en celular). Solo presenta: los datos y la posición los calcula Configuración.
 */
@Component({
  selector: 'app-ficha-detalle',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, IconoSim, EnteroPipe, HorasPipe],
  templateUrl: './ficha-detalle.html',
  styleUrl: './ficha-detalle.scss',
})
export class FichaDetalle {
  readonly posicion = input.required<PosicionDetalle>();
  readonly ruta = input<RutaDetalle | null>(null);
  readonly cluster = input<ClusterDetalle | null>(null);
  readonly cerrar = output<void>();
}
