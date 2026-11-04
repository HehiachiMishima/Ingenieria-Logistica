import { COLORES, ESCENA, INTRO } from './intro-diseno';

/**
 * Escena de luz de la intro (canvas 2D): una línea roja nace desde abajo, se
 * abre en abanico, estalla en un túnel de haces, se ordena en una cortina
 * vertical y se cierra sobre el logotipo ATLAS. Cada cuadro se calcula solo a
 * partir del segundo de la línea de tiempo, así que puede empezar en cualquier
 * punto (movimiento reducido) sin estado acumulado. Colores, tiempos y
 * densidad salen de intro-diseno.ts.
 */

/** Caja del logotipo en píxeles CSS del lienzo. */
export interface Marco {
  x: number;
  y: number;
  ancho: number;
  alto: number;
}

interface Haz {
  /** Posición dentro del abanico (−1 a 1). */
  u: number;
  color: string;
  grosor: number;
  brillo: number;
  /** Retardo relativo (0–1) con el que entra al abrirse el abanico. */
  aparicion: number;
  /** Profundidad (0–1): paralaje de la cortina. */
  profundidad: number;
}

interface Mota {
  x: number;
  y: number;
  radio: number;
  alfa: number;
  velocidad: number;
}

const tramo = (t: number, desde: number, hasta: number): number =>
  Math.min(1, Math.max(0, (t - desde) / (hasta - desde)));
const suave = (x: number): number => x * x * (3 - 2 * x);
const salidaExpo = (x: number): number => (x >= 1 ? 1 : 1 - 2 ** (-10 * x));
const entradaCubica = (x: number): number => x * x * x;
const mezcla = (a: number, b: number, x: number): number => a + (b - a) * x;

/** Generador con semilla (mulberry32): la escena es idéntica en cada carga. */
function aleatorio(semilla: number): () => number {
  let s = semilla;
  return () => {
    s = (s + 0x6d2b79f5) | 0;
    let r = Math.imul(s ^ (s >>> 15), 1 | s);
    r = (r + Math.imul(r ^ (r >>> 7), 61 | r)) ^ r;
    return ((r ^ (r >>> 14)) >>> 0) / 4294967296;
  };
}

function crearHaces(cantidad: number): Haz[] {
  const azar = aleatorio(7);
  // El primero es la línea roja que abre la escena.
  const haces: Haz[] = [
    { u: 0, color: COLORES.linea, grosor: 4, brillo: 1, aparicion: 0, profundidad: 0.5 },
  ];
  for (let i = 1; i < cantidad; i++) {
    const ancho = azar() < 0.18;
    haces.push({
      u: azar() * 2 - 1,
      color: COLORES.haces[Math.floor(azar() * COLORES.haces.length)],
      grosor: ancho ? 9 + azar() * 12 : 1.2 + azar() * 4.5,
      brillo: ancho ? 0.12 + azar() * 0.18 : 0.35 + azar() * 0.55,
      aparicion: azar(),
      profundidad: azar(),
    });
  }
  return haces;
}

function crearMotas(cantidad: number): Mota[] {
  const azar = aleatorio(19);
  return Array.from({ length: cantidad }, () => ({
    x: azar(),
    y: azar(),
    radio: 0.6 + azar() * 2.2,
    alfa: 0.05 + azar() * 0.22,
    velocidad: 0.004 + azar() * 0.016,
  }));
}

export class IntroEscena {
  private ancho = 0;
  private alto = 0;
  private haces: Haz[] = [];
  private readonly motas = crearMotas(ESCENA.motas);
  private fondo: CanvasGradient | null = null;

  /** Caja del logotipo hacia la que se cierra la cortina. */
  marco: Marco | null = null;

  constructor(
    private readonly ctx: CanvasRenderingContext2D,
    private readonly reducido: boolean,
  ) {}

  redimensionar(): void {
    const lienzo = this.ctx.canvas;
    const ppp = Math.min(window.devicePixelRatio || 1, ESCENA.pppMaximo);
    this.ancho = lienzo.clientWidth;
    this.alto = lienzo.clientHeight;
    lienzo.width = Math.round(this.ancho * ppp);
    lienzo.height = Math.round(this.alto * ppp);
    this.ctx.setTransform(ppp, 0, 0, ppp, 0, 0);

    const cantidad = this.ancho < ESCENA.corteMovil ? ESCENA.hacesMovil : ESCENA.hacesEscritorio;
    if (this.haces.length !== cantidad) {
      this.haces = crearHaces(cantidad);
    }

    const radio = Math.hypot(this.ancho, this.alto) * 0.62;
    this.fondo = this.ctx.createRadialGradient(
      this.ancho / 2,
      this.alto * 0.55,
      0,
      this.ancho / 2,
      this.alto * 0.55,
      radio,
    );
    const [centro, medio, borde] = COLORES.fondo;
    this.fondo.addColorStop(0, centro);
    this.fondo.addColorStop(0.62, medio);
    this.fondo.addColorStop(1, borde);
  }

  dibujar(t: number): void {
    const { ctx } = this;
    ctx.globalCompositeOperation = 'source-over';
    ctx.globalAlpha = 1;
    ctx.fillStyle = this.fondo ?? COLORES.fondo[1];
    ctx.fillRect(0, 0, this.ancho, this.alto);
    this.dibujarMotas(t);
    if (this.reducido) {
      return;
    }

    ctx.globalCompositeOperation = 'lighter';
    if (t > INTRO.haz && t < INTRO.cortina + 0.6) {
      this.dibujarHaces(t);
    }
    if (t > INTRO.cortina + 0.1 && t < INTRO.marca + 0.45) {
      this.dibujarCortina(t);
    }
    if (t > INTRO.marca && t < INTRO.marca + 0.9) {
      this.dibujarDestello(t);
    }
    ctx.globalCompositeOperation = 'source-over';
    ctx.globalAlpha = 1;
  }

  /** Polvo en suspensión, como el bokeh del fondo de un plano de cine. */
  private dibujarMotas(t: number): void {
    const { ctx } = this;
    ctx.fillStyle = COLORES.motas;
    for (const [i, m] of this.motas.entries()) {
      const y = (((m.y - t * m.velocidad) % 1) + 1) % 1;
      const x = m.x + Math.sin(t * 0.4 + i) * 0.004;
      ctx.globalAlpha = m.alfa * (0.6 + 0.4 * Math.sin(t * 2.2 + i * 1.7));
      ctx.beginPath();
      ctx.arc(x * this.ancho, y * this.alto, m.radio, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  /** Haces desde un punto de fuga que sube del borde inferior, cruza el centro y se aleja hacia arriba. */
  private dibujarHaces(t: number): void {
    const { ctx, ancho, alto } = this;
    const abre = suave(tramo(t, INTRO.abanico, INTRO.tunel));
    const estalla = suave(tramo(t, INTRO.tunel, INTRO.cortina));
    const alza = entradaCubica(tramo(t, INTRO.cortina - 0.25, INTRO.cortina + 0.45));
    const apaga = 1 - tramo(t, INTRO.cortina + 0.2, INTRO.cortina + 0.55);
    const diagonal = Math.hypot(ancho, alto);

    const fx = ancho / 2 + ancho * 0.08 * alza;
    const fy = mezcla(
      mezcla(mezcla(alto * 1.04, alto * 0.82, abre), alto * 0.5, estalla),
      -alto * 3.5,
      alza,
    );
    // Media vuelta durante el estallido: el abanico que apuntaba arriba termina mirando abajo.
    const eje = -Math.PI / 2 + Math.PI * estalla + Math.max(0, t - INTRO.abanico) * 0.12;
    const aperturaCortina = Math.atan((ancho * 0.75) / (alto * 4));
    const apertura = mezcla(mezcla(0.55 * abre, Math.PI, estalla), aperturaCortina, alza);
    const conico = mezcla(0.12, 1, alza);
    const referencia = diagonal * 0.5;
    const r1 =
      Math.max(
        Math.hypot(fx, fy),
        Math.hypot(ancho - fx, fy),
        Math.hypot(fx, alto - fy),
        Math.hypot(ancho - fx, alto - fy),
      ) + 10;
    const r0Base = diagonal * 0.06 * estalla * (1 - alza);

    for (const [i, h] of this.haces.entries()) {
      let alfa: number;
      let r0 = r0Base;
      let fin = r1;
      if (i === 0) {
        // La línea roja inicial crece hasta cruzar la pantalla.
        fin = Math.min(r1, alto * 1.15 * salidaExpo(tramo(t, INTRO.haz, INTRO.abanico)));
        r0 = 0;
        alfa = 1;
      } else {
        const entrada = INTRO.abanico + h.aparicion * 0.6;
        alfa = h.brillo * suave(tramo(t, entrada, entrada + 0.3));
      }
      alfa *= apaga;
      if (alfa <= 0.01) {
        continue;
      }

      const angulo = eje + (h.u + 0.02 * Math.sin(t * 3 + i)) * apertura;
      const a = (h.grosor * (conico + (1 - conico) * (r0 / referencia))) / 2;
      const b = (h.grosor * (conico + (1 - conico) * (fin / referencia))) / 2;
      ctx.fillStyle = h.color;
      if (i === 0) {
        // Halo de la línea roja: sin él se lee como un trazo plano.
        ctx.globalAlpha = 0.16 * alfa;
        this.trazarHaz(fx, fy, angulo, r0, fin, a * 7, b * 7);
      }
      ctx.globalAlpha = alfa;
      this.trazarHaz(fx, fy, angulo, r0, fin, a, b);
    }

    // Núcleo oscuro del túnel: sin él el punto de fuga se quema a blanco.
    const nucleo = estalla * (1 - alza);
    if (nucleo > 0.01) {
      const radio = diagonal * 0.14;
      const sombra = ctx.createRadialGradient(fx, fy, 0, fx, fy, radio);
      sombra.addColorStop(0, `rgba(${COLORES.sombra}, ${0.95 * nucleo})`);
      sombra.addColorStop(1, `rgba(${COLORES.sombra}, 0)`);
      ctx.globalCompositeOperation = 'source-over';
      ctx.globalAlpha = 1;
      ctx.fillStyle = sombra;
      ctx.fillRect(fx - radio, fy - radio, radio * 2, radio * 2);
      ctx.globalCompositeOperation = 'lighter';
    }
  }

  /** Haz en forma de cuña desde el punto de fuga: semiancho `a` en `r0` y `b` en `r1`. */
  private trazarHaz(
    fx: number,
    fy: number,
    angulo: number,
    r0: number,
    r1: number,
    a: number,
    b: number,
  ): void {
    const dx = Math.cos(angulo);
    const dy = Math.sin(angulo);
    const { ctx } = this;
    ctx.beginPath();
    ctx.moveTo(fx + dx * r0 - dy * a, fy + dy * r0 + dx * a);
    ctx.lineTo(fx + dx * r1 - dy * b, fy + dy * r1 + dx * b);
    ctx.lineTo(fx + dx * r1 + dy * b, fy + dy * r1 - dx * b);
    ctx.lineTo(fx + dx * r0 + dy * a, fy + dy * r0 - dx * a);
    ctx.fill();
  }

  /** Cortina de franjas verticales que se cierra en la caja del logotipo. */
  private dibujarCortina(t: number): void {
    const { ctx, ancho, alto } = this;
    const marco = this.marco ?? {
      x: ancho * 0.2,
      y: alto * 0.4,
      ancho: ancho * 0.6,
      alto: alto * 0.2,
    };
    const visible =
      suave(tramo(t, INTRO.cortina + 0.1, INTRO.cortina + 0.5)) *
      (1 - tramo(t, INTRO.marca + 0.05, INTRO.marca + 0.45));
    const cierra = entradaCubica(tramo(t, INTRO.compresion, INTRO.marca));
    const baja = suave(tramo(t, INTRO.marca - 0.25, INTRO.marca));
    const centro = marco.x + marco.ancho / 2;
    const escala = (marco.ancho * 1.04) / ancho;
    const arriba = mezcla(0, marco.y, baja);
    const abajo = mezcla(alto, marco.y + marco.alto, baja);
    const avance = t - INTRO.cortina;

    for (const h of this.haces) {
      const x = ((h.u + 1) / 2) * 1.2 - 0.1 + avance * 0.06 * (h.profundidad - 0.5);
      const libre = x * ancho;
      const xFinal = mezcla(libre, centro + (libre - ancho / 2) * escala, cierra);
      const grosor = Math.max(1, h.grosor * 1.6 * mezcla(1, escala, cierra));
      ctx.globalAlpha = Math.min(1, h.brillo * 1.25) * visible;
      ctx.fillStyle = h.color;
      ctx.fillRect(xFinal - grosor / 2, arriba, grosor, abajo - arriba);
    }

    // Bordes superior e inferior en penumbra mientras la cortina ocupa la pantalla.
    const penumbra = (1 - baja) * visible;
    if (penumbra > 0.01) {
      ctx.globalCompositeOperation = 'source-over';
      ctx.globalAlpha = penumbra;
      for (const [y0, y1] of [
        [0, alto * 0.22],
        [alto, alto * 0.78],
      ] as const) {
        const borde = ctx.createLinearGradient(0, y0, 0, y1);
        borde.addColorStop(0, `rgb(${COLORES.sombra})`);
        borde.addColorStop(1, `rgba(${COLORES.sombra}, 0)`);
        ctx.fillStyle = borde;
        ctx.fillRect(0, Math.min(y0, y1), ancho, Math.abs(y1 - y0));
      }
      ctx.globalCompositeOperation = 'lighter';
    }
  }

  /** Destello del impacto: velo rojo y flare anamórfico horizontal. */
  private dibujarDestello(t: number): void {
    const { ctx, ancho, alto } = this;
    const f = tramo(t, INTRO.marca, INTRO.marca + 0.9);
    const y = this.marco ? this.marco.y + this.marco.alto / 2 : alto / 2;

    ctx.globalAlpha = 0.2 * (1 - f) ** 3;
    ctx.fillStyle = COLORES.destello;
    ctx.fillRect(0, 0, ancho, alto);

    const largo = ancho * 0.55 * salidaExpo(f * 1.6);
    const flare = ctx.createLinearGradient(ancho / 2 - largo, 0, ancho / 2 + largo, 0);
    flare.addColorStop(0, `rgba(${COLORES.flareBorde}, 0)`);
    flare.addColorStop(0.5, `rgb(${COLORES.flareCentro})`);
    flare.addColorStop(1, `rgba(${COLORES.flareBorde}, 0)`);
    ctx.fillStyle = flare;
    ctx.globalAlpha = (1 - f) ** 2;
    ctx.fillRect(ancho / 2 - largo, y - 1.5, largo * 2, 3);
    ctx.globalAlpha = 0.25 * (1 - f) ** 2;
    ctx.fillRect(ancho / 2 - largo, y - 14, largo * 2, 28);
  }
}

