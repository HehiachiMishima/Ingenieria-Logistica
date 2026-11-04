import { CentroMallaDatos, Proceso, Responsable } from './data/malla.model';

/** Procesos en el orden del flujo, con su nombre y su color (tokens del tema). */
export const PROCESOS: readonly { id: Proceso; nombre: string; color: string }[] = [
  { id: 'REC', nombre: 'Recepción', color: 'var(--success)' },
  { id: 'ALM', nombre: 'Almacenamiento', color: 'var(--violet)' },
  { id: 'REA', nombre: 'Reabastecimiento', color: 'var(--warning)' },
  { id: 'OLA', nombre: 'Picking', color: 'var(--danger)' },
  { id: 'DES', nombre: 'Despacho', color: 'var(--info)' },
];

export const RESPONSABLES: readonly { id: Responsable; nombre: string }[] = [
  { id: 'IL', nombre: 'Ingeniería Logística' },
  { id: 'OPS', nombre: 'Operaciones' },
];

export const MINUTOS_DIA = 1440;
/** Lo que dura un refrigerio a partir de su hora. */
export const MINUTOS_REFRIGERIO = 60;

export function proceso(id: Proceso) {
  return PROCESOS.find((p) => p.id === id) ?? PROCESOS[0];
}

/** "HH:mm" → minutos del día; null si no hay hora. */
export function minutos(hora: string | null | undefined): number | null {
  const partes = /^(\d{1,2}):(\d{2})/.exec(hora ?? '');
  return partes ? (Number(partes[1]) % 24) * 60 + Number(partes[2]) : null;
}

/** Minutos del día → "HH:mm" (acepta valores fuera de 0–1439). */
export function hora(valor: number | null): string {
  if (valor === null) {
    return '—';
  }
  const m = ((valor % MINUTOS_DIA) + MINUTOS_DIA) % MINUTOS_DIA;
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
}

/** Duración de a hasta b; si b es antes que a, cruza la medianoche. */
export function duracion(a: string | null, b: string | null): number | null {
  const x = minutos(a);
  const y = minutos(b);
  if (x === null || y === null) {
    return null;
  }
  const d = y - x;
  return d <= 0 ? d + MINUTOS_DIA : d;
}

export function formatearDuracion(valor: number | null): string {
  if (valor === null) {
    return '—';
  }
  if (valor < 60) {
    return `${valor} min`;
  }
  const resto = valor % 60;
  return `${Math.floor(valor / 60)} h${resto ? ` ${resto} min` : ''}`;
}

/** La hora t (minutos) cae en la ventana [a, b), que puede cruzar la medianoche. */
export function enVentana(t: number, a: string | null, b: string | null): boolean {
  const x = minutos(a);
  const y = minutos(b);
  if (x === null || y === null) {
    return false;
  }
  return y > x ? t >= x && t < y : t >= x || t < y;
}

/** Minutos que faltan desde t hasta m, dando la vuelta al día. */
export function faltan(t: number, m: number): number {
  return (((m - t) % MINUTOS_DIA) + MINUTOS_DIA) % MINUTOS_DIA;
}

/** La malla es de centros en Perú: «ahora» es la hora de Lima, no la del equipo. */
const RELOJ_LIMA = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'America/Lima',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

export function ahoraEnMinutos(fecha = new Date()): number {
  return minutos(RELOJ_LIMA.format(fecha)) ?? 0;
}

const NUMERO = new Intl.NumberFormat('es-PE');

export function formatearNumero(valor: number | null | undefined): string {
  return valor === null || valor === undefined ? '—' : NUMERO.format(valor);
}

/** Malla vacía para un centro nuevo: tres turnos de referencia y nada más. */
export function mallaVacia(): CentroMallaDatos {
  return {
    codigo: '',
    nombre: '',
    turnos: [
      turnoBase('T1', 'Turno 1', '07:00', '16:00', '13:00', 'Almuerzo'),
      turnoBase('T2', 'Turno 2', '13:00', '22:00', '19:00', 'Cena'),
      turnoBase('T3', 'Turno 3', '22:00', '06:00', '01:00', 'Cena'),
    ],
    flujos: [],
    olas: [],
    zonas: [],
    capacidad: {
      unidadesDia: null,
      lineasDia: null,
      bultosRecepcionDia: null,
      proveedoresDia: null,
    },
    dotacion: [],
    dotacionReal: { inbound: null, outbound: null, despacho: null, total: null, semana: null },
  };
}

function turnoBase(
  codigo: string,
  nombre: string,
  inicio: string,
  fin: string,
  refrigerio: string,
  tipoRefrigerio: string,
) {
  return { codigo, nombre, inicio, fin, refrigerio, tipoRefrigerio, nota: null, confirmado: false };
}
