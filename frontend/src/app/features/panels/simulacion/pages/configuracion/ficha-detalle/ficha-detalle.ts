import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterRenderEffect,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  viewChild,
} from '@angular/core';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { IconoSim } from '../../../icono-sim/icono-sim';
import { LocalRuta, LocalesRuta } from '../../../data/simulacion.model';
import { EnteroPipe, HorasPipe, NumeroPipe } from '../../../numero.pipe';
import { formatearEntero } from '../../../simulacion-formato';
import type { PosicionDetalle, SimulacionConfiguracion } from '../configuracion';

/** Mini fichas que se dibujan de una vez (y cuántas más con cada «Mostrar más»). */
const PASO = 200;
/** Espera tras la última tecla antes de filtrar los locales. */
const ESPERA_BUSQUEDA_MS = 150;
/** Bajo este alto (en rem: 560 px con el texto normal) la ficha desplaza entera en lugar de darle scroll propio a la cuadrícula. */
const ALTO_MINIMO_CUADRICULA_REM = 35;

/** Lo que muestra la ficha de una ruta (lo arma Configuración). */
export type RutaDetalle = NonNullable<ReturnType<SimulacionConfiguracion['rutaDetalle']>>;
/** Lo que muestra la ficha de un cluster (lo arma Configuración). */
export type ClusterDetalle = NonNullable<ReturnType<SimulacionConfiguracion['clusterDetalle']>>;

/** Los locales de la ruta abierta, tal como llegan del servidor. */
export type EstadoLocales =
  | { estado: 'cargando' }
  | { estado: 'error'; mensaje: string }
  | { estado: 'listo'; datos: LocalesRuta };

/**
 * Ficha flotante de la matriz: la de una ruta (con la cuadrícula de sus locales del Excel) o la de
 * un cluster, junto a lo que se tocó (hoja inferior en celular). Solo presenta: los datos, los
 * locales y la posición los calcula Configuración; aquí viven la búsqueda y el filtro de bultos.
 */
@Component({
  selector: 'app-ficha-detalle',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, IconoSim, EnteroPipe, HorasPipe, NgTemplateOutlet, NumeroPipe],
  templateUrl: './ficha-detalle.html',
  styleUrls: ['./ficha-detalle.scss', '../configuracion-ficha.scss', './ficha-locales.scss'],
})
export class FichaDetalle {
  readonly posicion = input.required<PosicionDetalle>();
  readonly ruta = input<RutaDetalle | null>(null);
  readonly cluster = input<ClusterDetalle | null>(null);
  readonly locales = input<EstadoLocales | null>(null);
  /** Puede completar los locales del registro con su Excel (permiso de modificar registros). */
  readonly puedeCompletar = input(false);
  /** Hay un Excel subiéndose para completar los locales. */
  readonly completando = input(false);
  readonly cerrar = output<void>();
  readonly reintentar = output<void>();
  readonly completar = output<File>();

  /** Búsqueda por COD (ya aplicada) y bultos mínimos: vuelven a vacío al abrir otra ruta. */
  readonly busqueda = linkedSignal({ source: () => this.ruta()?.ruta.id, computation: () => '' });
  readonly minimo = linkedSignal<number | undefined, number | null>({
    source: () => this.ruta()?.ruta.id,
    computation: () => null,
  });

  /**
   * Cuántas mini fichas se dibujan: de {@link PASO} en {@link PASO} («Mostrar más»). Con miles
   * de locales dibujarlos todos bloqueaba el celular ~2,5 s (70 mil nodos); vuelve al primer paso
   * al cambiar de ruta o de filtro.
   */
  readonly limite = linkedSignal({
    source: () => ({ ruta: this.ruta()?.ruta.id, texto: this.busqueda(), minimo: this.minimo() }),
    computation: () => PASO,
  });

  /**
   * Ficha baja (laptop con zoom, celular horizontal o pequeño): la cuadrícula no lleva scroll
   * propio y la ficha desplaza entera, así no hay dos barras de scroll una dentro de otra.
   */
  readonly baja = computed(() => {
    if (this.desborda()) return true;
    const pos = this.posicion();
    const alto = pos.hoja ? window.innerHeight * 0.85 : (pos.maxAlto ?? window.innerHeight);
    // En rem: con el texto agrandado (accesibilidad del celular) lo demás de la ficha crece y la
    // cuadrícula se quedaría sin alto, así que se pasa antes al modo de un solo scroll.
    const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
    return alto < ALTO_MINIMO_CUADRICULA_REM * rem;
  });

  private readonly pop = viewChild.required<ElementRef<HTMLElement>>('pop');

  /**
   * La ficha desbordó con la cuadrícula ya en su alto mínimo (cabecera y filtros en dos líneas en
   * celulares angostos, avisos, texto agrandado): el umbral de {@link baja} no lo previó y había
   * dos scrolls, uno dentro de otro. Solo pasa de no a sí, así no oscila; se vuelve a medir al
   * abrir otra ruta o cambiar el alto disponible.
   */
  private readonly desborda = linkedSignal({
    source: () => {
      const pos = this.posicion();
      return `${this.ruta()?.ruta.id}|${pos.hoja}|${pos.maxAlto}`;
    },
    computation: () => false,
  });

  private esperaBusqueda: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.esperaBusqueda));
    afterRenderEffect({
      read: () => {
        this.locales(); // al llegar los locales (o un aviso) cambia el alto: se mide de nuevo
        if (!this.ruta() || this.baja()) return;
        const el = this.pop().nativeElement;
        if (el.scrollHeight > el.clientHeight + 1) this.desborda.set(true);
      },
    });
  }

  /** Locales del Excel, de más a menos bultos (los de más carga primero). */
  readonly todos = computed<LocalRuta[]>(() => {
    const estado = this.locales();
    return estado?.estado === 'listo'
      ? [...estado.datos.locales].sort((a, b) => b.bultos - a.bultos || (a.cod ?? '').localeCompare(b.cod ?? ''))
      : [];
  });

  readonly filtrando = computed(() => this.busqueda().trim() !== '' || this.minimo() !== null);

  readonly visibles = computed(() => {
    const texto = this.busqueda().trim().toLocaleLowerCase('es');
    const minimo = this.minimo();
    return this.todos().filter(
      (local) =>
        (texto === '' || (local.cod ?? 'sin cod').toLocaleLowerCase('es').includes(texto)) &&
        (minimo === null || local.bultos >= minimo),
    );
  });

  /** Las que se dibujan ahora (el resto, con «Mostrar más»). */
  readonly mostrados = computed(() => this.visibles().slice(0, this.limite()));
  readonly quedan = computed(() => this.visibles().length - this.mostrados().length);
  readonly siguientes = computed(() => Math.min(this.quedan(), PASO));

  /** Totales de todos los locales que pasan el filtro (pie de la cuadrícula), dibujados o no. */
  readonly totalesVisibles = computed(() => sumar(this.visibles()));

  /** Rótulo del pie: «Total», «7 de 14» al filtrar o «Primeros 5 000» si la ruta tiene más. */
  readonly etiquetaPie = computed(() => {
    const estado = this.locales();
    if (this.filtrando()) {
      return `${this.visibles().length} de ${this.todos().length}`;
    }
    return estado?.estado === 'listo' && estado.datos.truncado ? `Primeros ${formatearEntero(this.todos().length)}` : 'Total';
  });

  /**
   * Filtra al dejar de escribir ({@link ESPERA_BUSQUEDA_MS}): con miles de locales, refiltrar en
   * cada tecla tardaba 272 ms por tecla en un celular. Lo escrito para otra ruta no se aplica.
   */
  buscar(evento: Event): void {
    const valor = (evento.target as HTMLInputElement).value;
    const ruta = this.ruta()?.ruta.id;
    clearTimeout(this.esperaBusqueda);
    this.esperaBusqueda = setTimeout(() => {
      if (this.ruta()?.ruta.id === ruta) {
        this.busqueda.set(valor);
      }
    }, ESPERA_BUSQUEDA_MS);
  }

  mostrarMas(): void {
    this.limite.update((n) => n + PASO);
  }

  elegirExcel(evento: Event): void {
    const entrada = evento.target as HTMLInputElement;
    const archivo = entrada.files?.[0];
    // Se limpia: elegir el mismo archivo otra vez vuelve a disparar el cambio.
    entrada.value = '';
    if (archivo) {
      this.completar.emit(archivo);
    }
  }

  filtrarBultos(evento: Event): void {
    const valor = (evento.target as HTMLInputElement).value.trim();
    const numero = Number(valor.replace(',', '.'));
    this.minimo.set(valor === '' || !Number.isFinite(numero) || numero <= 0 ? null : numero);
  }
}

function sumar(locales: LocalRuta[]): { bultos: number; unidades: number; lineas: number } {
  let bultos = 0;
  let unidades = 0;
  let lineas = 0;
  for (const local of locales) {
    bultos += Number(local.bultos);
    unidades += Number(local.unidades);
    lineas += Number(local.lineas);
  }
  return { bultos, unidades, lineas };
}
