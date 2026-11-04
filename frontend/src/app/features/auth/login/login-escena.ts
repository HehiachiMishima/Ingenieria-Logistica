/**
 * Datos de la escena del login: contorno del Perú, red de rutas desde Lima y
 * pines de capas con su enlace a una ciudad. Coordenadas del mapa en su
 * viewBox (217×312); pines y enlaces en % del contenedor (viewBox 0–100).
 */

export type Punto = readonly [number, number];

/** El mapa ocupa el 56 % central del contenedor y todo su alto. */
const MAPA = { x: 22, ancho: 56, vbAncho: 217, vbAlto: 312 } as const;

/** Contorno del Perú (proyección equirectangular). */
export const PERU =
  'M48.8 80.6L49.8 78.8L49.4 76.0L51.2 72.6L51.7 69.6L53.9 66.7L54.8 61.5L56.0 61.5L56.5 63.1L57.5 62.0L56.9 60.2L62.6 54.4L81.9 47.6L91.4 40.4L99.7 30.6L102.3 20.6L103.5 21.6L105.3 21.3L104.0 17.5L104.8 14.6L102.1 12.5L100.9 9.2L98.7 8.4L98.7 7.2L102.0 8.2L104.8 7.4L105.7 6.0L110.9 9.3L112.5 9.0L113.5 11.5L114.6 12.2L115.1 11.8L118.9 14.8L119.8 18.3L120.9 19.5L120.4 20.7L121.3 22.3L123.7 23.1L127.6 26.3L130.5 26.5L132.1 27.9L133.9 31.8L133.2 34.4L136.3 36.2L137.9 35.0L139.0 35.8L140.2 40.5L138.9 42.2L139.3 43.8L141.1 45.0L142.1 44.7L143.0 46.5L147.6 44.9L153.6 46.4L157.5 44.3L159.1 44.5L160.3 42.1L162.2 40.6L162.6 42.0L165.1 42.1L167.7 44.0L170.4 44.1L175.7 41.9L178.9 44.0L180.3 46.2L184.8 46.4L186.5 48.9L188.1 49.6L188.5 50.6L177.9 67.4L181.3 68.7L184.4 67.7L186.7 70.0L187.6 72.1L190.3 74.4L190.0 76.0L186.3 76.4L184.6 75.0L184.4 73.2L180.2 74.2L177.2 73.4L174.2 77.2L170.7 76.9L163.0 79.3L158.8 79.5L153.5 83.3L151.2 83.8L149.6 86.4L143.5 89.4L141.7 94.9L141.7 98.1L137.4 105.3L139.1 111.1L138.0 112.8L135.4 113.2L129.1 118.1L128.2 121.5L129.7 125.2L126.0 126.0L125.7 128.7L124.8 129.0L127.7 132.1L129.7 132.9L129.6 134.4L128.4 134.5L128.6 135.7L130.7 136.9L132.5 142.3L134.6 143.9L135.6 146.5L138.4 148.1L141.7 152.6L141.6 155.2L137.8 159.6L145.5 159.7L152.3 161.6L153.2 163.7L152.9 165.1L154.4 166.0L154.8 167.3L154.3 169.3L168.6 169.2L173.4 166.2L180.0 160.2L181.3 159.9L179.8 162.3L180.2 163.4L179.4 166.6L179.4 185.8L181.0 184.5L184.3 186.8L190.7 184.2L196.6 184.8L206.9 201.9L211.0 210.2L208.8 214.0L206.2 216.2L206.4 226.6L204.8 230.0L208.2 237.9L205.7 238.9L206.1 241.0L202.1 244.2L201.7 246.0L199.8 247.9L199.6 250.7L203.6 255.2L201.4 256.5L198.9 261.3L202.3 270.2L203.6 271.2L206.3 271.0L208.6 273.3L205.9 274.4L205.1 276.4L205.6 278.3L202.7 280.3L199.2 285.1L195.8 287.4L195.2 288.6L197.7 290.1L197.7 292.2L194.7 294.8L192.2 295.1L193.3 299.5L192.2 302.4L189.8 304.7L183.0 306.0L174.2 299.0L168.6 295.6L167.0 290.5L165.4 288.8L160.0 287.0L149.9 279.1L144.5 277.9L135.8 272.9L127.6 270.1L124.0 266.8L119.2 265.1L117.1 263.0L108.0 258.8L100.4 249.8L93.7 245.6L92.8 242.4L88.8 237.3L87.9 233.2L86.2 233.1L86.7 231.6L87.6 231.4L88.2 232.4L89.3 228.9L89.4 225.3L84.6 219.2L84.2 216.8L79.7 208.4L74.4 203.3L73.1 196.4L70.4 193.1L65.9 190.4L66.6 188.7L65.4 184.6L59.1 174.5L51.8 155.7L49.2 152.7L49.5 151.5L48.5 150.1L47.8 146.1L45.2 143.4L44.3 140.2L38.5 134.8L34.6 127.8L32.5 122.0L28.9 118.2L28.2 116.3L17.1 109.0L8.9 105.2L8.6 103.4L9.3 101.4L10.6 100.9L13.0 101.3L13.8 98.1L12.4 94.5L8.4 90.5L8.9 88.5L10.4 87.8L6.0 82.3L7.1 76.1L13.8 69.1L14.7 66.9L18.1 64.8L19.4 62.9L21.8 62.5L22.4 60.9L23.8 61.5L25.5 69.1L22.8 71.2L20.4 70.8L19.8 71.8L20.4 74.3L22.5 74.3L20.2 77.3L20.4 78.2L22.1 78.7L25.8 75.7L30.9 78.9L33.6 78.1L35.4 79.5L36.9 84.1L39.4 86.8L43.2 87.5L45.3 85.2L46.1 82.1Z';

/** Lima: centro de distribución desde el que salen las rutas. */
export const LIMA: Punto = [75.8, 202.8];

const CIUDADES = {
  piura: [17.7, 90.5],
  chiclayo: [30.5, 116.4],
  cajamarca: [52.2, 122.8],
  trujillo: [43.6, 138.3],
  chimbote: [50.7, 154],
  tarapoto: [86.6, 111.8],
  iquitos: [137.1, 66.9],
  pucallpa: [116, 142.7],
  huanuco: [88.7, 168.1],
  huancayo: [105.5, 203.2],
  ica: [97, 235.9],
  ayacucho: [121.4, 221],
  cusco: [157.8, 227.1],
  maldonado: [202.7, 211.8],
  arequipa: [164.7, 274.1],
  puno: [189.3, 264.9],
  tacna: [185.6, 300.5],
} as const satisfies Record<string, Punto>;

/** Ciudades conectadas a la red. */
export const NODOS: readonly Punto[] = Object.values(CIUDADES);

/** Tramos de la red de distribución: costa, sierra y oriente enlazados a Lima. */
const TRAMOS: ReadonlyArray<readonly [Punto, Punto]> = [
  [LIMA, CIUDADES.chimbote], [CIUDADES.chimbote, CIUDADES.trujillo], [CIUDADES.trujillo, CIUDADES.chiclayo],
  [CIUDADES.chiclayo, CIUDADES.piura], [CIUDADES.trujillo, CIUDADES.cajamarca], [CIUDADES.cajamarca, CIUDADES.tarapoto],
  [LIMA, CIUDADES.huanuco], [CIUDADES.huanuco, CIUDADES.tarapoto], [CIUDADES.huanuco, CIUDADES.pucallpa],
  [CIUDADES.pucallpa, CIUDADES.iquitos], [LIMA, CIUDADES.huancayo], [CIUDADES.huancayo, CIUDADES.ayacucho],
  [CIUDADES.ayacucho, CIUDADES.cusco], [CIUDADES.cusco, CIUDADES.maldonado], [CIUDADES.cusco, CIUDADES.puno],
  [LIMA, CIUDADES.ica], [CIUDADES.ica, CIUDADES.arequipa], [CIUDADES.arequipa, CIUDADES.puno],
  [CIUDADES.arequipa, CIUDADES.tacna],
];

/** Rutas: arcos suaves por cada tramo. */
export const RUTAS: readonly string[] = TRAMOS.map(([a, b]) => arco(a, b, 0.16));

export interface CapaPin {
  icono: string;
  texto: string;
  /** Posición del pin en % del contenedor. */
  x: number;
  y: number;
  /** Ciudad del mapa a la que se enlaza. */
  nodo: Punto;
}

export const CAPAS: readonly CapaPin[] = [
  { icono: 'almacen', texto: 'Almacenes', x: 12, y: 13, nodo: CIUDADES.trujillo },
  { icono: 'area', texto: 'Slotting', x: 89, y: 11, nodo: CIUDADES.iquitos },
  { icono: 'farmacia', texto: 'Farmacias', x: 10, y: 58, nodo: LIMA },
  { icono: 'dashboard', texto: 'Datos y analítica', x: 90, y: 64, nodo: CIUDADES.cusco },
];

/** Enlaces punteados de cada pin a su ciudad. */
export const ENLACES: readonly string[] = CAPAS.map((c) => arco([c.x, c.y], enContenedor(c.nodo), -0.22));

function enContenedor([x, y]: Punto): Punto {
  return [MAPA.x + (x / MAPA.vbAncho) * MAPA.ancho, (y / MAPA.vbAlto) * 100];
}

/** Curva cuadrática de `a` a `b` con el control desplazado en perpendicular. */
function arco(a: Punto, b: Punto, curvatura: number): string {
  const [mx, my] = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
  const [cx, cy] = [mx - (b[1] - a[1]) * curvatura, my + (b[0] - a[0]) * curvatura];
  const f = (n: number) => n.toFixed(1);
  return `M${f(a[0])} ${f(a[1])} Q${f(cx)} ${f(cy)} ${f(b[0])} ${f(b[1])}`;
}
