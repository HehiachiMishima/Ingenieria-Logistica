/**
 * Lógica del panel Picking: lee el CSV del WMS (una fila por cartón) y arma
 * todo lo que muestran Resumen, Órdenes y SKU. Funciones puras, sin Angular.
 *
 * Reglas del negocio:
 * - Día operativo de 06:00 a 05:59 (hora de Lima). Las horas del archivo se
 *   tratan como hora de Lima en una escala sin zona (UTC ficticio).
 * - Una orden está completada si todos sus cartones están en Completed; sin
 *   iniciar si ninguno; en proceso si hay de ambos.
 * - UND. ORIG. se repite en cada cartón del mismo SKU y orden: se toma una vez
 *   y se reparte entre sus cartones según UND. ASIG. En cartones multi-SKU se
 *   usa lo asignado.
 * - Los cartones Cancelled no cuentan. TSC/TLI son traslados a otros CD.
 */

export const CONFIG = {
  inicioDiaOperativo: 6,
  estados: { completado: ['Completed'], pendiente: ['Allocated'], cancelado: ['Cancelled'] },
  multiSku: 'MULTI SKU',
  traslados: ['TSC', 'TLI'],
  nombresCluster: { 'Cluster 0': 'Cluster 0 TSC-TLI' } as Record<string, string>,
  clustersAlFinal: ['Cluster 0'],
  /** Cuadro «Avance MZ TITAN»: filas = grupos de AREA; columnas = clusters; cuenta líneas. */
  mz: {
    clusters: [
      ['CL1', 'Cluster 01'],
      ['CL2', 'Cluster 02'],
      ['CL3', 'Cluster 03'],
      ['CL4', 'Cluster 04'],
      ['CL5', 'Cluster 05'],
      ['CL6', 'Cluster 06'],
    ],
    secciones: [
      {
        nombre: 'B2C',
        filas: [
          ['DZ', ['DZ CASES']],
          ['MZ_PISO1', ['MZ_P1_UND', 'MZ_P1_CASES']],
          ['MZ_PISO2', ['MZ_P2_UND', 'MZ_P2_CASES']],
          ['MZ_PISO3', ['MZ_P3_UND', 'MZ_P3_CASES']],
        ],
      },
      {
        nombre: 'B2B',
        filas: [
          ['MZ_CASES', ['MZ CASES B2B']],
          ['MZ_UNIDS', ['MZ UNIDS B2B']],
        ],
      },
    ] as { nombre: string; filas: [string, string[]][] }[],
  },
};

export const SEG_OPE = 'Operación';
export const SEG_TRAS = 'Traslados TSC-TLI';
const DIA = 864e5;
const HORA = 36e5;

const COL = {
  fecha: 'FECHA ORDEN', creacion: 'FECHA CREACION', orden: 'ORDEN', tipo: 'TIPO DE ORDEN',
  negocio: 'NEGOCIO', ola: 'NRO. OLA', cluster: 'CLUSTER', area: 'AREA', carton: 'NRO. CARTON',
  estCarton: 'ESTADO CARTON', pallet: 'NRO. PALLET', estAsig: 'ESTADO DE ASIGNACION',
  inicio: 'HORA INICIO PICK', sku: 'SKU', ean: 'EAN', producto: 'PRODUCTO', uOrig: 'UND. ORIG.',
  uAsig: 'UND. ASIG.', uEmp: 'UND. EMPAC.', lineas: 'LINEAS ASIG.', usuario: 'USUA. PICK',
  rutaP: 'RUTA PRINCIPAL', rutaF: 'RUTA FINAL', suc: 'SUCURSAL DESTINO', sucNom: 'NOMBRE SUCURSAL',
  parada: 'PARADA', carga: 'NRO. CARGA', ubicI: 'UBIC. INICIAL PICK', ubicF: 'UBIC FINAL PICK',
  desc: 'DESCRIPCION MULTI',
} as const;
type Campo = keyof typeof COL;
const REQUERIDAS: Campo[] = ['fecha', 'orden', 'estAsig', 'estCarton', 'uAsig', 'uEmp', 'lineas', 'area', 'ola', 'cluster'];

/** Un cartón del archivo, ya interpretado. */
export interface Carton {
  fecha: string;
  fechaKey: number | null;
  creacion: number | null;
  orden: string;
  tipo: string;
  negocio: string;
  ola: string;
  cluster: string;
  area: string;
  carton: string;
  estCarton: string;
  pallet: string;
  estAsig: string;
  inicio: number | null;
  sku: string;
  ean: string;
  producto: string;
  uOrig: number;
  asig: number;
  emp: number;
  lin: number;
  usuario: string;
  rutaP: string;
  rutaF: string;
  suc: string;
  sucNom: string;
  parada: string;
  carga: string;
  ubicI: string;
  ubicF: string;
  multi: boolean;
  done: boolean;
  cancel: boolean;
  segmento: string;
  /** Unidades solicitadas y no asignadas que le tocan a este cartón (ver reparto de UND. ORIG.). */
  solic: number;
  falt: number;
  /** Hora del día operativo (0 = 06:00) en que empezó su pick; null si no empezó o cae fuera. */
  hora: number | null;
  busq: string;
}

export interface DatosPicking {
  cartones: Carton[];
  /** Día operativo (días desde 1970) = FECHA ORDEN más reciente. */
  opKey: number;
  /** Último evento del archivo (FECHA CREACION o HORA INICIO PICK). */
  ultimo: number;
  /** Órdenes de días anteriores que siguen en el archivo. */
  arrastre: number;
}

// ---------------- Formato y fechas ----------------

const NF = new Intl.NumberFormat('es-PE', { maximumFractionDigits: 0 });
const NF1 = new Intl.NumberFormat('es-PE', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const pad = (n: number) => String(n).padStart(2, '0');
export const nf = (x: number) => NF.format(Math.round(x || 0));
export const pct = (x: number) => (Number.isFinite(x) ? `${NF1.format(x * 100)} %` : '—');
export const hhmm = (ps: number | null) =>
  ps == null ? '—' : `${pad(new Date(ps).getUTCHours())}:${pad(new Date(ps).getUTCMinutes())}`;
export const fecha = (ps: number) => {
  const d = new Date(ps);
  return `${pad(d.getUTCDate())}/${pad(d.getUTCMonth() + 1)}/${d.getUTCFullYear()}`;
};
export const fechaHora = (ps: number | null) => (ps == null ? '—' : `${fecha(ps)} ${hhmm(ps)}`);
export const fechaDia = (key: number) => fecha(key * DIA);
export const etiquetaHora = (i: number) => `${pad((CONFIG.inicioDiaOperativo + i) % 24)}:00`;
export const nomCluster = (c: string) => CONFIG.nombresCluster[c] ?? c;

const FMT_LIMA = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'America/Lima', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
});
/** Instante real → «hora de Lima» en la escala sin zona del archivo. */
export function limaPs(ms: number): number {
  const p: Record<string, string> = {};
  for (const x of FMT_LIMA.formatToParts(new Date(ms))) p[x.type] = x.value;
  return Date.UTC(+p['year'], +p['month'] - 1, +p['day'], +p['hour'] % 24, +p['minute'], +p['second']);
}
export const diaOperativo = (ps: number) => Math.floor((ps - CONFIG.inicioDiaOperativo * HORA) / DIA);

const RX_FECHA = /^\s*(\d{1,2})\/(\d{1,2})\/(\d{4})(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?/;
function parseFecha(s: string): number | null {
  const m = RX_FECHA.exec(s || '');
  return m ? Date.UTC(+m[3], +m[2] - 1, +m[1], +(m[4] || 0), +(m[5] || 0), +(m[6] || 0)) : null;
}
const num = (s: string) => {
  const v = parseFloat(s);
  return Number.isFinite(v) ? v : 0;
};

// ---------------- Lectura del CSV ----------------

/** CSV con comillas (RFC 4180), rápido para ~10 MB. */
function parseCsv(t: string, sep: string): string[][] {
  const S = sep.charCodeAt(0);
  const filas: string[][] = [];
  let fila: string[] = [];
  let campo = '';
  let i = 0;
  let q = false;
  const n = t.length;
  while (i < n) {
    if (q) {
      const j = t.indexOf('"', i);
      if (j < 0) {
        campo += t.slice(i);
        break;
      }
      campo += t.slice(i, j);
      if (t.charCodeAt(j + 1) === 34) {
        campo += '"';
        i = j + 2;
      } else {
        q = false;
        i = j + 1;
      }
      continue;
    }
    const c = t.charCodeAt(i);
    if (c === 34) {
      q = true;
      i++;
    } else if (c === S) {
      fila.push(campo);
      campo = '';
      i++;
    } else if (c === 10) {
      fila.push(campo);
      filas.push(fila);
      fila = [];
      campo = '';
      i++;
    } else if (c === 13) {
      i++;
    } else {
      let j = i + 1;
      while (j < n) {
        const d = t.charCodeAt(j);
        if (d === S || d === 10 || d === 13 || d === 34) break;
        j++;
      }
      campo += t.slice(i, j);
      i = j;
    }
  }
  if (campo !== '' || fila.length) {
    fila.push(campo);
    filas.push(fila);
  }
  return filas;
}

export function construir(texto: string): DatosPicking {
  if (texto.charCodeAt(0) === 0xfeff) texto = texto.slice(1);
  const linea1 = texto.slice(0, texto.indexOf('\n'));
  const sep = linea1.split(';').length > linea1.split(',').length ? ';' : ',';
  const filas = parseCsv(texto, sep);
  if (!filas.length) throw new Error('El archivo está vacío.');
  const cab = filas[0].map((x) => x.trim());
  const ix = {} as Record<Campo, number>;
  for (const k of Object.keys(COL) as Campo[]) ix[k] = cab.indexOf(COL[k]);
  const faltan = REQUERIDAS.filter((k) => ix[k] < 0).map((k) => COL[k]);
  if (faltan.length) throw new Error(`Al archivo le faltan columnas: ${faltan.join(', ')}`);

  const hecho = new Set(CONFIG.estados.completado);
  const cancelado = new Set(CONFIG.estados.cancelado);
  const cartones: Carton[] = [];
  for (let i = 1; i < filas.length; i++) {
    const f = filas[i];
    if (f.length !== cab.length) continue;
    const g = (k: Campo) => (ix[k] >= 0 ? (f[ix[k]] || '').trim() : '');
    const tipo = g('tipo');
    const estAsig = g('estAsig');
    const desc = g('desc');
    const r: Carton = {
      fecha: g('fecha'), fechaKey: null, creacion: parseFecha(g('creacion')), orden: g('orden'), tipo,
      negocio: g('negocio'), ola: g('ola'), cluster: g('cluster'), area: g('area'), carton: g('carton'),
      estCarton: g('estCarton'), pallet: g('pallet'), estAsig, inicio: parseFecha(g('inicio')),
      sku: g('sku'), ean: g('ean'), producto: g('producto'), uOrig: num(g('uOrig')), asig: num(g('uAsig')),
      emp: num(g('uEmp')), lin: num(g('lineas')), usuario: g('usuario'), rutaP: g('rutaP'), rutaF: g('rutaF'),
      suc: g('suc'), sucNom: g('sucNom'), parada: g('parada'), carga: g('carga'), ubicI: g('ubicI'),
      ubicF: g('ubicF'), multi: desc === CONFIG.multiSku, done: hecho.has(estAsig), cancel: cancelado.has(estAsig),
      segmento: CONFIG.traslados.some((c) => tipo.toUpperCase().startsWith(c)) ? SEG_TRAS : SEG_OPE,
      solic: 0, falt: 0, hora: null, busq: '',
    };
    const p = parseFecha(r.fecha);
    r.fechaKey = p == null ? null : Math.floor(p / DIA);
    r.busq = `${r.orden}|${r.carton}|${r.sku}|${r.ean}|${r.producto}|${desc}`.toLowerCase();
    cartones.push(r);
  }
  const validos = cartones.filter((r) => !r.cancel);
  if (!validos.length) throw new Error('El archivo no tiene cartones válidos.');

  let opKey = -Infinity;
  let ultimo = -Infinity;
  for (const r of validos) {
    if (r.fechaKey != null && r.fechaKey > opKey) opKey = r.fechaKey;
    if (r.inicio != null && r.inicio > ultimo) ultimo = r.inicio;
    if (r.creacion != null && r.creacion > ultimo) ultimo = r.creacion;
  }
  const inicioVentana = opKey * DIA + CONFIG.inicioDiaOperativo * HORA;
  for (const r of cartones) {
    if (r.inicio == null) continue;
    const k = Math.floor((r.inicio - inicioVentana) / HORA);
    if (k >= 0 && k < 24) r.hora = k;
  }

  // UND. ORIG.: una vez por (orden, SKU) en cartones de un SKU, repartida según lo asignado.
  const pares = new Map<string, { simples: Carton[]; multi: boolean }>();
  for (const r of validos) {
    const k = `${r.orden}\u0001${r.sku}`;
    let par = pares.get(k);
    if (!par) pares.set(k, (par = { simples: [], multi: false }));
    if (r.multi) par.multi = true;
    else par.simples.push(r);
  }
  for (const r of validos) if (r.multi) r.solic = r.asig;
  for (const par of pares.values()) {
    if (!par.simples.length) continue;
    if (par.multi) {
      for (const r of par.simples) r.solic = r.asig;
      continue;
    }
    let orig = 0;
    let asig = 0;
    for (const r of par.simples) {
      orig = Math.max(orig, r.uOrig);
      asig += r.asig;
    }
    const falta = Math.max(0, orig - asig);
    for (const r of par.simples) {
      const w = asig > 0 ? r.asig / asig : 1 / par.simples.length;
      r.solic = orig * w;
      r.falt = falta * w;
    }
  }

  const ordenesAnteriores = new Set(validos.filter((r) => r.fechaKey !== opKey).map((r) => r.orden));
  return { cartones, opKey, ultimo, arrastre: ordenesAnteriores.size };
}

// ---------------- Filtros ----------------

/** Campos filtrables (los primarios se ven primero en el panel de filtros). */
export const CAMPOS = [
  { k: 'segmento', l: 'Segmento' },
  { k: 'negocio', l: 'Negocio' },
  { k: 'tipo', l: 'Tipo de orden' },
  { k: 'area', l: 'Área' },
  { k: 'cluster', l: 'Cluster' },
  { k: 'ola', l: 'Ola' },
  { k: 'estCarton', l: 'Estado cartón' },
  { k: 'estAsig', l: 'Estado asignación' },
  { k: 'usuario', l: 'Usuario pick' },
  { k: 'rutaP', l: 'Ruta principal' },
  { k: 'rutaF', l: 'Ruta final' },
  { k: 'suc', l: 'Sucursal destino' },
  { k: 'carga', l: 'Nro. carga' },
  { k: 'parada', l: 'Parada' },
] as const;
export type CampoFiltro = (typeof CAMPOS)[number]['k'];

export interface Filtros {
  campos: Partial<Record<CampoFiltro, string[]>>;
  busqueda: string;
  /** Rango de horas del día operativo (0 = 06:00), por HORA INICIO PICK. */
  horas: [number, number] | null;
  /** Incluir las órdenes de días anteriores que siguen en el archivo. */
  arrastre: boolean;
}

export const FILTROS_VACIOS: Filtros = { campos: {}, busqueda: '', horas: null, arrastre: false };

/** Cartones del día operativo (o todos, con arrastre), sin cancelados. */
export function universo(d: DatosPicking, arrastre: boolean): Carton[] {
  return d.cartones.filter((r) => !r.cancel && (arrastre || r.fechaKey === d.opKey));
}

export function filtrar(filas: Carton[], f: Filtros): Carton[] {
  const campos = Object.entries(f.campos)
    .filter(([, v]) => v && v.length)
    .map(([k, v]) => [k as CampoFiltro, new Set(v)] as const);
  const q = f.busqueda.trim().toLowerCase();
  if (!campos.length && !q && !f.horas) return filas;
  return filas.filter((r) => {
    for (const [k, s] of campos) if (!s.has(r[k])) return false;
    if (f.horas && (r.hora == null || r.hora < f.horas[0] || r.hora > f.horas[1])) return false;
    return !q || r.busq.includes(q);
  });
}

// ---------------- Agregación ----------------

export interface Grupo {
  key: string;
  cart: number;
  cartPend: number;
  asig: number;
  emp: number;
  solic: number;
  falt: number;
  lin: number;
  linDone: number;
  pend: number;
  pct: number;
  nOrd: number;
  nOrdPend: number;
  pctOrd: number;
  /** Segmento: SKU y órdenes con unidades no asignadas. */
  skuFalt: number;
  ordFalt: number;
}

export type EstadoOrden = 'completa' | 'en_proceso' | 'sin_iniciar';
export const ESTADO_ORDEN: Record<EstadoOrden, { texto: string; badge: string; orden: number }> = {
  completa: { texto: 'Completada', badge: 'ui-badge--success', orden: 3 },
  en_proceso: { texto: 'En proceso', badge: 'ui-badge--info', orden: 2 },
  sin_iniciar: { texto: 'Sin iniciar', badge: 'ui-badge--neutral', orden: 1 },
};

export interface Orden {
  orden: string;
  estado: EstadoOrden;
  segmento: string;
  tipo: string;
  negocio: string;
  cluster: string;
  suc: string;
  sucNom: string;
  fecha: string;
  creacion: number | null;
  cart: number;
  pend: number;
  done: number;
  asig: number;
  emp: number;
  solic: number;
  falt: number;
  lin: number;
  linDone: number;
  areas: Map<string, number>;
  olas: Set<string>;
  usuarios: Set<string>;
  rutaP: Set<string>;
  rutaF: Set<string>;
  cargas: Set<string>;
  paradas: Set<string>;
  estAsig: Map<string, number>;
  estCart: Map<string, number>;
  ini: number | null;
  fin: number | null;
  problemas: string[];
  cartones: Carton[];
}

const DIMS = ['area', 'ola', 'cluster', 'tipo', 'negocio', 'segmento', 'rutaP', 'rutaF'] as const;
export type Dim = (typeof DIMS)[number];

export interface Agregado {
  total: Grupo;
  ordenes: Map<string, Orden>;
  estados: Record<EstadoOrden, number>;
  negocios: [string, number][];
  dims: Record<Dim, Grupo[]>;
  /** Cluster → sus áreas. */
  cluArea: Map<string, Grupo[]>;
  /** Por hora del día operativo: cartones, unidades empacadas y líneas completadas. */
  evo: { cart: number[]; und: number[]; lin: number[] };
  nSku: number;
}

interface Acum {
  cart: number;
  cartPend: number;
  asig: number;
  emp: number;
  solic: number;
  falt: number;
  lin: number;
  linDone: number;
  ords: Map<string, number>;
  fSku: Set<string>;
  fOrd: Set<string>;
}
const acum = (): Acum => ({
  cart: 0, cartPend: 0, asig: 0, emp: 0, solic: 0, falt: 0, lin: 0, linDone: 0,
  ords: new Map(), fSku: new Set(), fOrd: new Set(),
});
function sumar(g: Acum, r: Carton): void {
  g.cart++;
  if (!r.done) g.cartPend++;
  g.asig += r.asig;
  g.emp += r.emp;
  g.solic += r.solic;
  g.falt += r.falt;
  g.lin += r.lin;
  if (r.done) g.linDone += r.lin;
  g.ords.set(r.orden, (g.ords.get(r.orden) || 0) + (r.done ? 0 : 1));
  if (r.falt > 0) {
    g.fSku.add(r.sku);
    g.fOrd.add(r.orden);
  }
}
function cerrar(key: string, g: Acum): Grupo {
  let abiertas = 0;
  for (const v of g.ords.values()) if (v > 0) abiertas++;
  const n = g.ords.size;
  return {
    key, cart: g.cart, cartPend: g.cartPend, asig: g.asig, emp: g.emp, solic: g.solic, falt: g.falt,
    lin: g.lin, linDone: g.linDone, pend: g.asig - g.emp, pct: g.asig ? g.emp / g.asig : 0,
    nOrd: n, nOrdPend: abiertas, pctOrd: n ? (n - abiertas) / n : 0, skuFalt: g.fSku.size, ordFalt: g.fOrd.size,
  };
}
const contar = (m: Map<string, number>, k: string) => m.set(k, (m.get(k) || 0) + 1);

export function agregar(filas: Carton[]): Agregado {
  const total = acum();
  const dims = Object.fromEntries(DIMS.map((d) => [d, new Map<string, Acum>()])) as Record<Dim, Map<string, Acum>>;
  const cluArea = new Map<string, Map<string, Acum>>();
  const ordenes = new Map<string, Orden>();
  const evo = { cart: new Array(24).fill(0), und: new Array(24).fill(0), lin: new Array(24).fill(0) };
  const skus = new Set<string>();
  for (const r of filas) {
    sumar(total, r);
    for (const d of DIMS) {
      let g = dims[d].get(r[d]);
      if (!g) dims[d].set(r[d], (g = acum()));
      sumar(g, r);
    }
    let areas = cluArea.get(r.cluster);
    if (!areas) cluArea.set(r.cluster, (areas = new Map()));
    let ca = areas.get(r.area);
    if (!ca) areas.set(r.area, (ca = acum()));
    sumar(ca, r);
    if (!r.multi) skus.add(r.sku);

    let o = ordenes.get(r.orden);
    if (!o) {
      ordenes.set(r.orden, (o = {
        orden: r.orden, estado: 'sin_iniciar', segmento: r.segmento, tipo: r.tipo, negocio: r.negocio,
        cluster: r.cluster, suc: r.suc, sucNom: r.sucNom, fecha: r.fecha, creacion: r.creacion,
        cart: 0, pend: 0, done: 0, asig: 0, emp: 0, solic: 0, falt: 0, lin: 0, linDone: 0,
        areas: new Map(), olas: new Set(), usuarios: new Set(), rutaP: new Set(), rutaF: new Set(),
        cargas: new Set(), paradas: new Set(), estAsig: new Map(), estCart: new Map(),
        ini: null, fin: null, problemas: [], cartones: [],
      }));
    }
    o.cart++;
    if (r.done) o.done++;
    else o.pend++;
    o.asig += r.asig;
    o.emp += r.emp;
    o.solic += r.solic;
    o.falt += r.falt;
    o.lin += r.lin;
    if (r.done) o.linDone += r.lin;
    contar(o.areas, r.area);
    o.olas.add(r.ola);
    if (r.usuario) o.usuarios.add(r.usuario);
    if (r.rutaP) o.rutaP.add(r.rutaP);
    if (r.rutaF) o.rutaF.add(r.rutaF);
    if (r.carga) o.cargas.add(r.carga);
    if (r.parada) o.paradas.add(r.parada);
    contar(o.estAsig, r.estAsig);
    contar(o.estCart, r.estCarton);
    if (r.inicio != null) {
      if (o.ini == null || r.inicio < o.ini) o.ini = r.inicio;
      if (o.fin == null || r.inicio > o.fin) o.fin = r.inicio;
    }
    if (r.creacion != null && (o.creacion == null || r.creacion < o.creacion)) o.creacion = r.creacion;
    o.cartones.push(r);
    if (r.done && r.hora != null) {
      evo.cart[r.hora]++;
      evo.und[r.hora] += r.emp;
      evo.lin[r.hora] += r.lin;
    }
  }

  const estados: Record<EstadoOrden, number> = { completa: 0, en_proceso: 0, sin_iniciar: 0 };
  const negocios = new Map<string, number>();
  for (const o of ordenes.values()) {
    o.estado = o.pend === 0 ? 'completa' : o.done === 0 ? 'sin_iniciar' : 'en_proceso';
    estados[o.estado]++;
    contar(negocios, o.negocio);
    const sinUsuario = o.cartones.filter((r) => r.done && (!r.usuario || r.inicio == null)).length;
    if (sinUsuario) o.problemas.push(`${sinUsuario} cartón(es) completado(s) sin usuario u hora de inicio`);
    if (!o.cargas.size) o.problemas.push('sin número de carga');
    if (!o.rutaP.size) o.problemas.push('sin ruta principal');
  }
  const cerrarMapa = (m: Map<string, Acum>) => [...m].map(([k, g]) => cerrar(k, g));
  return {
    total: cerrar('total', total),
    ordenes,
    estados,
    negocios: [...negocios].sort((a, b) => b[1] - a[1]),
    dims: Object.fromEntries(DIMS.map((d) => [d, cerrarMapa(dims[d])])) as Record<Dim, Grupo[]>,
    cluArea: new Map([...cluArea].map(([c, m]) => [c, cerrarMapa(m)])),
    evo,
    nSku: skus.size,
  };
}

export type OrdenGrupos = 'pend' | 'avance' | 'volumen' | 'abiertas';
const ORDENAR: Record<OrdenGrupos, (a: Grupo, b: Grupo) => number> = {
  pend: (a, b) => b.pend - a.pend,
  avance: (a, b) => a.pct - b.pct || b.asig - a.asig,
  volumen: (a, b) => b.asig - a.asig,
  abiertas: (a, b) => b.nOrdPend - a.nOrdPend || b.pend - a.pend,
};
export function ordenarGrupos(gs: Grupo[], orden: OrdenGrupos, alFinal = false): Grupo[] {
  const lista = gs.slice().sort(
    (a, b) => ORDENAR[orden](a, b) || a.key.localeCompare(b.key, 'es', { numeric: true }),
  );
  if (!alFinal) return lista;
  const fin = new Set(CONFIG.clustersAlFinal);
  return [...lista.filter((g) => !fin.has(g.key)), ...lista.filter((g) => fin.has(g.key))];
}

/** Cuadro «Avance MZ TITAN»: líneas por grupo de área y pendientes por cluster. */
export function cuadroMz(filas: Carton[]) {
  const porArea = new Map<string, Carton[]>();
  for (const r of filas) {
    let a = porArea.get(r.area);
    if (!a) porArea.set(r.area, (a = []));
    a.push(r);
  }
  return CONFIG.mz.secciones.map((sec) => {
    const tot = { asig: 0, emp: 0, pend: 0, porCl: {} as Record<string, number> };
    const filasSec = sec.filas.map(([nombre, areas]) => {
      const f = { nombre, areas, asig: 0, emp: 0, pend: 0, porCl: {} as Record<string, number> };
      for (const a of areas) {
        for (const r of porArea.get(a) || []) {
          f.asig += r.lin;
          if (r.done) f.emp += r.lin;
          else f.porCl[r.cluster] = (f.porCl[r.cluster] || 0) + r.lin;
        }
      }
      f.pend = f.asig - f.emp;
      tot.asig += f.asig;
      tot.emp += f.emp;
      tot.pend += f.pend;
      for (const [k, v] of Object.entries(f.porCl)) tot.porCl[k] = (tot.porCl[k] || 0) + v;
      return f;
    });
    return { nombre: sec.nombre, filas: filasSec, tot, avance: tot.asig ? tot.emp / tot.asig : 0 };
  });
}

// ---------------- SKU ----------------

export interface SkuResumen {
  sku: string;
  ean: string;
  producto: string;
  ordenes: Set<string>;
  cart: number;
  cartPend: number;
  solic: number;
  asig: number;
  emp: number;
  falt: number;
  pend: number;
  conFalt: boolean;
  conPend: boolean;
}

/** SKU de los cartones de un SKU; los multi-SKU se suman aparte (el archivo trae un SKU por cartón). */
export function agregarSkus(filas: Carton[]) {
  const m = new Map<string, SkuResumen>();
  const multi = { cart: 0, asig: 0, emp: 0, ordenes: new Set<string>() };
  for (const r of filas) {
    if (r.multi) {
      multi.cart++;
      multi.asig += r.asig;
      multi.emp += r.emp;
      multi.ordenes.add(r.orden);
      continue;
    }
    let k = m.get(r.sku);
    if (!k) {
      m.set(r.sku, (k = {
        sku: r.sku, ean: r.ean, producto: r.producto, ordenes: new Set(), cart: 0, cartPend: 0,
        solic: 0, asig: 0, emp: 0, falt: 0, pend: 0, conFalt: false, conPend: false,
      }));
    }
    k.ordenes.add(r.orden);
    k.cart++;
    if (!r.done) k.cartPend++;
    k.solic += r.solic;
    k.asig += r.asig;
    k.emp += r.emp;
    k.falt += r.falt;
  }
  const lista = [...m.values()];
  for (const k of lista) {
    k.pend = k.asig - k.emp;
    k.conFalt = k.falt > 0.5;
    k.conPend = k.pend > 0;
  }
  return { lista, multi };
}

/** Ordena por una clave numérica o de texto; desempata por `id`. */
export function ordenarPor<T>(lista: T[], valor: (x: T) => number | string, dir: 1 | -1, id: (x: T) => string): T[] {
  return lista.sort((a, b) => {
    const x = valor(a);
    const y = valor(b);
    const c = typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y), 'es', { numeric: true });
    return c * dir || id(a).localeCompare(id(b));
  });
}
