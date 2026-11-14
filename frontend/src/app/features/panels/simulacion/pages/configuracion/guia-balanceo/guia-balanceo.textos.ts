/** Una duda frecuente: su ícono, el término y una respuesta corta. */
export interface GrupoDetalle {
  titulo: string;
  items: readonly { icono: string; termino: string; texto: string }[];
}

/** Respuestas cortas a las dudas de la pantalla, agrupadas por tema. */
export const DETALLES: readonly GrupoDetalle[] = [
  {
    titulo: 'Frecuencias y balanceos',
    items: [
      {
        icono: 'reloj',
        termino: '1 · Diarias',
        texto:
          'Primero, en cada cluster: eliges el rango de chutes y se reparten solo las diarias, por grupos. Desde ahí quedan fijas para los dos días.',
      },
      {
        icono: 'layers',
        termino: '2 · L-X-V y M-J-S',
        texto:
          'En cualquier orden y cada uno por su cuenta: reparte solo sus rutas, dentro del rango de las diarias. Las diarias no se mueven ni cuentan en su reparto: se apilan en el mismo chute.',
      },
      {
        icono: 'usuarios',
        termino: '3 · Operarios · 4 · Cerrar',
        texto:
          'Una sola cantidad de operarios, la misma para los dos días. Cerrar cierra Diarias, L-X-V y M-J-S a la vez.',
      },
      {
        icono: 'refresh',
        termino: 'Rehacer las diarias',
        texto:
          'Si ya balanceaste un día, primero restablece el cluster: deshace sus tres balanceos. «Restablecer todo» devuelve el registro entero a la proyección.',
      },
      {
        icono: 'flecha',
        termino: 'A mano',
        texto: 'Todo se mueve, también las diarias. Mover una diaria la cambia en los dos días.',
      },
      {
        icono: 'ver',
        termino: 'Filtros del panel',
        texto:
          'L-X-V, M-J-S (los dos juntos = Ambos, apilados) y Diarias (sola). Los totales siguen el filtro: en Ambos las diarias cuentan una vez y los operarios son el máximo de los dos días.',
      },
      {
        icono: 'paquete',
        termino: 'Buff',
        texto:
          'Global: se elige antes del primer balanceo. Cada frecuencia (Diarias, L-X-V y M-J-S) se balancea sola y en orden: primero por grupos (cada grupo en chutes seguidos según sus bultos y con sus rutas parejas; si mover una ruta sola no mejora, se intercambia una por otra) y después el buff: se mueven columnas completas para que el grupo con más bultos tenga un buff con su chute más cargado y los buff lleven la mayor carga. Al final se verifica que entre los chutes usados no quede ninguno vacío; los que una frecuencia no usa quedan en los extremos, fuera de ese día (rayados, sin rutas ni operarios).',
      },
      {
        icono: 'info',
        termino: 'Columna FRECUENCIA',
        texto:
          'Días L, M, X, J, V, S separados por guion (L-X-V, M-J-S, L-X, V…) o DIARIA. Días mezclados (p. ej. L-M) cuentan como diaria.',
      },
    ],
  },
  {
    titulo: 'Parámetros',
    items: [
      {
        icono: 'usuarios',
        termino: 'Operarios de la ola',
        texto:
          'Cada cluster ingresa una cantidad (mínimo uno por chute con carga), la misma para L-X-V y M-J-S; la próxima vez se propone la última que usó. La gente de la ola anterior se queda en su chute si el cierre no empeora.',
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
    titulo: 'Grupos',
    items: [
      {
        icono: 'nodos',
        termino: 'Qué es un grupo',
        texto:
          'Columna GRUPO del Excel: rutas de un mismo cluster que se balancean juntas. Cada ruta tiene un solo grupo y no cambia.',
      },
      {
        icono: 'layers',
        termino: 'Tramos',
        texto:
          'El rango lo eliges al balancear las diarias. Cada frecuencia arma sus propios tramos dentro de él: cada grupo, chutes seguidos según sus bultos (al menos uno, nunca más que sus rutas), en el orden que mejor aprovecha el buff.',
      },
      {
        icono: 'control',
        termino: 'Balanceo por grupo',
        texto:
          'Cada grupo se iguala solo dentro de su tramo: el balanceo nunca junta en un chute rutas de grupos distintos de la misma frecuencia (las diarias de otro grupo sí pueden quedar apiladas). A mano puedes moverlas a cualquier chute.',
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
        texto:
          'Si los clusters afectados ya tenían operarios, se borran: vuelve a balancear operarios.',
      },
      {
        icono: 'control',
        termino: 'Volver a balancear',
        texto:
          'Con el cluster en curso ya balanceado, reparte de nuevo sus rutas desde como están hoy. Si tiene varios grupos, una ruta que moviste a mano al tramo de otro grupo vuelve al de su grupo (el modal te dice cuántas entrarán).',
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
          'Se cierra en los tres balanceos a la vez y ya no cambia. Al reabrirlo, los clusters que se trabajaron después vuelven a la proyección.',
      },
      {
        icono: 'refresh',
        termino: 'Restablecer',
        texto:
          'El cluster: deshace sus tres balanceos (Diarias, L-X-V y M-J-S), lo que moviste a mano mientras estuvo en curso y los operarios de su ola. «Restablecer todo»: el registro entero.',
      },
      {
        icono: 'dashboard',
        termino: 'Al terminar',
        texto: 'Cerrado el último cluster, el resumen queda en el dashboard.',
      },
    ],
  },
];
