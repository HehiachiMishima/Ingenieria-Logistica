import { DestroyRef, inject, signal } from '@angular/core';
import { DefFiltro } from '../../../../shared/ui/panel-filtros/estado-filtros';
import { EstadoIncidencia } from './bitacora.model';

/**
 * Estado abierto/cerrado de una incidencia: etiquetas, antigüedad de las
 * abiertas y un reloj por minuto para que esa antigüedad avance sola en
 * pantalla sin pedir nada al servidor.
 */

export const ETIQUETA_ESTADO: Record<EstadoIncidencia, string> = {
  ABIERTA: 'Abierta',
  CERRADA: 'Cerrada',
};

/** Filtro «Estado» del panel lateral (chip «Estado: Abiertas»). */
export function filtroEstado(): DefFiltro<EstadoIncidencia | null> {
  return {
    vacio: null,
    etiqueta: 'Estado',
    icono: 'reloj',
    texto: (estado) => (estado === 'ABIERTA' ? 'Abiertas' : 'Cerradas'),
  };
}

/** Lee el estado del `<select>` del filtro («» = todas). */
export function estadoDeEvento(evento: Event): EstadoIncidencia | null {
  const valor = (evento.target as HTMLSelectElement).value;
  return valor === 'ABIERTA' || valor === 'CERRADA' ? valor : null;
}

export type Antiguedad = 'reciente' | 'media' | 'antigua';

const UNA_HORA = 3600;
const CUATRO_HORAS = 4 * UNA_HORA;

/** Verde menos de 1 h · ámbar de 1 a 4 h · rojo más de 4 h. */
export function antiguedad(segundos: number): Antiguedad {
  return segundos < UNA_HORA ? 'reciente' : segundos < CUATRO_HORAS ? 'media' : 'antigua';
}

/** Segundos que lleva abierta una incidencia que empezó en `desde` (instante ISO). */
export function segundosDesde(desde: string, ahoraMs: number): number {
  return Math.max(0, Math.floor((ahoraMs - Date.parse(desde)) / 1000));
}

/** Segundos → «5 h 12 m», «18 m» o «3 d 4 h» (antigüedad legible). */
export function edad(segundos: number): string {
  const minutos = Math.floor(segundos / 60);
  const horas = Math.floor(minutos / 60);
  const dias = Math.floor(horas / 24);
  if (dias > 0) {
    return `${dias} d ${horas % 24} h`;
  }
  return horas > 0 ? `${horas} h ${`${minutos % 60}`.padStart(2, '0')} m` : `${minutos} m`;
}

/**
 * Reloj que avanza cada minuto (ms desde epoch). Se crea en un contexto de
 * inyección y se detiene solo al destruirse el componente.
 */
export function relojPorMinuto() {
  const ahora = signal(Date.now());
  const id = setInterval(() => ahora.set(Date.now()), 60_000);
  inject(DestroyRef).onDestroy(() => clearInterval(id));
  return ahora.asReadonly();
}
