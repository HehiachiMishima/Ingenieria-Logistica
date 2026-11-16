import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';
import { diaDeInstante, duracionHhmm } from '../../../../../core/utils/fecha.util';
import { Incidencia } from '../../data/bitacora.model';
import { ETIQUETA_ESTADO } from '../../data/estado-incidencia';

/** Lógica pura de la página de incidencias de Bitácora (fechas, horas y exportación). */

export const CABECERAS_EXPORT = [
  'Ticket',
  'Descripción',
  'Centro',
  'Sistema',
  'Tipo',
  'Área',
  'Responsable',
  'Fecha inicio',
  'Hora inicio',
  'Fecha fin',
  'Hora fin',
  'Duración',
  'Estado',
  'Registrado por',
];

/** «08:30:00» → «08:30» (— si no hay). */
export function hhmm(valor: string | null): string {
  return valor ? valor.slice(0, 5) : '—';
}

/** «08:30» → «08:30:00», el formato que espera el backend. */
export function normalizarHora(valor: string): string {
  return valor.length === 5 ? `${valor}:00` : valor;
}

/** «2026-11-13» → «13/11/2026», sin pasar por Date (no corre el día por zona). */
export function fechaCorta(dia: string | null): string {
  if (!dia) {
    return '—';
  }
  const [a, m, d] = dia.split('-');
  return `${d}/${m}/${a}`;
}

/** Día del inicio (negocio) de una incidencia: creado_en = fecha + hora de inicio. */
export function fechaInicio(incidencia: Incidencia): string {
  return diaDeInstante(incidencia.creadoEn);
}

/** Día del fin (negocio); null si está abierta. */
export function fechaFin(incidencia: Incidencia): string | null {
  return incidencia.cerradoEn ? diaDeInstante(incidencia.cerradoEn) : null;
}

/**
 * Fecha de fin sugerida al escribir la hora de fin: la del inicio, o el día
 * siguiente si la hora de fin es menor (cruzó la medianoche).
 */
export function sugerirFechaFin(fecha: string, horaInicio: string, horaFin: string): string {
  if (!fecha || !horaInicio || !horaFin || horaFin.slice(0, 5) >= horaInicio.slice(0, 5)) {
    return fecha;
  }
  const [a, m, d] = fecha.split('-').map(Number);
  const siguiente = new Date(Date.UTC(a, m - 1, d + 1));
  return siguiente.toISOString().slice(0, 10);
}

/**
 * Segundos entre inicio y fin con fechas y horas de negocio (misma zona en
 * ambos extremos: basta compararlas como UTC). Null si falta algún dato.
 */
export function segundosEntre(
  fecha: string,
  horaInicio: string,
  fechaFin: string,
  horaFin: string,
): number | null {
  if (!fecha || !horaInicio || !fechaFin || !horaFin) {
    return null;
  }
  const inicio = Date.parse(`${fecha}T${horaInicio.slice(0, 5)}:00Z`);
  const fin = Date.parse(`${fechaFin}T${horaFin.slice(0, 5)}:00Z`);
  return Number.isFinite(inicio) && Number.isFinite(fin) ? Math.round((fin - inicio) / 1000) : null;
}

/**
 * Validador del grupo: fecha y hora de fin van juntas (ambas vacías = abierta)
 * y el fin no puede ser anterior al inicio.
 */
export const finCoherente: ValidatorFn = (grupo: AbstractControl): ValidationErrors | null => {
  const v = grupo.value as { fecha: string; horaInicio: string; fechaFin: string; horaFin: string };
  const conFecha = !!v.fechaFin;
  const conHora = !!v.horaFin;
  if (conFecha !== conHora) {
    return { finIncompleto: true };
  }
  const segundos = segundosEntre(v.fecha, v.horaInicio, v.fechaFin, v.horaFin);
  return segundos !== null && segundos < 0 ? { finAnterior: true } : null;
};

/** Fila del CSV de una incidencia; `actor` resuelve creado_por (quién la registró). */
export function filaExportacion(
  incidencia: Incidencia,
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
    fechaCorta(fechaInicio(incidencia)),
    hhmm(incidencia.horaInicio),
    fechaCorta(fechaFin(incidencia)),
    hhmm(incidencia.horaFin),
    duracionHhmm(incidencia.tiempoSolucionSeg),
    ETIQUETA_ESTADO[incidencia.estado],
    actor(incidencia.creadoPor),
  ];
}
