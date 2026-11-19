import { EndpointMonitoreo, Periodo } from '../data/observabilidad.model';
import { OpcionPeriodo } from './selector-periodo';

/** Meta de tiempo de respuesta (p95) por API, en ms. */
export const META_P95_MS = 500;
/** Meta de disponibilidad: peticiones sin error 5xx. */
export const META_DISPONIBILIDAD = 0.995;
/** Por debajo de este volumen, el p95 de una API es ruido y no entra al ranking de lentas. */
export const MIN_PETICIONES_RANKING = 20;
/** Meta de cambios sin incidente (ni fallidos ni denegados) sobre el total de intentos. */
export const META_SIN_INCIDENTE = 0.95;
/** Accesos denegados que se toleran por semana antes de pasar a riesgo. */
export const TOLERANCIA_DENEGADOS_SEMANA = 5;
/** Horario laboral (hora de Lima): lunes a viernes de 8 a 20 h. Fuera de él, un cambio es alerta. */
export const HORARIO_LABORAL = { hastaDia: 5, desde: 8, hasta: 20 } as const;

export const DIAS_SEMANA = ['Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb', 'Dom'];
/** Franjas de 4 horas de los mapas de calor día × franja. */
export const HORAS_POR_FRANJA = 4;
export const FRANJAS = [0, 4, 8, 12, 16, 20];

export function etiquetaFranja(desde: number): string {
  return `${desde}–${desde + HORAS_POR_FRANJA} h`;
}

export function enHorarioLaboral(dia: number, desde: number): boolean {
  return dia <= HORARIO_LABORAL.hastaDia && desde >= HORARIO_LABORAL.desde && desde + HORAS_POR_FRANJA <= HORARIO_LABORAL.hasta;
}

export const PERIODOS_MONITOREO: OpcionPeriodo<Periodo>[] = [
  { valor: 1, etiqueta: '24 h' },
  { valor: 7, etiqueta: '7 días' },
  { valor: 30, etiqueta: '30 días' },
];

export const PERIODOS_AUDITORIA: OpcionPeriodo<Periodo>[] = [
  ...PERIODOS_MONITOREO,
  { valor: 90, etiqueta: '90 días' },
];

export type Nivel = 'ok' | 'aviso' | 'riesgo';

export const COLOR_NIVEL: Record<Nivel, string> = {
  ok: 'var(--success)',
  aviso: 'var(--warning)',
  riesgo: 'var(--danger)',
};

export const BADGE_NIVEL: Record<Nivel, string> = {
  ok: 'ui-badge--success',
  aviso: 'ui-badge--warning',
  riesgo: 'ui-badge--danger',
};

/** Semáforo de una API: crítica por encima de 1,5 × la meta o con más del 5 % de errores. */
export function nivelApi(e: Pick<EndpointMonitoreo, 'p95' | 'porcentaje'>): Nivel {
  if (e.p95 > META_P95_MS * 1.5 || e.porcentaje > 5) {
    return 'riesgo';
  }
  return e.p95 > META_P95_MS || e.porcentaje > 2 ? 'aviso' : 'ok';
}

export const TEXTO_NIVEL_API: Record<Nivel, string> = { ok: 'En meta', aviso: 'Atención', riesgo: 'Crítico' };

/** Clase de insignia para un estado HTTP (sin estado = traza purgada). */
export function badgeEstado(estado: number | null | undefined): string {
  if (estado == null) {
    return 'ui-badge--neutral';
  }
  if (estado >= 500) {
    return 'ui-badge--danger';
  }
  return estado >= 400 ? 'ui-badge--warning' : 'ui-badge--success';
}

export function badgeMetodo(metodo: string | null | undefined): string {
  switch (metodo) {
    case 'GET':
      return 'ui-badge--info';
    case 'DELETE':
      return 'ui-badge--danger';
    case 'POST':
      return 'ui-badge--brand';
    default:
      return 'ui-badge--neutral';
  }
}

export const BADGE_RESULTADO: Record<string, string> = {
  Éxito: 'ui-badge--success',
  Fallo: 'ui-badge--warning',
  Denegado: 'ui-badge--danger',
};

/** Etiqueta del eje X: «14 h» en la vista de 24 h; el día ISO se deja para el gráfico. */
export function etiquetaPeriodo(periodo: string): string {
  const hora = periodo.split('T')[1];
  return hora ? `${Number(hora.slice(0, 2))} h` : periodo;
}

/** Corta un texto largo (correos, nombres de 60+ caracteres) para que quepa en una tarjeta. */
export function abreviar(texto: string, maximo: number): string {
  return texto.length > maximo ? `${texto.slice(0, maximo - 1)}…` : texto;
}

/** Iniciales para el avatar a partir del nombre de usuario («jperez» → «JP», «ana.ruiz» → «AR»). */
export function inicialesDe(usuario: string | null | undefined): string {
  const partes = (usuario ?? '').split(/[.\-_@\s]+/).filter(Boolean);
  const texto = partes.length > 1 ? partes[0][0] + partes[1][0] : (partes[0] ?? '?').slice(0, 2);
  return texto.toUpperCase();
}
