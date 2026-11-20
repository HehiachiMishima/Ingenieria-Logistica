import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  input,
  linkedSignal,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core';
import { contieneTexto } from '../../../../../core/utils/texto.util';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { PermisoDelUsuario } from '../../data/permiso.model';
import { ArrastreFichas, SoltarFichas } from '../tablero-fichas/arrastre-fichas.directive';
import { llevarAColumna, seguirColumnasVisibles } from '../tablero-fichas/columnas-catalogo';
import { COLUMNAS_CRUD, ColumnaCrud, FichaPermiso, clasificarPermiso } from '../tablero-permisos/ficha-permiso';

/** Lado del tablero: el catálogo (hereda del rol) o una de las dos zonas de excepciones. */
export type ZonaExcepcion = 'catalogo' | 'concedidos' | 'denegados';
/** Estado de un permiso para el usuario. */
export type EstadoExcepcion = 'hereda' | 'concedido' | 'denegado';

/** Permiso listo para dibujar: clasificación, estado con lo no guardado y si queda efectivo. */
export interface FichaExcepcion extends PermisoDelUsuario, FichaPermiso {
  original: EstadoExcepcion;
  estado: EstadoExcepcion;
  movida: boolean;
  /** El usuario lo tendrá al guardar (activo y concedido, o heredado de un rol). */
  efectivo: boolean;
  colorAccion: string;
  /** Texto completo para el título y los lectores de pantalla. */
  resumen: string;
}

export interface ColumnaExcepciones extends ColumnaCrud {
  fichas: FichaExcepcion[];
  coincidencias: number;
}

export interface CambiosExcepciones {
  conceder: number[];
  denegar: number[];
  heredar: number[];
}

const SIN_CAMBIOS: ReadonlyMap<number, EstadoExcepcion> = new Map();
const VACIO: ReadonlySet<number> = new Set();
const COLOR_ACCION = new Map(COLUMNAS_CRUD.map((c) => [c.accion, c.color]));
const TITULO_ACCION = new Map(COLUMNAS_CRUD.map((c) => [c.accion, c.titulo]));
const ESTADO_DE: Record<ZonaExcepcion, EstadoExcepcion> = {
  catalogo: 'hereda',
  concedidos: 'concedido',
  denegados: 'denegado',
};

/** Aviso tras mover, en singular y plural. */
const AVISO_MOVIDAS: Record<ZonaExcepcion, [string, string]> = {
  concedidos: ['permiso concedido', 'permisos concedidos'],
  denegados: ['permiso denegado', 'permisos denegados'],
  catalogo: ['permiso devuelto a su rol', 'permisos devueltos a su rol'],
};

function estadoOriginal(permiso: PermisoDelUsuario): EstadoExcepcion {
  return permiso.excepcion === true ? 'concedido' : permiso.excepcion === false ? 'denegado' : 'hereda';
}

/**
 * Tablero de permisos individuales de un usuario (presentación): a la izquierda el catálogo por
 * acción (lo que hereda de sus roles), a la derecha sus excepciones en dos zonas, «Concedidos» y
 * «Denegados». Arrastrar a una zona crea la excepción; sacarla de ahí vuelve a heredar. Los
 * cambios quedan pendientes hasta que la página los guarda en lote.
 */
@Component({
  selector: 'app-tablero-excepciones',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ArrastreFichas, Icon, NgTemplateOutlet],
  templateUrl: './tablero-excepciones.html',
  styleUrls: [
    '../tablero-permisos/tablero-permisos.scss',
    '../tablero-permisos/tablero-ficha.scss',
    './tablero-excepciones.scss',
  ],
  host: { '(keydown.escape)': 'alEscape($event)' },
})
export class TableroExcepciones {
  readonly permisos = input<readonly PermisoDelUsuario[]>([]);
  readonly nombreUsuario = input('');
  /** Motivo por el que quien edita no puede cambiar las excepciones (todo de solo lectura). */
  readonly bloqueo = input<string | null>(null);
  readonly guardando = input(false);

  /** Estado elegido para cada permiso movido y sin guardar; se vacía al llegar el catálogo. */
  private readonly pendientes = linkedSignal<readonly PermisoDelUsuario[], ReadonlyMap<number, EstadoExcepcion>>({
    source: this.permisos,
    computation: () => SIN_CAMBIOS,
  });
  readonly elegidas = linkedSignal<readonly PermisoDelUsuario[], ReadonlySet<number>>({
    source: this.permisos,
    computation: () => VACIO,
  });
  readonly ladoElegido = linkedSignal<readonly PermisoDelUsuario[], ZonaExcepcion | null>({
    source: this.permisos,
    computation: () => null,
  });
  private ancla: number | null = null;

  readonly busqueda = signal('');
  readonly aviso = linkedSignal<readonly PermisoDelUsuario[], string | null>({
    source: this.permisos,
    computation: () => null,
  });

  readonly fichas = computed<FichaExcepcion[]>(() => {
    const pendientes = this.pendientes();
    return this.permisos().map((permiso) => {
      const clase = clasificarPermiso(permiso.nombre);
      const original = estadoOriginal(permiso);
      const estado = pendientes.get(permiso.id) ?? original;
      const ficha = {
        ...permiso,
        ...clase,
        original,
        estado,
        movida: estado !== original,
        efectivo: permiso.activo && (estado === 'concedido' || (estado === 'hereda' && permiso.porRol)),
        colorAccion: COLOR_ACCION.get(clase.accion) ?? '#334155',
      };
      return { ...ficha, resumen: describir(ficha) };
    });
  });

  private readonly porId = computed(() => new Map(this.fichas().map((f) => [f.id, f])));

  readonly coinciden = computed<ReadonlySet<number> | null>(() => {
    const termino = this.busqueda().trim();
    if (!termino) {
      return null;
    }
    return new Set(
      this.fichas()
        .filter((f) => contieneTexto([f.nombre, f.descripcion, f.etiqueta, f.nombrePanel, f.panel], termino))
        .map((f) => f.id),
    );
  });

  readonly columnas = computed<ColumnaExcepciones[]>(() => {
    const coinciden = this.coinciden();
    return COLUMNAS_CRUD.map((columna) => {
      const fichas = this.fichas().filter((f) => f.accion === columna.accion && f.estado === 'hereda');
      return {
        ...columna,
        fichas,
        coincidencias: coinciden ? fichas.filter((f) => coinciden.has(f.id)).length : fichas.length,
      };
    }).filter((c) => c.accion !== 'O' || c.fichas.length > 0);
  });

  readonly concedidos = computed(() => this.fichas().filter((f) => f.estado === 'concedido'));
  readonly denegados = computed(() => this.fichas().filter((f) => f.estado === 'denegado'));

  readonly resumenEfectivos = computed(() => {
    const fichas = this.fichas();
    return {
      efectivos: fichas.filter((f) => f.efectivo).length,
      delRol: fichas.filter((f) => f.estado === 'hereda' && f.porRol && f.activo).length,
    };
  });
  /** Concedidos que ya da su rol: la excepción sobra. */
  readonly concedidosRedundantes = computed(() => this.concedidos().filter((f) => f.porRol).length);
  /** Denegados que su rol no da: la excepción no cambia nada hoy. */
  readonly denegadosSinEfecto = computed(() => this.denegados().filter((f) => !f.porRol).length);
  readonly coincidenciasTotales = computed(() => this.coinciden()?.size ?? 0);
  /** Coincidencias del catálogo que se pueden elegir (activas). */
  readonly elegiblesBusqueda = computed(() => {
    const coinciden = this.coinciden();
    return coinciden ? this.fichas().filter((f) => f.estado === 'hereda' && f.activo && coinciden.has(f.id)) : [];
  });

  readonly cambios = computed<CambiosExcepciones>(() => {
    const conceder: number[] = [];
    const denegar: number[] = [];
    const heredar: number[] = [];
    for (const ficha of this.fichas()) {
      if (!ficha.movida) continue;
      (ficha.estado === 'concedido' ? conceder : ficha.estado === 'denegado' ? denegar : heredar).push(ficha.id);
    }
    return { conceder, denegar, heredar };
  });
  readonly totalCambios = computed(() => this.fichas().filter((f) => f.movida).length);

  readonly validar = (ids: readonly number[], destino: string) => this.motivo(ids, destino as ZonaExcepcion);
  readonly grupo = (id: number): readonly number[] => (this.elegidas().has(id) ? [...this.elegidas()] : [id]);

  private readonly catalogo = viewChild.required<ElementRef<HTMLElement>>('catalogo');
  private readonly columnasCatalogo = viewChildren<ElementRef<HTMLElement>>('columnaCatalogo');
  readonly visibles = signal<ReadonlySet<string>>(new Set());

  constructor() {
    effect((alLimpiar) => {
      const raiz = this.catalogo().nativeElement;
      const columnas = this.columnasCatalogo().map((ref) => ref.nativeElement);
      alLimpiar(seguirColumnasVisibles(raiz, columnas, (visibles) => this.visibles.set(visibles)));
    });
  }

  irAColumna(accion: string): void {
    llevarAColumna(
      this.columnasCatalogo().map((ref) => ref.nativeElement),
      accion,
    );
  }

  buscar(termino: string): void {
    this.busqueda.set(termino);
  }

  elegirCoincidencias(): void {
    const ids = this.elegiblesBusqueda().map((f) => f.id);
    this.ladoElegido.set(ids.length ? 'catalogo' : null);
    this.elegidas.set(new Set(ids));
    this.ancla = null;
    this.aviso.set(`${ids.length} ${ids.length === 1 ? 'permiso elegido' : 'permisos elegidos'}.`);
  }

  /** Destinos posibles de la selección actual (botones para teclado y pantallas táctiles). */
  readonly destinosSeleccion = computed<ZonaExcepcion[]>(() => {
    const lado = this.ladoElegido();
    return lado ? (['concedidos', 'denegados', 'catalogo'] as const).filter((z) => z !== lado) : [];
  });

  /**
   * Clic: alterna la ficha en la selección de su lado (elegir en otro lado empieza de cero).
   * Mayús + clic: elige el tramo desde la última ficha tocada en el mismo lado.
   */
  alternar(ficha: FichaExcepcion, lado: ZonaExcepcion, evento?: Event): void {
    if (this.soloLectura()) {
      this.aviso.set(this.bloqueo());
      return;
    }
    if (lado === 'catalogo' && !ficha.activo && !this.elegidas().has(ficha.id)) {
      this.aviso.set(this.motivoFicha(ficha, 'concedidos'));
      return;
    }
    const mismoLado = this.ladoElegido() === lado;
    const siguiente = new Set(mismoLado ? this.elegidas() : VACIO);
    const conMayus = (evento instanceof MouseEvent || evento instanceof KeyboardEvent) && evento.shiftKey;
    const tramo = conMayus && mismoLado ? this.tramo(ficha, lado) : null;
    if (tramo) {
      for (const id of tramo) {
        siguiente.add(id);
      }
    } else if (siguiente.has(ficha.id)) {
      siguiente.delete(ficha.id);
    } else {
      siguiente.add(ficha.id);
    }
    this.ancla = ficha.id;
    this.ladoElegido.set(siguiente.size ? lado : null);
    this.elegidas.set(siguiente);
    this.aviso.set(null);
  }

  moverElegidas(destino: ZonaExcepcion): void {
    this.aplicar([...this.elegidas()], destino);
  }

  limpiarSeleccion(): void {
    this.elegidas.set(VACIO);
    this.ladoElegido.set(null);
    this.ancla = null;
  }

  descartar(): void {
    this.pendientes.set(SIN_CAMBIOS);
    this.limpiarSeleccion();
    this.aviso.set('Cambios descartados.');
  }

  alSoltar(evento: SoltarFichas): void {
    if (evento.motivo) {
      this.aviso.set(evento.motivo);
      return;
    }
    this.aplicar(evento.ids, evento.destino as ZonaExcepcion);
  }

  alEscape(evento: Event): void {
    if (this.elegidas().size) {
      this.limpiarSeleccion();
    } else if (this.busqueda()) {
      this.busqueda.set('');
    } else {
      return;
    }
    evento.stopPropagation();
    evento.preventDefault();
  }

  /** Arrastrable si al menos tiene a dónde ir (un permiso inactivo del catálogo no lo tiene). */
  arrastrable(ficha: FichaExcepcion): boolean {
    return this.bloqueo() === null && !(ficha.estado === 'hereda' && !ficha.activo);
  }

  textoDestino(destino: ZonaExcepcion): string {
    return destino === 'concedidos' ? 'Conceder' : destino === 'denegados' ? 'Denegar' : 'Heredar del rol';
  }

  private soloLectura(): boolean {
    return this.guardando() || this.bloqueo() !== null;
  }

  private aplicar(ids: readonly number[], destino: ZonaExcepcion): void {
    const motivo = this.motivo(ids, destino);
    if (motivo) {
      this.aviso.set(motivo);
      return;
    }
    const estado = ESTADO_DE[destino];
    const pendientes = new Map(this.pendientes());
    let movidas = 0;
    for (const id of ids) {
      const ficha = this.porId().get(id);
      if (!ficha || ficha.estado === estado) {
        continue;
      }
      movidas++;
      if (estado === ficha.original) {
        pendientes.delete(id);
      } else {
        pendientes.set(id, estado);
      }
    }
    this.pendientes.set(pendientes);
    this.limpiarSeleccion();
    const [uno, varios] = AVISO_MOVIDAS[destino];
    this.aviso.set(`${movidas} ${movidas === 1 ? uno : varios} (falta guardar).`);
  }

  /** Por qué esos permisos no pueden ir a `destino`; null si pueden. */
  private motivo(ids: readonly number[], destino: ZonaExcepcion): string | null {
    if (this.soloLectura()) {
      return this.bloqueo() ?? 'Espere a que terminen de guardarse los cambios.';
    }
    for (const id of ids) {
      const ficha = this.porId().get(id);
      const motivo = ficha ? this.motivoFicha(ficha, destino) : null;
      if (motivo) {
        return motivo;
      }
    }
    return null;
  }

  private motivoFicha(ficha: FichaExcepcion, destino: ZonaExcepcion): string | null {
    const estado = ESTADO_DE[destino];
    if (ficha.estado === estado || estado === 'hereda') {
      return null; // ya está ahí, o vuelve a heredar: siempre se puede
    }
    if (!ficha.activo && ficha.original !== estado) {
      return `«${ficha.nombre}» está inactivo: reactívelo en Permisos antes de crear una excepción.`;
    }
    if (estado === 'concedido' && !ficha.otorgable && ficha.original !== 'concedido') {
      return `No puede conceder «${ficha.nombre}»: usted no tiene ese permiso.`;
    }
    return null;
  }

  private tramo(ficha: FichaExcepcion, lado: ZonaExcepcion): number[] | null {
    const lista =
      lado === 'catalogo'
        ? this.columnas().find((c) => c.accion === ficha.accion)?.fichas
        : lado === 'concedidos'
          ? this.concedidos()
          : this.denegados();
    const desde = lista?.findIndex((f) => f.id === this.ancla) ?? -1;
    const hasta = lista?.findIndex((f) => f.id === ficha.id) ?? -1;
    if (!lista || desde < 0 || hasta < 0) {
      return null;
    }
    const [a, b] = desde < hasta ? [desde, hasta] : [hasta, desde];
    return lista
      .slice(a, b + 1)
      .filter((f) => lado !== 'catalogo' || f.activo)
      .map((f) => f.id);
  }
}

/** Texto completo de la ficha para el título y los lectores de pantalla. */
function describir(ficha: Omit<FichaExcepcion, 'resumen'>): string {
  const estado =
    ficha.estado === 'concedido'
      ? 'concedido a este usuario'
      : ficha.estado === 'denegado'
        ? 'denegado a este usuario'
        : ficha.porRol
          ? 'lo da su rol'
          : 'su rol no lo da';
  const partes = [
    ficha.nombre,
    TITULO_ACCION.get(ficha.accion),
    ficha.nombrePanel || null,
    ficha.descripcion || null,
    ficha.activo ? estado : 'inactivo',
    ficha.estado !== 'hereda' ? (ficha.porRol ? 'su rol lo da' : 'su rol no lo da') : null,
    ficha.estado === 'hereda' && !ficha.otorgable ? 'usted no puede concederlo' : null,
    ficha.movida ? 'cambio sin guardar' : null,
  ];
  return partes.filter(Boolean).join(' · ');
}
