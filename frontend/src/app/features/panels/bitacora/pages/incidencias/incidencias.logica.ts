import { duracionHhmm, fechaHoraNegocio } from '../../../../../core/utils/fecha.util';
import { Incidencia } from '../../data/bitacora.model';

/** Lógica pura de la página de incidencias de Bitácora (horas y exportación). */

export const CABECERAS_EXPORT = [
  'Ticket',
  'Descripción',
  'Centro',
  'Sistema',
  'Tipo',
  'Área',
  'Responsable',
  'Hora inicio',
  'Hora fin',
  'Tiempo de solución',
  'Creado',
  'Creado por',
  'Actualizado',
  'Actualizado por',
];

/** «08:30:00» → «08:30» (— si no hay). */
export function hhmm(valor: string | null): string {
  return valor ? valor.slice(0, 5) : '—';
}

/** «08:30» → «08:30:00», el formato que espera el backend. */
export function normalizarHora(valor: string): string {
  return valor.length === 5 ? `${valor}:00` : valor;
}

/** Fila del CSV de una incidencia; `actor` resuelve creado_por / actualizado_por. */
export function filaExportacion(
  incidencia: Incidencia,
  locale: string,
  actor: (id: number | null) => string,
): string[] {
  return [
    incidencia.ticket,
    incidencia.descripcion,
    incidencia.centro ?? '—',
    incidencia.sistema ?? '—',
    incidencia.tipoIncidencia ?? '—',
    incidencia.area ?? '—',
    incidencia.responsable ?? '—',
    incidencia.horaInicio,
    incidencia.horaFin,
    duracionHhmm(incidencia.tiempoSolucionSeg),
    fechaHoraNegocio(incidencia.creadoEn, locale),
    actor(incidencia.creadoPor),
    incidencia.actualizadoEn ? fechaHoraNegocio(incidencia.actualizadoEn, locale) : '—',
    actor(incidencia.actualizadoPor),
  ];
}
