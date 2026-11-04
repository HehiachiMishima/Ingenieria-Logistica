import { diaDeInstante } from '../../../core/utils/fecha.util';
import { ChipFiltro, fechaChip } from './filtros-activos';

/**
 * Filtros comunes de los catálogos (usuarios, roles, permisos): estado
 * activo/inactivo y periodo de creación (día del negocio). La lógica vive
 * aquí para que las tres pantallas filtren y muestren sus chips igual.
 */

export type FiltroEstado = 'todos' | 'activos' | 'inactivos';

export const OPCIONES_ESTADO: ReadonlyArray<{ id: FiltroEstado; etiqueta: string }> = [
  { id: 'todos', etiqueta: 'Todos' },
  { id: 'activos', etiqueta: 'Activos' },
  { id: 'inactivos', etiqueta: 'Inactivos' },
];

export function cumpleEstado(estado: FiltroEstado, activo: boolean | null | undefined): boolean {
  return estado === 'todos' || (estado === 'activos') === !!activo;
}

/**
 * true si la fecha ISO cae en el rango [desde, hasta] (días YYYY-MM-DD,
 * inclusivo). Si el rango llega invertido se interpreta al derecho.
 */
export function enPeriodo(iso: string | null | undefined, desde: string, hasta: string): boolean {
  if (!desde && !hasta) {
    return true;
  }
  const [inicio, fin] = desde && hasta && desde > hasta ? [hasta, desde] : [desde, hasta];
  const dia = iso ? diaDeInstante(iso) : '';
  return (!inicio || dia >= inicio) && (!fin || dia <= fin);
}

export function chipEstado(estado: FiltroEstado): ChipFiltro | null {
  return estado === 'todos'
    ? null
    : {
        clave: 'estado',
        etiqueta: 'Estado',
        valor: estado === 'activos' ? 'Activos' : 'Inactivos',
        icono: 'power',
      };
}

export function chipPeriodo(desde: string, hasta: string, etiqueta = 'Creado'): ChipFiltro | null {
  if (!desde && !hasta) {
    return null;
  }
  const [inicio, fin] = desde && hasta && desde > hasta ? [hasta, desde] : [desde, hasta];
  return {
    clave: 'periodo',
    etiqueta,
    valor: `${inicio ? fechaChip(inicio) : 'inicio'} – ${fin ? fechaChip(fin) : 'hoy'}`,
    icono: 'calendario',
  };
}
