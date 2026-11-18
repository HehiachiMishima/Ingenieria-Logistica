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
import { DistribucionSimulacion, FrecuenciaRuta, RutaDistribucion } from '../../data/simulacion.model';
import { formatearEntero } from '../../simulacion-formato';

/** Una ficha lista para dibujar: grupo con su color, código, bultos y pestaña de frecuencia. */
interface FichaVista {
  codigo: string;
  grupo: string | null;
  color: string;
  bultos: string;
  letra: string;
  clase: string;
  titulo: string;
}

/** Una celda ya lista para dibujar: sus fichas y los bultos de cada día. */
interface CeldaVista {
  operariosLxv: number | null;
  operariosMjs: number | null;
  bloqueado: boolean;
  buff: boolean;
  rutas: FichaVista[];
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

/** Letra de la pestaña de frecuencia (como la planilla) y su nombre para el title. */
const FRECUENCIA: Record<FrecuenciaRuta, { letra: string; nombre: string }> = {
  DIARIA: { letra: 'D', nombre: 'Diaria' },
  LXV: { letra: 'L', nombre: 'L-X-V' },
  MJS: { letra: 'M', nombre: 'M-J-S' },
};

/** Pestaña de una ruta sin grupo: gris neutro. */
const SIN_GRUPO = '#94a3b8';

// Anchos de la hoja (px): la columna de rótulos, un chute bloqueado y un chute con fichas (una
// ficha en una línea necesita ~112 px). Al imprimir, la hoja se ajusta al ancho de la página A3.
const ANCHO_ROTULO = 120;
const ANCHO_BLOQUEADO = 26;
const ANCHO_CHUTE = 116;
const ANCHO_MINIMO = 1200;
/** Ancho útil de A3 horizontal con márgenes de 8 mm (404 mm a 96 ppp), con holgura. */
const ANCHO_IMPRIMIBLE = 1520;

function ficha(r: RutaDistribucion): FichaVista {
  const f = FRECUENCIA[r.frecuencia];
  const bultos = formatearEntero(r.bultos);
  return {
    codigo: r.codigo,
    grupo: r.grupo,
    color: r.color ?? SIN_GRUPO,
    bultos,
    letra: f.letra,
    clase: CLASE[r.frecuencia],
    titulo: `Ruta ${r.codigo} · ${r.grupo !== null ? `grupo ${r.grupo} · ` : ''}${bultos} bultos · ${f.nombre}`,
  };
}

/** Escala de color de la fila de bultos (como el Excel): blanco → el tono del día. */
function fondo(valor: number | null, maximo: number, tono: string): string | null {
  if (valor === null || maximo <= 0) {
    return null;
  }
  return `color-mix(in srgb, ${tono} ${Math.round((valor / maximo) * 100)}%, #ffffff)`;
}

/**
 * Distribución de rutas por cluster y chute para imprimir o guardar como PDF (A3 horizontal): el
 * mismo diseño que el Excel, con la banda ATLAS. Un bloque por cluster con todos los chutes; cada
 * ruta es una ficha como la de la planilla (grupo, código, bultos y frecuencia) y, abajo, los
 * bultos de cada día. Al cargar abre
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
    // Logo de Farmacias Peruanas y marca ATLAS: que salgan en la primera impresión.
    const imagenes = [...document.querySelectorAll<HTMLImageElement>('.dist__hoja img')];
    await Promise.all(imagenes.map((img) => img.decode().catch(() => undefined)));
    this.imprimir();
  }

  /**
   * Ancho de la hoja en pantalla (desplaza el lienzo si no cabe) y, al imprimir, el ancho y la
   * escala para que todos los chutes entren en el ancho de la página, como el Excel.
   */
  readonly hoja = computed(() => {
    const d = this.datos();
    const bloqueados = d?.chutes.filter((c) => c.bloqueado).length ?? 0;
    const libres = (d?.chutes.length ?? 0) - bloqueados;
    const ancho = Math.max(
      ANCHO_MINIMO,
      ANCHO_ROTULO + bloqueados * ANCHO_BLOQUEADO + libres * ANCHO_CHUTE + 48,
    );
    const impresion = Math.max(ancho, ANCHO_IMPRIMIBLE);
    return {
      ancho: `${ancho}px`,
      impresion: `${impresion}px`,
      escala: String(Math.round((ANCHO_IMPRIMIBLE / impresion) * 1000) / 1000),
    };
  });

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
          rutas: rutas.map(ficha),
          lxv: x && trabajaLxv ? x.bultosLxv : null,
          mjs: x && trabajaMjs ? x.bultosMjs : null,
          operariosLxv: x?.operariosLxv ?? null,
          operariosMjs: x?.operariosMjs ?? null,
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
