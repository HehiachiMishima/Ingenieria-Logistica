import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { compacto, entero, porcentaje } from '../../../../../../../shared/ui/charts/formato';
import { ResumenActividad, ResumenMonitoreo } from '../../../../data/observabilidad.model';
import { META_P95_MS, Nivel } from '../../../../ui/observabilidad.util';
import {
  Delta,
  LIMITE_ERROR,
  TEXTO_NIVEL,
  columnaRedondeada,
  compactoEje,
  deltaPuntos,
  deltaRelativo,
  etiquetaEje,
  etiquetaLarga,
  maximoRedondo,
  medirAncho,
  nivelDisponibilidad,
} from './grafico.util';

interface Dia {
  periodo: string;
  eje: string;
  larga: string;
  peticiones: number | null;
  tasa: number | null;
  p95: number | null;
  usuarios: number | null;
}

interface Marca {
  x: number;
  y: number;
}

interface Panel {
  clave: 'peticiones' | 'p95' | 'usuarios';
  titulo: string;
  y: number;
  alto: number;
  guias: { y: number; texto: string; base: boolean }[];
  /** Anotación del peor caso, en la cabecera de la franja (alineada a la derecha). */
  aviso?: string;
}

/** Formato de las marcas de un eje: un solo estilo por eje, compacto si llega a 10 000. */
const marcaEje = (maximo: number) => (maximo >= 10_000 ? (v: number) => (v ? compactoEje(v) : '0') : (v: number) => entero(v));

/** Alto de cada franja (px) y espacio de su título. */
const ALTO = { peticiones: 96, p95: 84, usuarios: 72 } as const;
const TITULO = 34;
const PIE = 26;

/**
 * Pulso de la plataforma: la disponibilidad como cifra protagonista y, debajo, tres franjas
 * alineadas por día (peticiones, tiempo p95 contra la meta y usuarios activos) con una sola
 * línea de lectura que las recorre a la vez: un pico de lentitud se ve junto a la demanda de
 * ese día. Se dibuja al ancho real (textos nítidos) y se recorre con mouse, toque o flechas.
 */
@Component({
  selector: 'app-pulso-plataforma',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './pulso-plataforma.html',
  styleUrl: './pulso-plataforma.scss',
})
export class PulsoPlataforma {
  readonly monitoreo = input<ResumenMonitoreo | null>(null);
  readonly actividad = input<ResumenActividad | null>(null);
  readonly cuentasActivas = input<number | null>(null);
  readonly dias = input(30);

  readonly ancho = medirAncho();
  /** Día bajo el puntero o el teclado. */
  readonly activo = signal<number | null>(null);
  readonly meta = META_P95_MS;

  readonly serie = computed<Dia[]>(() => {
    const porPeriodo = new Map<string, Dia>();
    const dia = (periodo: string): Dia => {
      let d = porPeriodo.get(periodo);
      if (!d) {
        d = { periodo, eje: etiquetaEje(periodo), larga: etiquetaLarga(periodo), peticiones: null, tasa: null, p95: null, usuarios: null };
        porPeriodo.set(periodo, d);
      }
      return d;
    };
    for (const p of this.monitoreo()?.serie ?? []) {
      const d = dia(p.periodo);
      d.peticiones = p.peticiones;
      d.tasa = p.peticiones ? p.errores / p.peticiones : null;
      d.p95 = p.peticiones ? p.p95 : null;
    }
    for (const p of this.actividad()?.serie ?? []) {
      dia(p.periodo).usuarios = p.usuarios;
    }
    return [...porPeriodo.values()].sort((a, b) => a.periodo.localeCompare(b.periodo));
  });

  readonly porHora = computed(() => this.serie().some((d) => d.periodo.includes('T')));

  /** Cifra protagonista: la disponibilidad (o los usuarios activos si no hay monitoreo). */
  readonly protagonista = computed(() => {
    const m = this.monitoreo();
    const a = this.actividad();
    if (m?.peticiones) {
      const valor = 1 - m.erroresServidor / m.peticiones;
      const previa = m.peticionesPrevias ? 1 - m.erroresServidorPrevios / m.peticionesPrevias : null;
      const nivel = nivelDisponibilidad(valor);
      return {
        valor: porcentaje(valor),
        etiqueta: `Disponibilidad · ${this.textoPeriodo()}`,
        delta: deltaPuntos(valor, previa, false),
        nivel,
        textoNivel: TEXTO_NIVEL[nivel],
      };
    }
    if (a) {
      return {
        valor: entero(a.usuarios),
        etiqueta: `Usuarios activos · ${this.textoPeriodo()}`,
        delta: deltaRelativo(a.usuarios, a.usuariosPrevios, false),
        nivel: null as Nivel | null,
        textoNivel: '',
      };
    }
    return null;
  });

  readonly cifras = computed(() => {
    const m = this.monitoreo();
    const a = this.actividad();
    const lista: { etiqueta: string; valor: string; nota: string; delta: Delta | null }[] = [];
    if (m?.peticiones) {
      lista.push({
        etiqueta: 'Respuesta p95',
        valor: `${entero(m.p95)} ms`,
        nota: `meta ${entero(META_P95_MS)} ms`,
        delta: deltaRelativo(m.p95, m.p95Previo, true),
      });
      const tasa = m.errores / m.peticiones;
      lista.push({
        etiqueta: 'Tasa de error',
        valor: porcentaje(tasa),
        nota: `límite ${porcentaje(LIMITE_ERROR)}`,
        delta: deltaPuntos(tasa, m.peticionesPrevias ? m.erroresPrevios / m.peticionesPrevias : null, true),
      });
      lista.push({
        etiqueta: 'Peticiones',
        valor: compacto(m.peticiones),
        nota: 'a las APIs',
        delta: deltaRelativo(m.peticiones, m.peticionesPrevias, false),
      });
    }
    if (a && m?.peticiones) {
      const cuentas = this.cuentasActivas();
      lista.push({
        etiqueta: 'Usuarios activos',
        valor: entero(a.usuarios),
        nota: cuentas ? `${porcentaje(a.usuarios / cuentas)} de las cuentas` : 'con al menos una petición',
        delta: deltaRelativo(a.usuarios, a.usuariosPrevios, false),
      });
    }
    return lista;
  });

  /** Toda la geometría del gráfico, al ancho real del componente. */
  readonly g = computed(() => {
    const ancho = this.ancho();
    const serie = this.serie();
    const n = serie.length;
    if (ancho < 200 || !n) {
      return null;
    }
    const conMonitoreo = serie.some((d) => d.peticiones !== null);
    const conActividad = serie.some((d) => d.usuarios !== null);
    // Máximos de cada eje y su formato (compacto en todo el eje si llega a 10 000).
    const maxPet = maximoRedondo(Math.max(0, ...serie.map((d) => d.peticiones ?? 0)));
    const maxP95 = maximoRedondo(Math.max(META_P95_MS * 1.3, ...serie.map((d) => d.p95 ?? 0)));
    const maxUsu = maximoRedondo(Math.max(0, ...serie.map((d) => d.usuarios ?? 0)));
    const marcas = [conMonitoreo ? maxPet : 0, conMonitoreo ? maxP95 : 0, conActividad ? maxUsu : 0];
    // A la izquierda cabe la marca más larga de los ejes.
    const largoMarca = Math.max(1, ...marcas.map((m) => marcaEje(m)(m).length));
    const L = Math.max(ancho < 420 ? 34 : 42, Math.ceil(14 + largoMarca * 6.4));
    // A la derecha cabe el último valor de usuarios (su etiqueta va junto al punto final).
    const ultimoUsuarios = [...serie].reverse().find((d) => d.usuarios !== null)?.usuarios ?? null;
    const R = ultimoUsuarios === null ? 14 : Math.max(30, Math.ceil(16 + marcaEje(maxUsu)(ultimoUsuarios).length * 6.6));
    const anchoUtil = ancho - L - R;
    const banda = anchoUtil / n;
    const cx = (i: number) => L + banda * (i + 0.5);
    const unidad = this.porHora() ? 'hora' : 'día';

    const paneles: Panel[] = [];
    let y = TITULO;
    const panel = (clave: Panel['clave'], titulo: string, maximo: number, texto: (v: number) => string): Panel => {
      const alto = ALTO[clave];
      const p: Panel = {
        clave,
        titulo,
        y,
        alto,
        guias: [0, maximo / 2, maximo].map((v) => ({ y: y + alto - (v / maximo) * alto, texto: texto(v), base: v === 0 })),
      };
      paneles.push(p);
      y += alto + TITULO;
      return p;
    };
    const escalaY = (p: Panel, maximo: number) => (v: number) => p.y + p.alto - (v / maximo) * p.alto;

    let columnas: string[] = [];
    let erroresAltos: Marca[] = [];
    let lineaP95 = '';
    let lentos: Marca[] = [];
    let meta: { y: number; texto: string } | null = null;
    let yP95: ((v: number) => number) | null = null;
    // Anotaciones del peor caso: con fecha si hay espacio; muy angosto, solo los puntos (el
    // detalle sigue en la lectura y en la tabla).
    const anotar = ancho >= 420;
    const anotarDia = ancho >= 600;

    if (conMonitoreo) {
      const pp = panel('peticiones', `Peticiones por ${unidad}`, maxPet, marcaEje(maxPet));
      const yPet = escalaY(pp, maxPet);
      const anchoCol = Math.min(24, Math.max(2, banda * 0.62));
      const base = pp.y + pp.alto;
      columnas = serie.map((d, i) =>
        d.peticiones ? columnaRedondeada(cx(i) - anchoCol / 2, yPet(d.peticiones), anchoCol, base) : '',
      );
      // Días con más del 2 % de error: punto rojo sobre su columna; el peor, anotado en la
      // cabecera de la franja (nunca encima de las columnas).
      const conError = serie.map((d, i) => ({ d, i })).filter(({ d }) => d.tasa !== null && d.tasa > LIMITE_ERROR);
      erroresAltos = conError.map(({ d, i }) => ({ x: cx(i), y: yPet(d.peticiones ?? 0) - 9 }));
      if (conError.length && anotar) {
        const peor = conError.reduce((a, b) => ((b.d.tasa ?? 0) > (a.d.tasa ?? 0) ? b : a));
        pp.aviso = `⚠ ${porcentaje(peor.d.tasa ?? 0)} de error${anotarDia ? ` · ${peor.d.larga}` : ''}`;
      }

      // Angosto: la meta va en el título (un rótulo sobre la línea chocaría con sus picos).
      const tituloP95 = anotar ? `Respuesta p95 (ms) por ${unidad}` : `p95 (ms) · meta ${entero(META_P95_MS)}`;
      const p2 = panel('p95', tituloP95, maxP95, marcaEje(maxP95));
      yP95 = escalaY(p2, maxP95);
      const yp = yP95;
      lineaP95 = this.trazo(serie.map((d, i) => (d.p95 === null ? null : [cx(i), yp(d.p95)])));
      meta = { y: yp(META_P95_MS), texto: anotar ? `Meta ${entero(META_P95_MS)} ms` : '' };
      const sobre = serie.map((d, i) => ({ d, i })).filter(({ d }) => d.p95 !== null && d.p95 > META_P95_MS);
      lentos = sobre.map(({ d, i }) => ({ x: cx(i), y: yp(d.p95 ?? 0) }));
      if (sobre.length && anotar) {
        const peor = sobre.reduce((a, b) => ((b.d.p95 ?? 0) > (a.d.p95 ?? 0) ? b : a));
        const cuantos = `${sobre.length} ${unidad}${sobre.length === 1 ? '' : 's'}`;
        p2.aviso = `⚠ ${cuantos} sobre la meta · máx. ${entero(peor.d.p95 ?? 0)} ms${anotarDia ? ` (${peor.d.larga})` : ''}`;
      }
    }

    let lineaUsuarios = '';
    let areaUsuarios = '';
    let fin: (Marca & { texto: string }) | null = null;
    let yUsu: ((v: number) => number) | null = null;
    if (conActividad) {
      const p3 = panel('usuarios', `Usuarios activos por ${unidad}`, maxUsu, marcaEje(maxUsu));
      yUsu = escalaY(p3, maxUsu);
      const yu = yUsu;
      const puntos = serie.map((d, i) => (d.usuarios === null ? null : ([cx(i), yu(d.usuarios)] as [number, number])));
      lineaUsuarios = this.trazo(puntos);
      const validos = puntos.filter((p): p is [number, number] => p !== null);
      if (validos.length > 1) {
        const base = p3.y + p3.alto;
        areaUsuarios = `M${validos[0][0]},${base}L${validos.map((p) => p.join(',')).join('L')}L${validos.at(-1)![0]},${base}Z`;
      }
      const ultimo = [...serie.keys()].reverse().find((i) => serie[i].usuarios !== null);
      if (ultimo !== undefined) {
        fin = { x: cx(ultimo), y: yu(serie[ultimo].usuarios ?? 0), texto: marcaEje(maxUsu)(serie[ultimo].usuarios ?? 0) };
      }
    }

    const caben = Math.max(2, Math.floor(anchoUtil / 64));
    const cada = Math.max(1, Math.ceil(n / caben));
    const ejeX = serie
      .map((d, i) => ({ x: cx(i), texto: d.eje, i }))
      .filter((e) => (n - 1 - e.i) % cada === 0);

    return {
      ancho,
      alto: y - TITULO + PIE + 4,
      L,
      R,
      banda,
      cx,
      paneles,
      columnas,
      erroresAltos,
      lineaP95,
      lentos,
      meta,
      yP95,
      lineaUsuarios,
      areaUsuarios,
      fin,
      yUsu,
      ejeX,
      ejeY: y - TITULO + 18,
      arriba: TITULO - 12,
      abajo: y - TITULO,
    };
  });

  /** Lo que muestra la línea de lectura del día activo. */
  readonly lectura = computed(() => {
    const g = this.g();
    const i = this.activo();
    if (!g || i === null) {
      return null;
    }
    const d = this.serie()[i];
    const x = g.cx(i);
    return {
      x,
      derecha: x > g.ancho - 190,
      // Angosto: la lectura va a todo el ancho del gráfico (al lado del cursor no cabría).
      ancho: g.ancho < 480,
      dia: d,
      marcaP95: g.yP95 && d.p95 !== null ? g.yP95(d.p95) : null,
      marcaUsuarios: g.yUsu && d.usuarios !== null ? g.yUsu(d.usuarios) : null,
      lento: d.p95 !== null && d.p95 > META_P95_MS,
      errorAlto: d.tasa !== null && d.tasa > LIMITE_ERROR,
    };
  });

  readonly resumen = computed(() => {
    const serie = this.serie();
    if (!serie.length) {
      return 'Sin datos en el período.';
    }
    const lentos = serie.filter((d) => d.p95 !== null && d.p95 > META_P95_MS).length;
    const p = this.protagonista();
    return (
      `${p ? `${p.etiqueta}: ${p.valor}. ` : ''}` +
      `${serie.length} ${this.porHora() ? 'horas' : 'días'}; ` +
      `${lentos ? `${lentos} sobre la meta de ${entero(META_P95_MS)} ms.` : 'todos dentro de la meta de respuesta.'} ` +
      'Usa las flechas para recorrer los días.'
    );
  });

  readonly entero = entero;
  readonly porcentaje = porcentaje;

  mover(evento: PointerEvent): void {
    const g = this.g();
    if (!g) {
      return;
    }
    const caja = (evento.currentTarget as Element).getBoundingClientRect();
    // Sobre los márgenes se lee el día más cercano (el borde también es parte del gráfico).
    const i = Math.floor((evento.clientX - caja.left - g.L) / g.banda);
    this.activo.set(Math.min(this.serie().length - 1, Math.max(0, i)));
  }

  tecla(evento: KeyboardEvent): void {
    const n = this.serie().length;
    const actual = this.activo();
    const mover: Record<string, number> = { ArrowRight: 1, ArrowLeft: -1 };
    if (evento.key in mover) {
      evento.preventDefault();
      const base = actual ?? (mover[evento.key] > 0 ? -1 : n);
      this.activo.set(Math.min(n - 1, Math.max(0, base + mover[evento.key])));
    } else if (evento.key === 'Home' || evento.key === 'End') {
      evento.preventDefault();
      this.activo.set(evento.key === 'Home' ? 0 : n - 1);
    } else if (evento.key === 'Escape') {
      this.activo.set(null);
    }
  }

  private textoPeriodo(): string {
    const d = this.dias();
    return d === 1 ? 'últimas 24 h' : `últimos ${d} días`;
  }

  /** Línea que se corta donde falta el dato (no inventa valores entre huecos). */
  private trazo(puntos: ([number, number] | null)[]): string {
    let d = '';
    let nuevo = true;
    for (const p of puntos) {
      if (!p) {
        nuevo = true;
        continue;
      }
      d += `${nuevo ? 'M' : 'L'}${p[0].toFixed(1)},${p[1].toFixed(1)}`;
      nuevo = false;
    }
    return d;
  }
}
