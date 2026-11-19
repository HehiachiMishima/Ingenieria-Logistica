import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { compacto, entero } from '../../../../../../../shared/ui/charts/formato';
import { CeldaMonitoreo } from '../../../../data/observabilidad.model';
import { DIAS_SEMANA, FRANJAS, HORAS_POR_FRANJA, META_P95_MS } from '../../../../ui/observabilidad.util';

interface Celda {
  clave: string;
  dia: string;
  franja: string;
  peticiones: number;
  p95: number;
  /** Intensidad del color (0–100) según la demanda. */
  pct: number;
  lento: boolean;
}

/**
 * ¿El sistema aguanta cuando más se usa? Un mapa día × franja de 4 h (hora de Lima): el color
 * es la demanda (peticiones a las APIs) y un punto rojo marca las franjas cuyo p95 pasó la meta.
 * Si los puntos caen en las celdas más oscuras, la plataforma se degrada con la carga.
 */
@Component({
  selector: 'app-demanda-rendimiento',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './demanda-rendimiento.html',
  styleUrl: './demanda-rendimiento.scss',
})
export class DemandaRendimiento {
  readonly celdas = input<CeldaMonitoreo[] | null>(null);

  /** Rótulo de cada franja: completo («12–16») y corto («12», cuando el mapa es angosto). */
  readonly franjas = FRANJAS.map((desde) => ({ largo: `${desde}–${desde + HORAS_POR_FRANJA}`, corto: `${desde}` }));
  readonly meta = META_P95_MS;
  readonly escala = [8, 30, 55, 80, 100];
  /** Celda bajo el puntero (su lectura reemplaza al resumen). */
  readonly activa = signal<Celda | null>(null);

  readonly filas = computed(() => {
    const porClave = new Map((this.celdas() ?? []).map((c) => [`${c.dia}|${c.desde}`, c]));
    const max = Math.max(1, ...(this.celdas() ?? []).map((c) => c.peticiones));
    return DIAS_SEMANA.map((nombre, i) => ({
      nombre,
      celdas: FRANJAS.map((desde, j): Celda => {
        const c = porClave.get(`${i + 1}|${desde}`);
        const peticiones = c?.peticiones ?? 0;
        return {
          clave: `${i}|${j}`,
          dia: nombre,
          franja: `${desde}–${desde + HORAS_POR_FRANJA} h`,
          peticiones,
          p95: c?.p95 ?? 0,
          pct: peticiones ? Math.round(8 + (92 * peticiones) / max) : 0,
          lento: peticiones > 0 && (c?.p95 ?? 0) > META_P95_MS,
        };
      }),
    }));
  });

  readonly hayDatos = computed(() => (this.celdas() ?? []).some((c) => c.peticiones > 0));

  /** Lectura del mapa: la franja de más tráfico y cuántas pasaron la meta. */
  readonly resumen = computed(() => {
    const todas = this.filas().flatMap((f) => f.celdas);
    const conTrafico = todas.filter((c) => c.peticiones > 0);
    if (!conTrafico.length) {
      return 'Sin peticiones en el período.';
    }
    const pico = conTrafico.reduce((a, b) => (b.peticiones > a.peticiones ? b : a));
    const lentas = conTrafico.filter((c) => c.lento);
    const base = `La franja de más tráfico es ${pico.dia} ${pico.franja} (${compacto(pico.peticiones)} peticiones).`;
    if (!lentas.length) {
      return `${base} Todas las franjas respondieron dentro de la meta de ${entero(META_P95_MS)} ms.`;
    }
    const peor = lentas.reduce((a, b) => (b.p95 > a.p95 ? b : a));
    return (
      `${base} ${lentas.length} ${lentas.length === 1 ? 'franja pasó' : 'franjas pasaron'} la meta de ` +
      `${entero(META_P95_MS)} ms; la más lenta: ${peor.dia} ${peor.franja} (${entero(peor.p95)} ms).`
    );
  });

  readonly lectura = computed(() => {
    const c = this.activa();
    if (!c) {
      return this.resumen();
    }
    if (!c.peticiones) {
      return `${c.dia} ${c.franja}: sin peticiones.`;
    }
    return (
      `${c.dia} ${c.franja}: ${entero(c.peticiones)} peticiones · p95 ${entero(c.p95)} ms` +
      (c.lento ? ' ⚠ sobre la meta' : ' · dentro de la meta')
    );
  });

  readonly entero = entero;
}
