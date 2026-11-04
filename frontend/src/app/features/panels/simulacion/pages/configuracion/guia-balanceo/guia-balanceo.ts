import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../../shared/ui/modal/modal';
import { colorCluster } from '../../../simulacion-formato';

/** Barras de ejemplo (alto en %) para dibujar cada paso. */
const RUTAS_ANTES = [92, 34, 58];
const RUTAS_DESPUES = [62, 60, 64];
/** Chutes del ejemplo de operarios: carga (alto en %) y gente asignada. */
const OPERARIOS = [
  { carga: 90, operarios: 4 },
  { carga: 50, operarios: 2 },
  { carga: 45, operarios: 2 },
];

/** Guía visual del balanceo: olas en orden, los tres pasos y las reglas. */
@Component({
  selector: 'app-simulacion-guia-balanceo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, Modal],
  templateUrl: './guia-balanceo.html',
  styleUrl: './guia-balanceo.scss',
})
export class SimulacionGuiaBalanceo {
  readonly abierto = input(false);
  readonly cerrar = output<void>();

  readonly olas = ['CL1', 'CL2', 'CL3'].map((nombre, indice) => ({
    nombre,
    color: colorCluster(indice),
  }));
  readonly rutasAntes = RUTAS_ANTES;
  readonly rutasDespues = RUTAS_DESPUES;
  readonly chutes = OPERARIOS.map((chute) => ({
    ...chute,
    personas: Array.from({ length: chute.operarios }, (_, i) => i),
  }));
}
