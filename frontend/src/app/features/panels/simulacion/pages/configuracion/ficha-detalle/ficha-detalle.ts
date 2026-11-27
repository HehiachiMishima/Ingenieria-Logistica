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
  signal,
  viewChild,
  type Signal,
} from '@angular/core';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { IconoSim } from '../../../icono-sim/icono-sim';
import { LocalRuta, LocalesRuta } from '../../../data/simulacion.model';
import { EnteroPipe, HorasPipe, NumeroPipe } from '../../../numero.pipe';
import { formatearEntero } from '../../../simulacion-formato';
import { pastillaArrastre } from '../arrastre-rutas.directive';
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

/** Ruta que puede recibir locales (las opciones de «Mover a…»), agrupada por DATA o su cluster. */
export interface DestinoLocales {
  id: number;
  etiqueta: string;
  grupo: string;
}

/** Locales (por su orden) que pasan a otra ruta con «Mover a…». */
export interface MoverLocalesA {
  ordenes: readonly number[];
  destinoId: number;
}

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
  /** Hay otra operación en curso: Editar y Eliminar esperan. */
  readonly bloqueado = input(false);
  readonly cerrar = output<void>();
  readonly reintentar = output<void>();
  /** Solo rutas de DATA: abrir el formulario o eliminarla. */
  readonly editar = output<void>();
  readonly eliminar = output<void>();
  /** La ruta puede dar locales (DATA o un cluster sin empezar, con permiso): se eligen y se arrastran. */
  readonly localesMovibles = input(false);
  /**
   * Rutas que pueden recibirlos (las opciones de «Mover a…»). Llega la señal, no la lista: se arma
   * solo cuando se muestra «Mover a…», no en cada cambio de la matriz.
   */
  readonly destinosLocales = input<Signal<readonly DestinoLocales[]> | null>(null);
  /** Locales (por su orden) que empiezan a arrastrarse; null al terminar. */
  readonly arrastreLocales = output<readonly number[] | null>();
  readonly moverLocales = output<MoverLocalesA>();

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
  /** Título, totales y mini fichas por día del cluster: se miden para que nada se corte. */
  private readonly tituloCluster = viewChild<ElementRef<HTMLElement>>('tituloCluster');
  private readonly totalesCluster = viewChild<ElementRef<HTMLElement>>('totalesCluster');
  private readonly diasCluster = viewChild<ElementRef<HTMLElement>>('diasCluster');

  /** Cifras del cluster abierto y el tipo de ficha: si cambian, su ficha se mide de nuevo. */
  private readonly cifrasCluster = computed(() => {
    const k = this.cluster();
    if (!k) return '';
    const m = k.m;
    const dias = k.dias.map((d) => [d.cod, d.bultos, d.unidades, d.operarios, d.rutas, d.lineas, d.dispersion, d.tiempo].join(','));
    return [k.c.id, m?.rutas, k.c.chutes, m?.bultos, m?.unidades, m?.lineas, m?.cod, k.operarios, k.cierre?.tiempo, k.estado, k.etiquetaVista, ...dias, this.posicion().hoja].join('|');
  });

  /**
   * Alguna cifra (o nombre) de los totales no cabe en su bloque: pasan de tres columnas a dos, con
   * más ancho cada uno. Igual que la cabecera, solo de no a sí.
   */
  readonly totalesDos = linkedSignal({ source: this.cifrasCluster, computation: () => false });

  /**
   * «Cluster CL1» no cabe al lado de su ficha (cifras grandes o texto agrandado): el título baja bajo
   * la ficha, como en la ficha de una ruta angosta. Solo pasa de no a sí, así no oscila; se mide de
   * nuevo con otro cluster o con otras cifras.
   */
  readonly cabeceraApilada = linkedSignal({ source: this.cifrasCluster, computation: () => false });

  /**
   * Alguna cifra de las mini fichas por día no cabe en su línea: todas pasan a nombre arriba y cifra
   * abajo, así quedan parejas. Igual que la cabecera, solo de no a sí.
   */
  readonly diasApilados = linkedSignal({ source: this.cifrasCluster, computation: () => false });

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

  /**
   * La ruta abierta y sus locales tal como llegaron: cambia al abrir otra ruta o al recargarlos, no
   * cuando la ficha se recalcula por otra cosa (cambiar la vista del cluster, por ejemplo).
   */
  private readonly claveLocales = computed(
    () => ({ ruta: this.ruta()?.ruta.id, locales: this.locales() }),
    { equal: (a, b) => a.ruta === b.ruta && a.locales === b.locales },
  );

  /**
   * Locales elegidos (por su orden). Vuelven a vacío con otra ruta o al recargar sus locales; al
   * filtrar, solo siguen elegidos los que se ven, así nunca se mueve un local oculto.
   */
  readonly elegidos = linkedSignal<{ clave: unknown; visibles: readonly LocalRuta[] }, ReadonlySet<number>>({
    source: () => ({ clave: this.claveLocales(), visibles: this.visibles() }),
    computation: (actual, previo) => {
      // Otra ruta o sus locales recargados: empieza vacía.
      if (!previo || previo.source.clave !== actual.clave) {
        return new Set();
      }
      if (previo.value.size === 0) {
        return previo.value;
      }
      const visibles = new Set(actual.visibles.map((local) => local.orden));
      const quedan = [...previo.value].filter((orden) => visibles.has(orden));
      return quedan.length === previo.value.size ? previo.value : new Set(quedan);
    },
  });
  /** Ruta elegida en «Mover a…». */
  readonly destino = linkedSignal<unknown, number | null>({ source: () => this.elegidos(), computation: () => null });
  /** Arrastrando locales: la ficha se vuelve translúcida y deja alcanzar las rutas que tapa. */
  readonly arrastrando = signal(false);
  /**
   * Con el dedo el arrastre nativo falla: en pantalla táctil o con la ficha como hoja inferior, los
   * elegidos se pasan con «Mover a…».
   */
  private readonly tactil = typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches;
  /** Se eligió un local con el teclado: también hace falta «Mover a…» (con teclado no se arrastra). */
  private readonly conTeclado = linkedSignal({ source: this.claveLocales, computation: () => false });
  readonly conBoton = computed(() => this.posicion().hoja || this.tactil || this.conTeclado());
  /** Último local pulsado (Mayús+clic elige desde él hasta otro): se olvida con otra ruta o al recargar. */
  private readonly ancla = linkedSignal<unknown, number | null>({ source: this.claveLocales, computation: () => null });
  private enArrastre = false;

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
    afterRenderEffect({
      read: () => {
        const desborda = (e: Element) => e.scrollWidth > e.clientWidth + 1;
        const titulo = this.tituloCluster()?.nativeElement;
        // Sin el texto solo para el lector de pantalla (1 px, siempre «desborda»).
        const visibles = titulo ? [...titulo.children].filter((e) => !e.classList.contains('k-oculta')) : [];
        if (titulo && !this.cabeceraApilada() && visibles.some(desborda)) this.cabeceraApilada.set(true);
        const totales = this.totalesCluster()?.nativeElement;
        if (totales && !this.totalesDos() && [...totales.querySelectorAll('dt, dd')].some(desborda)) this.totalesDos.set(true);
        const dias = this.diasCluster()?.nativeElement;
        if (dias && !this.diasApilados() && [...dias.querySelectorAll('.k-dia__c')].some((c) => c.scrollWidth > c.clientWidth + 1)) {
          this.diasApilados.set(true);
        }
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

  /** Lo que muestra la barra de elegidos: cuántos, sus bultos y si son todos los de la ruta. */
  readonly resumenElegidos = computed(() => {
    const ids = this.elegidos();
    if (ids.size === 0) {
      return null;
    }
    const todos = this.todos();
    const bultos = todos.reduce((t, local) => (ids.has(local.orden) ? t + Number(local.bultos) : t), 0);
    return { n: ids.size, bultos, todos: ids.size >= todos.length };
  });

  /** Opciones de «Mover a…» por grupo (DATA, CL1…), en el orden en que llegan; solo si se muestra. */
  readonly gruposDestino = computed(() => {
    const destinos = this.conBoton() && this.resumenElegidos() !== null ? (this.destinosLocales()?.() ?? []) : [];
    const grupos = new Map<string, DestinoLocales[]>();
    for (const destino of destinos) {
      const lista = grupos.get(destino.grupo);
      if (lista) {
        lista.push(destino);
      } else {
        grupos.set(destino.grupo, [destino]);
      }
    }
    return [...grupos].map(([grupo, opciones]) => ({ grupo, opciones }));
  });

  /** Clic (o Enter/Espacio) elige o suelta un local; con Mayús, el tramo desde el último pulsado. */
  elegir(evento: Event, orden: number): void {
    if (!this.localesMovibles() || this.bloqueado()) {
      return;
    }
    if (evento instanceof KeyboardEvent) {
      this.conTeclado.set(true);
    }
    const ancla = this.ancla();
    this.elegidos.update((actual) => {
      const siguiente = new Set(actual);
      if ((evento as MouseEvent).shiftKey && ancla !== null) {
        const lista = this.mostrados().map((local) => local.orden);
        const desde = lista.indexOf(ancla);
        const hasta = lista.indexOf(orden);
        if (desde >= 0 && hasta >= 0) {
          lista.slice(Math.min(desde, hasta), Math.max(desde, hasta) + 1).forEach((o) => siguiente.add(o));
          return siguiente;
        }
      }
      if (!siguiente.delete(orden)) {
        siguiente.add(orden);
      }
      return siguiente;
    });
    this.ancla.set(orden);
  }

  limpiarElegidos(): void {
    this.elegidos.set(new Set());
    this.ancla.set(null);
  }

  /** Se arrastra el local tomado y, si estaba elegido, todos los elegidos. */
  iniciarArrastre(evento: DragEvent, orden: number): void {
    if (!this.localesMovibles() || this.bloqueado()) {
      evento.preventDefault();
      return;
    }
    const elegidos = this.elegidos();
    const ordenes = elegidos.has(orden) ? [...elegidos] : [orden];
    if (evento.dataTransfer) {
      evento.dataTransfer.effectAllowed = 'move';
      // Firefox no arrastra sin datos.
      evento.dataTransfer.setData('text/plain', ordenes.join(','));
      if (ordenes.length > 1) {
        pastillaArrastre(evento.dataTransfer, `${ordenes.length} locales`);
      }
    }
    this.enArrastre = true;
    this.arrastreLocales.emit(ordenes);
    // Chrome cancela el arrastre si el origen cambia de estilo dentro de `dragstart`.
    requestAnimationFrame(() => this.arrastrando.set(this.enArrastre));
  }

  terminarArrastre(): void {
    this.enArrastre = false;
    this.arrastrando.set(false);
    this.arrastreLocales.emit(null);
  }

  elegirDestino(evento: Event): void {
    const valor = (evento.target as HTMLSelectElement).value;
    this.destino.set(valor === '' ? null : Number(valor));
  }

  moverConBoton(): void {
    const destinoId = this.destino();
    const ordenes = [...this.elegidos()];
    if (destinoId !== null && ordenes.length > 0 && !this.bloqueado()) {
      this.moverLocales.emit({ ordenes, destinoId });
    }
  }

  mostrarMas(): void {
    this.limite.update((n) => n + PASO);
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
