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
} from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, map, of, startWith, switchMap } from 'rxjs';
import { toSignal } from '@angular/core/rxjs-interop';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { DiccionarioApi } from '../../../data/diccionario.api';
import { LocalDiccionario, RutaDiccionario, TEXTO_FRECUENCIA } from '../../../data/diccionario.model';
import { EnteroPipe, NumeroPipe } from '../../../numero.pipe';

/** Mini fichas que se dibujan de una vez (y cuántas más con cada «Mostrar más»). */
const PASO = 200;
/** Espera tras la última tecla antes de filtrar los locales. */
const ESPERA_BUSQUEDA_MS = 150;
/** Bajo este alto (en rem) la ficha desplaza entera en lugar de darle scroll propio a la cuadrícula. */
const ALTO_MINIMO_CUADRICULA_REM = 35;

/** Dónde se dibuja la ficha (la calcula el módulo, igual que en Configuración). */
export interface PosicionFicha {
  hoja: boolean;
  left: number | null;
  top: number | null;
  bottom: number | null;
  maxAlto: number | null;
}

type EstadoLocales =
  | { estado: 'cargando' }
  | { estado: 'error'; mensaje: string }
  | { estado: 'listo'; locales: LocalDiccionario[] };

/**
 * Ficha flotante de una ruta del diccionario: la misma de Configuración (planilla arriba, totales y
 * la cuadrícula de sus locales con buscador y bultos mínimos), con Editar y Eliminar al pie.
 */
@Component({
  selector: 'app-ruta-ficha',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, EnteroPipe, NumeroPipe, NgTemplateOutlet],
  templateUrl: './ruta-ficha.html',
  styleUrls: [
    '../../configuracion/ficha-detalle/ficha-detalle.scss',
    '../../configuracion/configuracion-ficha.scss',
    '../../configuracion/ficha-detalle/ficha-locales.scss',
    './ruta-ficha.scss',
  ],
})
export class RutaFicha {
  private readonly api = inject(DiccionarioApi);

  readonly posicion = input.required<PosicionFicha>();
  readonly ruta = input.required<RutaDiccionario>();
  /** Nombre del bloque donde está («Datos» o «CL2»). */
  readonly cluster = input.required<string>();
  readonly color = input<string | null>(null);
  readonly cerrar = output<void>();
  readonly editar = output<{ ruta: RutaDiccionario; locales: LocalDiccionario[] }>();
  readonly eliminar = output<RutaDiccionario>();

  readonly textoFrecuencia = TEXTO_FRECUENCIA;

  private readonly reintento = signal(0);

  /** Sus locales; se vuelven a pedir si cambia la ruta, sus totales o al reintentar. */
  readonly estado = toSignal(
    toObservable(
      computed(
        () => {
          const r = this.ruta();
          return { id: r.id, clave: `${r.id}|${r.codigoRuta}|${r.tipo}|${r.locales}|${r.bultos}`, intento: this.reintento() };
        },
        { equal: (a, b) => a.clave === b.clave && a.intento === b.intento },
      ),
    ).pipe(
      switchMap(({ id }) =>
        this.api.detalle(id).pipe(
          map((d): EstadoLocales => ({ estado: 'listo', locales: d.locales })),
          catchError((e: HttpErrorResponse) =>
            of<EstadoLocales>({
              estado: 'error',
              mensaje: e.status === 404 ? 'La ruta ya no existe.' : 'Sin conexión con el servidor.',
            }),
          ),
          startWith<EstadoLocales>({ estado: 'cargando' }),
        ),
      ),
    ),
    { initialValue: { estado: 'cargando' } as EstadoLocales },
  );

  reintentar(): void {
    this.reintento.update((n) => n + 1);
  }

  /** Búsqueda por código (ya aplicada) y bultos mínimos: vuelven a vacío al abrir otra ruta. */
  readonly busqueda = linkedSignal({ source: () => this.ruta().id, computation: () => '' });
  readonly minimo = linkedSignal<number, number | null>({ source: () => this.ruta().id, computation: () => null });
  readonly limite = linkedSignal({
    source: () => ({ ruta: this.ruta().id, texto: this.busqueda(), minimo: this.minimo() }),
    computation: () => PASO,
  });

  /** Locales de más a menos bultos (los de más carga primero). */
  readonly todos = computed<LocalDiccionario[]>(() => {
    const e = this.estado();
    return e.estado === 'listo'
      ? [...e.locales].sort((a, b) => b.bultos - a.bultos || a.cod.localeCompare(b.cod))
      : [];
  });

  readonly filtrando = computed(() => this.busqueda().trim() !== '' || this.minimo() !== null);

  readonly visibles = computed(() => {
    const texto = this.busqueda().trim().toUpperCase();
    const minimo = this.minimo();
    return this.todos().filter(
      (l) => (texto === '' || l.cod.includes(texto)) && (minimo === null || l.bultos >= minimo),
    );
  });

  readonly mostrados = computed(() => this.visibles().slice(0, this.limite()));
  readonly quedan = computed(() => this.visibles().length - this.mostrados().length);
  readonly siguientes = computed(() => Math.min(this.quedan(), PASO));
  readonly totalesVisibles = computed(() => sumar(this.visibles()));
  readonly etiquetaPie = computed(() =>
    this.filtrando() ? `${this.visibles().length} de ${this.todos().length}` : 'Total',
  );

  private esperaBusqueda: ReturnType<typeof setTimeout> | undefined;

  buscar(evento: Event): void {
    const valor = (evento.target as HTMLInputElement).value;
    const ruta = this.ruta().id;
    clearTimeout(this.esperaBusqueda);
    this.esperaBusqueda = setTimeout(() => {
      if (this.ruta().id === ruta) {
        this.busqueda.set(valor);
      }
    }, ESPERA_BUSQUEDA_MS);
  }

  filtrarBultos(evento: Event): void {
    const valor = (evento.target as HTMLInputElement).value.trim();
    const numero = Number(valor.replace(',', '.'));
    this.minimo.set(valor === '' || !Number.isFinite(numero) || numero <= 0 ? null : numero);
  }

  mostrarMas(): void {
    this.limite.update((n) => n + PASO);
  }

  pedirEdicion(): void {
    const e = this.estado();
    if (e.estado === 'listo') {
      this.editar.emit({ ruta: this.ruta(), locales: e.locales });
    }
  }

  // ---------------- Ficha baja: un solo scroll ----------------

  private readonly pop = viewChild.required<ElementRef<HTMLElement>>('pop');

  /** Desbordó con la cuadrícula en su alto mínimo: solo pasa de no a sí (no oscila). */
  private readonly desborda = linkedSignal({
    source: () => {
      const pos = this.posicion();
      return `${this.ruta().id}|${pos.hoja}|${pos.maxAlto}`;
    },
    computation: () => false,
  });

  /**
   * Ficha baja (laptop con zoom, celular horizontal o pequeño): la cuadrícula no lleva scroll
   * propio y desplaza la ficha entera, así no hay dos barras de scroll una dentro de otra.
   */
  readonly baja = computed(() => {
    if (this.desborda()) {
      return true;
    }
    const pos = this.posicion();
    const alto = pos.hoja ? window.innerHeight * 0.85 : (pos.maxAlto ?? window.innerHeight);
    const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
    return alto < ALTO_MINIMO_CUADRICULA_REM * rem;
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.esperaBusqueda));
    afterRenderEffect({
      read: () => {
        this.estado(); // al llegar los locales cambia el alto: se mide de nuevo
        if (this.baja()) {
          return;
        }
        const el = this.pop().nativeElement;
        if (el.scrollHeight > el.clientHeight + 1) {
          this.desborda.set(true);
        }
      },
    });
  }
}

function sumar(locales: LocalDiccionario[]): { bultos: number; unidades: number; lineas: number } {
  let bultos = 0;
  let unidades = 0;
  let lineas = 0;
  for (const l of locales) {
    bultos += Number(l.bultos);
    unidades += Number(l.unidades);
    lineas += Number(l.lineas);
  }
  return { bultos, unidades, lineas };
}
