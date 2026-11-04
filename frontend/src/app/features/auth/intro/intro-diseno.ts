/**
 * Diseño de la intro en un único punto: línea de tiempo, marca, colores,
 * densidad de la escena y mezcla de sonido. La escena (canvas), el sonido
 * (Web Audio), el componente y el SCSS leen de aquí; para cambiar el aspecto
 * o la duración no hace falta tocar su lógica.
 */

/** Línea de tiempo en segundos (máximo 8 s). */
export const INTRO = {
  /** Nace la línea roja. */
  haz: 0.25,
  /** La línea se abre en abanico. */
  abanico: 0.95,
  /** Estallido radial: túnel de haces. */
  tunel: 1.7,
  /** Los haces se alzan y forman una cortina vertical. */
  cortina: 2.6,
  /** La cortina se cierra hacia el logotipo. */
  compresion: 3.3,
  /** Impacto: aparece la marca. */
  marca: 3.75,
  /** Fundido final. */
  salida: 6.9,
  /** Duración total. */
  duracion: 7.6,
} as const;

/** Tiempos de interfaz alrededor de la línea de tiempo. */
export const TIEMPOS = {
  /** Espera máxima a la fuente del logotipo antes de arrancar. */
  esperaFuenteMs: 700,
  /** Corte a negro al omitir (más rápido que la entrada). */
  corteMs: 280,
  /** Con movimiento reducido, segundos antes del impacto en que arranca. */
  previoReducido: 0.3,
} as const;

/** Textos y tipografía de la marca. */
export const MARCA = {
  palabra: 'ATLAS',
  nombre: 'Atlas',
  lema: ['Farmacias Peruanas', 'Ingeniería Logística'],
  fuente: '900 100px "Inter Tight"',
  /** Índice (0-based) de la letra 100 % amarilla → la L. */
  indiceAmarilla: 2,
  /** Índice (0-based) de la letra mitad roja, mitad amarilla → la T. */
  indiceMixta: 1,
} as const;

/** Colores de la escena de luz. */
export const COLORES = {
  /** Degradado radial del fondo: centro, medio y borde. */
  fondo: ['#0b1730', '#05080f', '#020305'],
  /** Línea roja con la que abre la escena. */
  linea: '#ff3b4e',
  /** Haces: rojos de marca con fugas de luz cálidas y frías. */
  haces: [
    '#e11d2e',
    '#ff3b4e',
    '#ff3b4e',
    '#ff6b3d',
    '#ffb03a',
    '#ff2d95',
    '#c026d3',
    '#8b5cf6',
    '#4f46e5',
    '#2563eb',
    '#38bdf8',
    '#ffd6dc',
  ],
  /** Polvo en suspensión (bokeh). */
  motas: '#7aa2ff',
  /** Sombra (RGB) del núcleo del túnel y de los bordes de la cortina. */
  sombra: '4, 6, 12',
  /** Velo del impacto y flare anamórfico. */
  destello: '#ff5a64',
  flareBorde: '255, 59, 78',
  flareCentro: '255, 220, 225',
} as const;

/** Densidad de la escena según el ancho de pantalla. */
export const ESCENA = {
  hacesMovil: 90,
  hacesEscritorio: 150,
  /** Ancho (px) por debajo del cual se usa la densidad móvil. */
  corteMovil: 640,
  motas: 70,
  /** Densidad de píxeles máxima del lienzo (rendimiento en pantallas 3×). */
  pppMaximo: 2,
} as const;

/** Mezcla de la banda sonora sintetizada. */
export const SONIDO = {
  volumen: 0.85,
  /** Rumor grave (Hz). */
  rumor: [41.2, 41.6, 82.4],
  /** Acorde de marca: re mayor con novena (Hz). */
  acorde: [73.42, 110, 146.83, 185, 220, 329.63],
  /** Brillo agudo sobre el acorde (Hz). */
  brillo: [1174.66, 1760],
  /** Envío a la reverberación y duración de su cola (s). */
  reverberacion: 0.38,
  colaReverberacion: 2.8,
} as const;

/**
 * Franjas de luz con la paleta de los haces para rellenar las letras; así la
 * cortina del canvas y el logotipo comparten colores.
 */
export function franjasCss(): string {
  const paso = 0.035;
  const cortes = COLORES.haces.map(
    (color, i) => `${color} ${(i * paso).toFixed(3)}em ${((i + 1) * paso).toFixed(3)}em`,
  );
  return `repeating-linear-gradient(90deg, ${cortes.join(', ')})`;
}
