import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { entero } from '../../../../../../../shared/ui/charts/formato';
import { Icon } from '../../../../../../../shared/ui/icon/icon';
import { EndpointMonitoreo } from '../../../../data/observabilidad.model';
import {
  META_P95_MS,
  MIN_PETICIONES_RANKING,
  TEXTO_NIVEL_API,
  badgeMetodo,
  nivelApi,
} from '../../../../ui/observabilidad.util';
import { ICONO_NIVEL, maximoRedondo } from './grafico.util';

/** APIs que se muestran en el ranking. */
const TOPE = 6;
const ERROR = new Intl.NumberFormat('es-PE', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/**
 * APIs a vigilar: las más lentas por p95 (con tráfico suficiente para que el p95 no sea ruido),
 * cada una contra la meta y con el semáforo de Monitoreo (en meta, atención, crítico) en
 * palabras. Barras en el color de Monitoreo; las que pasan la meta, en el color de su estado.
 */
@Component({
  selector: 'app-apis-vigilar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  templateUrl: './apis-vigilar.html',
  styleUrl: './apis-vigilar.scss',
})
export class ApisVigilar {
  readonly endpoints = input<EndpointMonitoreo[] | null>(null);

  readonly textoNivel = TEXTO_NIVEL_API;
  readonly iconoNivel = ICONO_NIVEL;
  readonly minimo = MIN_PETICIONES_RANKING;

  readonly ranking = computed(() => {
    const lista = (this.endpoints() ?? [])
      .filter((e) => e.peticiones >= MIN_PETICIONES_RANKING)
      .sort((a, b) => b.p95 - a.p95)
      .slice(0, TOPE);
    const tope = Math.max(1000, maximoRedondo(Math.max(0, ...lista.map((e) => e.p95))));
    const pct = (v: number) => Math.min(100, (v / tope) * 100);
    return {
      metaPct: pct(META_P95_MS),
      tope: `${entero(tope)} ms`,
      filas: lista.map((e) => {
        const nivel = nivelApi(e);
        return {
          clave: `${e.metodo} ${e.ruta}`,
          metodo: e.metodo,
          badge: badgeMetodo(e.metodo),
          ruta: e.ruta,
          // Sin el prefijo común «/api/v1» la ruta se lee entera más veces (completa en el title).
          rutaCorta: e.ruta.replace(/^\/api(\/v\d+)?(?=\/)/, ''),
          p95: `${entero(e.p95)} ms`,
          error: `${ERROR.format(e.porcentaje)} %`,
          /** Más del 2 % de error: es lo que pone a la API en atención o crítica aunque responda rápido. */
          errorAlto: e.porcentaje > 2,
          peticiones: entero(e.peticiones),
          pct: pct(e.p95),
          nivel,
        };
      }),
    };
  });

  readonly metaMs = `${entero(META_P95_MS)} ms`;
  readonly meta = `Meta ${this.metaMs}`;
}
