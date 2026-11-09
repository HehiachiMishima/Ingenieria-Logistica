import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../../shared/ui/modal/modal';
import { colorCluster } from '../../../simulacion-formato';

interface GrupoDetalle {
  titulo: string;
  items: readonly { icono: string; termino: string; texto: string }[];
}

/** Barras de ejemplo (alto en %) para dibujar cada paso. */
const RUTAS_ANTES = [92, 34, 58];
const RUTAS_DESPUES = [62, 60, 64];
/** Chutes del ejemplo de operarios: carga (alto en %) y gente asignada. */
const OPERARIOS = [
  { carga: 90, operarios: 4 },
  { carga: 50, operarios: 2 },
  { carga: 45, operarios: 2 },
];

/** Respuestas cortas a las dudas de la pantalla, agrupadas por tema. */
const DETALLES: readonly GrupoDetalle[] = [
  {
    titulo: 'Parámetros',
    items: [
      {
        icono: 'usuarios',
        termino: 'Operarios de la ola',
        texto:
          'Cada cluster ingresa su cantidad en el paso 2 (mínimo uno por chute con carga); la próxima vez se propone la última que usó. La gente de la ola anterior se queda en su chute si el cierre no empeora.',
      },
      {
        icono: 'tendencia',
        termino: 'Velocidad',
        texto: 'Bultos por hora que mueve cada operario.',
      },
      {
        icono: 'layers',
        termino: 'Máximo por chute',
        texto:
          'Tope de operarios en un mismo chute. Vacío: un chute puede recibir a toda la ola. Se bloquea cuando un cluster ya se cerró: reábrelo para cambiarlo.',
      },
      {
        icono: 'lock',
        termino: 'Chutes del 1 al 33',
        texto:
          'Siempre se ven del CHUTE 1 al 33, aunque el Excel no los traiga. El 19, 20 y 21 están bloqueados: no se muestran, el Excel no puede traer rutas en ellos y un rango que los cruza (p. ej. 18 → 22) reparte solo en los disponibles.',
      },
      {
        icono: 'reloj',
        termino: 'Límite de horas',
        texto: 'Por cluster. La barra muestra cuánto usa: verde cumple, rojo excede.',
      },
    ],
  },
  {
    titulo: 'Mover rutas a mano',
    items: [
      {
        icono: 'flecha',
        termino: 'Cambiar de chute',
        texto: 'Arrastra la ruta con ⋮⋮ a otra columna del mismo cluster.',
      },
      {
        icono: 'area',
        termino: 'Cambiar de cluster',
        texto:
          'Arrástrala al cuadro de otro cluster no cerrado, antes o después de balancear. Los destinos no permitidos se marcan en rojo.',
      },
      {
        icono: 'ver',
        termino: 'Rutas movidas',
        texto:
          'Las que ya no están donde venían en el Excel se ven rayadas; «← CLx» indica su cluster original.',
      },
      {
        icono: 'usuarios',
        termino: 'Después de mover',
        texto: 'Si los clusters afectados ya tenían operarios, se borran: vuelve a balancear operarios.',
      },
      {
        icono: 'control',
        termino: 'Volver a balancear',
        texto:
          'Con el cluster en curso ya balanceado, reparte de nuevo sus rutas desde como están hoy, sin perder lo que moviste a mano.',
      },
    ],
  },
  {
    titulo: 'Reglas',
    items: [
      {
        icono: 'lock',
        termino: 'Cluster cerrado',
        texto:
          'No cambia. Al reabrirlo, los clusters que se trabajaron después vuelven a la proyección.',
      },
      {
        icono: 'refresh',
        termino: 'Restablecer',
        texto:
          'Deshace lo hecho mientras el cluster estuvo en curso: su balanceo y lo que moviste o cambiaste de cluster a mano. Quita los operarios de su ola.',
      },
      {
        icono: 'dashboard',
        termino: 'Al terminar',
        texto: 'Cerrado el último cluster, el resumen queda en el dashboard.',
      },
    ],
  },
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
  /** Lo que la pantalla no explica: parámetros, arrastre manual y reglas. */
  readonly detalles = DETALLES;

  readonly chutes = OPERARIOS.map((chute) => ({
    ...chute,
    personas: Array.from({ length: chute.operarios }, (_, i) => i),
  }));
}
