import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Injector,
  ViewEncapsulation,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { mensajeDeError } from '../../../../../core/errors/error.util';
import { SimulacionApi } from '../../data/simulacion.api';
import { DistribucionSimulacion, FrecuenciaRuta } from '../../data/simulacion.model';
import { formatearEntero } from '../../simulacion-formato';

/** Una celda ya lista para dibujar: rutas con su clase de color y los bultos de cada día. */
interface CeldaVista {
  bloqueado: boolean;
  buff: boolean;
  rutas: { codigo: string; clase: string }[];
  lxv: number | null;
  mjs: number | null;
  fondoLxv: string | null;
  fondoMjs: string | null;
}

interface ClusterVista {
  id: number;
  nombre: string;
  rutas: number;
  /** Dos líneas: las diarias y luego L-X-V · M-J-S (sin cortar «M-J-S»). */
  detalle: [string, string];
  bultosLxv: number;
  bultosMjs: number;
  celdas: CeldaVista[];
}

const CLASE: Record<FrecuenciaRuta, string> = {
  DIARIA: 'dist__ruta--diaria',
  LXV: 'dist__ruta--lxv',
  MJS: 'dist__ruta--mjs',
};

/** Escala de color de la fila de bultos (como el Excel): blanco → el tono del día. */
function fondo(valor: number | null, maximo: number, tono: string): string | null {
  if (valor === null || maximo <= 0) {
    return null;
  }
  return `color-mix(in srgb, ${tono} ${Math.round((valor / maximo) * 100)}%, #ffffff)`;
}

/**
 * Distribución de rutas por cluster y chute para imprimir o guardar como PDF (A3 horizontal): el
 * mismo diseño que el Excel. Un bloque por cluster con todos los chutes; cada celda lleva los
 * números de ruta con el color de su frecuencia y, abajo, los bultos de cada día. Al cargar abre
 * el diálogo de impresión (salvo con ?imprimir=no).
 */
@Component({
  selector: 'app-simulacion-reporte',
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  templateUrl: './reporte.html',
  styleUrl: './reporte.scss',
})
export class SimulacionReporteEjecutivo {
  private readonly api = inject(SimulacionApi);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  /** Para programar la impresión cuando llegan los datos (fuera del constructor). */
  private readonly injector = inject(Injector);

  readonly datos = signal<DistribucionSimulacion | null>(null);
  readonly error = signal<string | null>(null);
  readonly formatearEntero = formatearEntero;

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    const id = Number(params.get('registro'));
    const imprimir = params.get('imprimir') !== 'no';
    if (!Number.isInteger(id) || id <= 0) {
      this.error.set('Falta el registro a reportar.');
      return;
    }
    this.api
      .distribucion(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (datos) => {
          this.datos.set(datos);
          document.title = `Distribución · ${datos.registro}`;
          if (imprimir) {
            // Tras dibujarse y cargar el logo, abre el diálogo de impresión.
            afterNextRender(() => void this.imprimirAlCargar(), { injector: this.injector });
          }
        },
        error: (err) => this.error.set(mensajeDeError(err)),
      });
  }

  imprimir(): void {
    window.print();
  }

  cerrar(): void {
    window.close();
  }

  private async imprimirAlCargar(): Promise<void> {
    const logo = document.querySelector<HTMLImageElement>('.dist__logo');
    await logo?.decode().catch(() => undefined);
    this.imprimir();
  }

  /** «CHUTE 15» aunque el catálogo lo guarde como «15». */
  etiquetaChute(nombre: string): string {
    const limpio = nombre.trim().toUpperCase();
    return limpio.startsWith('CHUTE') ? limpio : `CHUTE ${limpio}`;
  }

  readonly generado = computed(() => {
    const d = this.datos();
    return d
      ? new Intl.DateTimeFormat('es-PE', {
          timeZone: 'America/Lima',
          day: '2-digit',
          month: '2-digit',
          year: 'numeric',
          hour: '2-digit',
          minute: '2-digit',
        }).format(new Date(d.generado))
      : '';
  });

  readonly indicadores = computed(() => {
    const t = this.datos()?.totales;
    return t
      ? [
          { etiqueta: 'Clusters', valor: t.clusters, clase: '' },
          { etiqueta: 'Rutas', valor: t.rutas, clase: '' },
          { etiqueta: 'Chutes en uso', valor: t.chutesEnUso, clase: '' },
          { etiqueta: 'Bultos L-X-V', valor: t.bultosLxv, clase: 'dist__kpi--lxv' },
          { etiqueta: 'Bultos M-J-S', valor: t.bultosMjs, clase: 'dist__kpi--mjs' },
        ]
      : [];
  });

  /** Cada cluster con sus celdas alineadas a los chutes del plano. */
  readonly clusters = computed<ClusterVista[]>(() => {
    const d = this.datos();
    if (!d) {
      return [];
    }
    return d.clusters.map((c) => {
      const porChute = new Map(c.celdas.map((x) => [x.chuteId, x]));
      const celdas: CeldaVista[] = d.chutes.map((ch) => {
        const x = ch.id === null ? undefined : porChute.get(ch.id);
        const rutas = ch.bloqueado || !x ? [] : x.rutas;
        // Un día cuenta si el chute tiene alguna diaria o ruta de ese día.
        const trabajaLxv = rutas.some((r) => r.frecuencia !== 'MJS');
        const trabajaMjs = rutas.some((r) => r.frecuencia !== 'LXV');
        return {
          bloqueado: ch.bloqueado,
          buff: ch.buff,
          rutas: rutas.map((r) => ({ codigo: r.codigo, clase: CLASE[r.frecuencia] })),
          lxv: x && trabajaLxv ? x.bultosLxv : null,
          mjs: x && trabajaMjs ? x.bultosMjs : null,
          fondoLxv: null,
          fondoMjs: null,
        };
      });
      const maxLxv = Math.max(0, ...celdas.map((x) => x.lxv ?? 0));
      const maxMjs = Math.max(0, ...celdas.map((x) => x.mjs ?? 0));
      for (const x of celdas) {
        x.fondoLxv = fondo(x.lxv, maxLxv, '#dce7fd');
        x.fondoMjs = fondo(x.mjs, maxMjs, '#fce3bf');
      }
      return {
        id: c.id,
        nombre: c.nombre,
        rutas: c.rutas,
        detalle: [`${c.diarias} ${c.diarias === 1 ? 'diaria' : 'diarias'}`, `${c.lxv} L-X-V · ${c.mjs} M-J-S`],
        bultosLxv: c.bultosLxv,
        bultosMjs: c.bultosMjs,
        celdas,
      };
    });
  });
}
