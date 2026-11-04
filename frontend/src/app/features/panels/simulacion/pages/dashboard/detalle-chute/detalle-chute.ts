import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, debounceTime, finalize } from 'rxjs';
import { mensajeDeError } from '../../../../../../core/errors/error.util';
import { Pagina } from '../../../../../../core/models';
import { EmptyState } from '../../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { Paginador } from '../../../../../../shared/ui/paginador/paginador';
import { SelectBuscable } from '../../../../../../shared/ui/select-buscable/select-buscable';
import {
  ChuteComparativoSimulacion,
  ClusterComparativoSimulacion,
  LadoChute,
  MovimientoChuteSimulacion,
  ResumenRutasSimulacion,
  RutaSimulacion,
} from '../../../data/simulacion.model';
import { SimulacionApi } from '../../../data/simulacion.api';
import { compararNombre, formatearNumero } from '../../../simulacion-formato';

type TipoFiltro = '' | 'B2C' | 'B2B';

interface Movimiento {
  texto: string;
  tipo: 'quedo' | 'interna' | 'sale' | 'llega';
  /** Salió para separar CL1 de CL2. */
  separa?: boolean;
}

/** Estado de un lado del chute (antes o después): su página, totales y carga. */
class LadoRutas {
  readonly resumen = signal<ResumenRutasSimulacion | null>(null);
  readonly datos = signal<Pagina<RutaSimulacion> | null>(null);
  readonly pagina = signal(1);
  readonly porPagina = signal(10);
  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);
  /** Descarta respuestas viejas cuando se filtra o pagina rápido. */
  solicitud = 0;

  constructor(readonly lado: LadoChute) {}

  reiniciar(): void {
    this.resumen.set(null);
    this.datos.set(null);
    this.error.set(null);
    this.pagina.set(1);
  }
}

/**
 * Detalle de los chutes elegidos en el filtro del dashboard: sus rutas al
 * cargar el Excel frente a las que tienen tras el balanceo. Vive dentro de la
 * página (sin modal). Las rutas se listan agrupadas por cluster: CL1 y CL2 no
 * pueden compartir chute, así que cada cluster se lee por separado.
 */
@Component({
  selector: 'app-simulacion-detalle-chute',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, EmptyState, Paginador, SelectBuscable],
  templateUrl: './detalle-chute.html',
  styleUrl: './detalle-chute.scss',
})
export class SimulacionDetalleChute {
  private readonly api = inject(SimulacionApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly registroId = input<number | null>(null);
  /** Chutes elegidos en el filtro del dashboard (y visibles con sus filtros). */
  readonly chutes = input<ChuteComparativoSimulacion[]>([]);
  readonly clusters = input<ClusterComparativoSimulacion[]>([]);
  /** Movimientos de rutas entre chutes del registro (para el flujo del conjunto). */
  readonly movimientos = input<MovimientoChuteSimulacion[]>([]);
  /** Vista del lanzamiento de un cluster: solo sus rutas, sin elegir otro cluster. */
  readonly clusterFijo = input<number | null>(null);

  readonly formatearNumero = formatearNumero;

  readonly texto = signal('');
  /** Lo que muestra el buscador; el filtro se aplica tras la pausa de escritura. */
  readonly textoBusqueda = signal('');
  readonly tipo = signal<TipoFiltro>('');
  readonly clusterId = signal<number | null>(null);
  readonly soloCambios = signal(false);
  private readonly busqueda$ = new Subject<string>();
  /** Últimos chutes cargados: evita repetir la consulta si la selección no cambió. */
  private cargado = '';

  readonly antes = new LadoRutas('ANTES');
  readonly despues = new LadoRutas('DESPUES');
  readonly lados = [this.antes, this.despues];

  readonly ids = computed(() => this.chutes().map((chute) => chute.id));
  private readonly elegidos = computed(() => new Set(this.ids()));
  private readonly porId = computed(
    () => new Map(this.chutes().map((chute) => [chute.id, chute] as const)),
  );
  readonly varios = computed(() => this.chutes().length > 1);

  /** Solo los clusters que los chutes elegidos atienden antes o después. */
  readonly opcionesCluster = computed(() => {
    const nombres = new Set(this.chutes().flatMap((chute) => chute.clusters));
    return this.clusters()
      .filter((cluster) => nombres.has(cluster.cluster))
      .sort((a, b) => compararNombre(a.cluster, b.cluster))
      .map((cluster) => ({ id: cluster.id, etiqueta: cluster.cluster }));
  });

  readonly tieneFiltros = computed(
    () =>
      this.texto().trim().length > 0 ||
      this.tipo() !== '' ||
      this.clusterId() !== this.clusterFijo() ||
      this.soloCambios(),
  );

  /**
   * Flujo de rutas del conjunto elegido: las que se quedaron en su chute, las
   * que cambiaron entre chutes elegidos, las que salieron del conjunto y las que
   * llegaron de fuera. Antes − salieron + llegaron = después.
   */
  readonly flujo = computed(() => {
    const chutes = this.chutes();
    if (chutes.length === 0) {
      return null;
    }
    const elegidos = this.elegidos();
    const antes = chutes.reduce((total, chute) => total + Number(chute.rutasOriginal), 0);
    const despues = chutes.reduce((total, chute) => total + Number(chute.rutasBalanceado), 0);
    let salieron = 0;
    let internas = 0;
    let llegaron = 0;
    for (const movimiento of this.movimientos()) {
      const desde = elegidos.has(movimiento.origenId);
      const hacia = elegidos.has(movimiento.destinoId);
      const rutas = Number(movimiento.rutas);
      if (desde && hacia) {
        internas += rutas;
      } else if (desde) {
        salieron += rutas;
      } else if (hacia) {
        llegaron += rutas;
      }
    }
    const quedaron = Math.max(0, antes - salieron - internas);
    const escala = Math.max(1, antes, despues);
    return {
      antes,
      despues,
      salieron,
      internas,
      llegaron,
      quedaron,
      pctQuedaron: (quedaron / escala) * 100,
      pctInternas: (internas / escala) * 100,
      pctSalieron: (salieron / escala) * 100,
      pctLlegaron: (llegaron / escala) * 100,
    };
  });

  /** Chutes que hoy juntan CL1 y CL2: sin balancear o sin otro chute libre para separarlos. */
  readonly mezclados = computed(() => this.chutes().filter((chute) => chute.mezclaBalanceada));

  constructor() {
    // Cada selección de chutes (o lado) limpia los filtros y recarga lo visible.
    effect(() => {
      const clave = this.ids().join(',');
      untracked(() => this.activar(clave));
    });
    this.busqueda$.pipe(debounceTime(300), takeUntilDestroyed()).subscribe((texto) => {
      if (texto.trim() === this.texto().trim()) {
        return;
      }
      this.texto.set(texto);
      this.recargar();
    });
  }

  private activar(clave: string): void {
    if (clave === this.cargado) {
      return;
    }
    this.cargado = clave;
    // Los clusters dependen de los chutes: un filtro de cluster ajeno vaciaría la vista.
    this.reiniciarFiltros();
    if (clave === '') {
      for (const lado of this.lados) {
        lado.reiniciar();
      }
      return;
    }
    this.recargar();
  }

  buscar(texto: string): void {
    this.textoBusqueda.set(texto);
    this.busqueda$.next(texto);
  }

  setTipo(valor: string): void {
    this.tipo.set(valor === 'B2C' || valor === 'B2B' ? valor : '');
    this.recargar();
  }

  setCluster(valor: number | null): void {
    this.clusterId.set(valor);
    this.recargar();
  }

  setSoloCambios(valor: boolean): void {
    this.soloCambios.set(valor);
    this.recargar();
  }

  limpiarFiltros(): void {
    this.reiniciarFiltros();
    this.recargar();
  }

  private reiniciarFiltros(): void {
    this.busqueda$.next('');
    this.textoBusqueda.set('');
    this.texto.set('');
    this.tipo.set('');
    this.clusterId.set(this.clusterFijo());
    this.soloCambios.set(false);
  }

  /** Cualquier cambio de chute o filtro vuelve ambos lados a la primera página. */
  private recargar(): void {
    for (const lado of this.lados) {
      lado.reiniciar();
      this.cargar(lado, 1);
    }
  }

  cambiarPagina(lado: LadoRutas, pagina: number): void {
    this.cargar(lado, pagina);
  }

  cambiarPorPagina(lado: LadoRutas, valor: number): void {
    lado.porPagina.set(valor);
    this.cargar(lado, 1);
  }

  cargar(lado: LadoRutas, pagina: number): void {
    const id = this.registroId();
    const chuteIds = this.ids();
    if (!id || chuteIds.length === 0) {
      return;
    }
    lado.pagina.set(pagina);
    const solicitud = ++lado.solicitud;
    lado.cargando.set(true);
    lado.error.set(null);
    this.api
      .rutasDashboard(id, {
        pagina,
        tamano: lado.porPagina(),
        texto: this.texto().trim(),
        tipo: this.tipo(),
        chuteIds,
        lado: lado.lado,
        clusterId: this.clusterId(),
        soloCambios: this.soloCambios(),
      })
      .pipe(
        finalize(() => {
          if (solicitud === lado.solicitud) {
            lado.cargando.set(false);
          }
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (respuesta) => {
          if (solicitud === lado.solicitud) {
            lado.resumen.set(respuesta.resumen);
            lado.datos.set(respuesta.rutas);
          }
        },
        error: (err: unknown) => {
          if (solicitud === lado.solicitud) {
            lado.error.set(mensajeDeError(err));
          }
        },
      });
  }

  /** La fila abre un grupo nuevo de cluster (las rutas llegan ordenadas por cluster). */
  /** Cada lado agrupa por el cluster que la ruta tenía entonces: antes, el original. */
  iniciaGrupo(lado: LadoChute, rutas: RutaSimulacion[], indice: number): boolean {
    return (
      indice === 0 ||
      this.clusterIdDe(lado, rutas[indice - 1]) !== this.clusterIdDe(lado, rutas[indice])
    );
  }

  clusterDe(lado: LadoChute, ruta: RutaSimulacion): string {
    return lado === 'ANTES' ? ruta.clusterOriginal : ruta.cluster;
  }

  private clusterIdDe(lado: LadoChute, ruta: RutaSimulacion): number {
    return lado === 'ANTES' ? ruta.clusterOriginalId : ruta.clusterId;
  }

  /** La ruta se pasó a mano a otro cluster: texto del cambio visto desde cada lado. */
  cambioDeCluster(lado: LadoChute, ruta: RutaSimulacion): string | null {
    if (ruta.clusterId === ruta.clusterOriginalId) {
      return null;
    }
    return lado === 'ANTES' ? `Pasó a ${ruta.cluster}` : `Era de ${ruta.clusterOriginal}`;
  }

  /** Movimiento de la ruta visto desde un lado del conjunto elegido. */
  movimiento(lado: LadoChute, ruta: RutaSimulacion): Movimiento {
    if (ruta.chuteId === ruta.chuteOriginalId) {
      return { texto: lado === 'ANTES' ? 'Se quedó' : 'Ya estaba', tipo: 'quedo' };
    }
    const elegidos = this.elegidos();
    if (lado === 'ANTES') {
      return elegidos.has(ruta.chuteId)
        ? { texto: `Pasó a ${ruta.chute}`, tipo: 'interna' }
        : { texto: `Salió a ${ruta.chute}`, tipo: 'sale', separa: this.separaMezcla(ruta) };
    }
    return elegidos.has(ruta.chuteOriginalId)
      ? { texto: `Vino de ${ruta.chuteOriginal}`, tipo: 'interna' }
      : { texto: `Llegó de ${ruta.chuteOriginal}`, tipo: 'llega' };
  }

  /** La ruta salió de su chute para separar CL1 y CL2 (no por un ajuste de tiempos). */
  private separaMezcla(ruta: RutaSimulacion): boolean {
    return (
      this.porId().get(ruta.chuteOriginalId)?.clustersSeparados.includes(ruta.cluster) ?? false
    );
  }
}
