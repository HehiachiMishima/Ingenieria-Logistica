import { duracion, entero, variacion } from '../../../../../shared/ui/charts/formato';
import { ItemBullet } from '../../../../../shared/ui/charts/bullet-chart';
import { TarjetaKpi } from '../../../../../shared/ui/charts/kpi';
import { ItemRanking } from '../../../../../shared/ui/charts/ranking-tiles';
import { GrupoBitacora, AnaliticaBitacora } from '../../data/analitica.model';

/**
 * Lógica pura del dashboard de Bitácora: convierte la respuesta de analítica
 * en los datos que pintan tarjetas y gráficos. Sin estado ni inyección: el
 * componente solo la envuelve en `computed`.
 */

export function tarjetasBitacora(k: AnaliticaBitacora['kpis'] | null): TarjetaKpi[] {
  return [
    {
      etiqueta: 'Incidencias',
      valor: k ? entero(k.total) : '—',
      icono: 'incidencias',
      color: 'var(--brand)',
      detalle: 'Registradas en el periodo',
      variacion: k ? variacion(k.total, k.totalPeriodoAnterior) : null,
    },
    {
      etiqueta: 'Tiempo medio de solución',
      valor: k ? duracion(k.mttrSegundos) : '—',
      icono: 'reloj',
      color: 'var(--accent)',
      detalle: k ? `MTTR de ${entero(k.resueltas)} resueltas` : '',
      variacion: null,
    },
    {
      etiqueta: 'Horas invertidas',
      valor: k ? entero(Math.round(k.segundosSolucion / 3600)) + ' h' : '—',
      icono: 'activity',
      color: 'var(--violet)',
      detalle: 'Suma de tiempos de solución',
      variacion: null,
    },
    {
      etiqueta: 'Sistemas afectados',
      valor: k ? entero(k.sistemasAfectados) : '—',
      icono: 'sistema',
      color: 'var(--cyan)',
      detalle: 'Con al menos una incidencia',
      variacion: null,
    },
    {
      etiqueta: 'Centros afectados',
      valor: k ? entero(k.centrosAfectados) : '—',
      icono: 'centro',
      color: 'var(--success)',
      detalle: 'Con al menos una incidencia',
      variacion: null,
    },
  ];
}

/** Tipos más repetidos, con el sistema al que pertenecen. */
export function rankingTipos(tipos: AnaliticaBitacora['tipos']): ItemRanking[] {
  return tipos.map((t) => ({
    etiqueta: t.tipo,
    valor: t.total,
    chip: t.sistema,
    detalle: `MTTR ${duracion(t.mttrSegundos)}`,
  }));
}

/** Grupo (centro, área…) con su total y su MTTR como detalle: anillo y waffle. */
export function gruposConMttr(
  grupos: readonly GrupoBitacora[],
): { etiqueta: string; valor: number; detalle: string }[] {
  return grupos.map((g) => ({
    etiqueta: g.etiqueta,
    valor: g.total,
    detalle: `MTTR ${duracion(g.mttrSegundos)}`,
  }));
}

/** ¿Dónde tardamos más? MTTR por sistema (solo los que tienen casos resueltos). */
export function bulletMttr(sistemas: readonly GrupoBitacora[]): ItemBullet[] {
  return sistemas
    .filter((g) => g.mttrSegundos > 0)
    .map((g) => ({
      etiqueta: g.etiqueta,
      valor: g.mttrSegundos,
      texto: duracion(g.mttrSegundos),
      detalle: `${entero(g.total)} casos`,
    }));
}
