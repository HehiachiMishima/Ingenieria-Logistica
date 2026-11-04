import { formatDate } from '@angular/common';
import { CONFIG } from '../config/configuracion';

/** Fecha local en formato ISO corto («2026-10-07»), el que usan los `<input type="date">`. */
export function fechaIso(fecha: Date): string {
  const mes = `${fecha.getMonth() + 1}`.padStart(2, '0');
  const dia = `${fecha.getDate()}`.padStart(2, '0');
  return `${fecha.getFullYear()}-${mes}-${dia}`;
}

/** Día del negocio (zona de CONFIG) en que cae un instante, en formato ISO corto. */
export function diaDeInstante(instante: string | number): string {
  return formatDate(instante, 'yyyy-MM-dd', 'en-US', CONFIG.zonaHoraria);
}

/** Día actual del negocio, sin importar la zona del navegador. */
export function diaNegocio(): Date {
  const [anio, mes, dia] = diaDeInstante(Date.now()).split('-').map(Number);
  return new Date(anio, mes - 1, dia);
}

/** Día actual del negocio en formato ISO corto. */
export function hoy(): string {
  return fechaIso(diaNegocio());
}

/** Instante de auditoría (UTC) → «dd/MM/yyyy HH:mm» en la zona del negocio, como lo muestra la tabla. */
export function fechaHoraNegocio(instante: string, locale: string): string {
  return formatDate(instante, 'dd/MM/yyyy HH:mm', locale, CONFIG.zonaHoraria);
}

/** Saludo según la hora local: «Buenos días», «Buenas tardes» o «Buenas noches». */
export function saludoSegunHora(fecha = new Date()): string {
  const hora = fecha.getHours();
  return hora < 12 ? 'Buenos días' : hora < 19 ? 'Buenas tardes' : 'Buenas noches';
}

/** Duración en segundos → «HH:MM» (— si no hay). */
export function duracionHhmm(segundos: number | null): string {
  if (segundos === null) {
    return '—';
  }
  const horas = `${Math.floor(segundos / 3600)}`.padStart(2, '0');
  const minutos = `${Math.floor((segundos % 3600) / 60)}`.padStart(2, '0');
  return `${horas}:${minutos}`;
}
