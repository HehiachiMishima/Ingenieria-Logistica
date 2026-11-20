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
import { PermisoDelRol } from '../../data/permiso.model';
import { ArrastreFichas, SoltarFichas } from '../tablero-fichas/arrastre-fichas.directive';
import { COLUMNAS_CRUD, ColumnaCrud, FichaPermiso, clasificarPermiso } from './ficha-permiso';

/** Lado del tablero: el catálogo (lo que el rol no tiene) o la columna del rol. */
export type ZonaPermisos = 'catalogo' | 'rol';

/** Permiso listo para dibujar: su clasificación y dónde queda con los cambios sin guardar. */
export interface Ficha extends PermisoDelRol, FichaPermiso {
  /** Está en el rol contando lo que se movió y aún no se guarda. */
  enRol: boolean;
  /** Se movió de lado y falta guardar. */
  movida: boolean;
  /** Roles que lo tendrán al guardar. */
  usos: number;
  colorAccion: string;
  /** Por qué no puede pasar al otro lado (null = puede). */
  bloqueo: string | null;
  /** Texto completo para el título y los lectores de pantalla. */
  resumen: string;
}

export interface ColumnaTablero extends ColumnaCrud {
  fichas: Ficha[];
  coincidencias: number;
}

export interface CambiosPermisos {
  agregar: number[];
  quitar: number[];
}

const VACIO: ReadonlySet<number> = new Set();
const COLOR_ACCION = new Map(COLUMNAS_CRUD.map((c) => [c.accion, c.color]));
const TITULO_ACCION = new Map(COLUMNAS_CRUD.map((c) => [c.accion, c.titulo]));

/**
 * Tablero de permisos de un rol (presentación): el catálogo agrupado por acción (Acceso, CRUD y
 * Exportar) y, a la derecha, la columna del rol. Los permisos se pasan de un lado a otro
 * arrastrando o eligiéndolos y usando los botones; los cambios quedan pendientes (rayados) hasta
 * que la página los guarda en lote. El buscador atenúa lo que no coincide sin esconderlo.
 */
@Component({
  selector: 'app-tablero-permisos',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ArrastreFichas, Icon, NgTemplateOutlet],
  templateUrl: './tablero-permisos.html',
  styleUrls: ['./tablero-permisos.scss', './tablero-ficha.scss'],
  host: { '(keydown.escape)': 'alEscape($event)' },
})
export class TableroPermisos {
  readonly permisos = input<readonly PermisoDelRol[]>([]);
  readonly nombreRol = input('');
  /** Rol ADMINISTRADOR: solo recibe permisos. */
  readonly protegido = input(false);
  /** Sin ASIGNAR_PERMISOS o guardando: solo se mira. */
  readonly soloLectura = input(false);
  /** Permisos que tiene quien edita: no puede otorgar otros (control de escalada). */
  readonly otorgables = input<readonly string[]>([]);

  /** Cambios sin guardar; se vacían cada vez que llega el catálogo (al abrir o tras guardar). */
  private readonly agregar = linkedSignal<readonly PermisoDelRol[], ReadonlySet<number>>({
    source: this.permisos,
    computation: () => VACIO,
  });
  private readonly quitar = linkedSignal<readonly PermisoDelRol[], ReadonlySet<number>>({
    source: this.permisos,
    computation: () => VACIO,
  });
  readonly elegidas = linkedSignal<readonly PermisoDelRol[], ReadonlySet<number>>({
    source: this.permisos,
    computation: () => VACIO,
  });
  readonly ladoElegido = linkedSignal<readonly PermisoDelRol[], ZonaPermisos | null>({
    source: this.permisos,
    computation: () => null,
  });
  private ancla: number | null = null;

  readonly busqueda = signal('');
  /** Último rechazo o confirmación (lectores de pantalla incluidos); se limpia al llegar el catálogo. */
  readonly aviso = linkedSignal<readonly PermisoDelRol[], string | null>({
    source: this.permisos,
    computation: () => null,
  });

  private readonly propios = computed(() => new Set(this.otorgables().map((p) => p.toUpperCase())));

  readonly fichas = computed<Ficha[]>(() => {
    const agregar = this.agregar();
    const quitar = this.quitar();
    return this.permisos().map((permiso) => {
      const clase = clasificarPermiso(permiso.nombre);
      const enRol = agregar.has(permiso.id) || (permiso.asignado && !quitar.has(permiso.id));
      const movida = enRol !== permiso.asignado;
      const ficha = {
        ...permiso,
        ...clase,
        enRol,
        movida,
        usos: permiso.roles + (movida ? (enRol ? 1 : -1) : 0),
        colorAccion: COLOR_ACCION.get(clase.accion) ?? '#334155',
        bloqueo: this.bloqueoDe(permiso, enRol),
      };
      return { ...ficha, resumen: describir(ficha) };
    });
  });

  private readonly porId = computed(() => new Map(this.fichas().map((f) => [f.id, f])));

  /** Ids que coinciden con la búsqueda; null sin búsqueda (nada se atenúa). */
  readonly coinciden = computed<ReadonlySet<number> | null>(() => {
    const termino = this.busqueda().trim();
    if (!termino) {
      return null;
    }
    return new Set(
      this.fichas()
        .filter((f) =>
          contieneTexto([f.nombre, f.descripcion, f.etiqueta, f.nombrePanel, f.panel], termino),
        )
        .map((f) => f.id),
    );
  });

  readonly columnas = computed<ColumnaTablero[]>(() => {
    const coinciden = this.coinciden();
    return COLUMNAS_CRUD.map((columna) => {
      const fichas = this.fichas().filter((f) => f.accion === columna.accion && !f.enRol);
      return {
        ...columna,
        fichas,
        coincidencias: coinciden ? fichas.filter((f) => coinciden.has(f.id)).length : fichas.length,
      };
    }).filter((c) => c.accion !== 'O' || c.fichas.length > 0);
  });

  /** La columna del rol, en el mismo orden de acciones. */
  readonly grupos = computed<ColumnaTablero[]>(() => {
    const coinciden = this.coinciden();
    return COLUMNAS_CRUD.map((columna) => {
      const fichas = this.fichas().filter((f) => f.accion === columna.accion && f.enRol);
      return {
        ...columna,
        fichas,
        coincidencias: coinciden ? fichas.filter((f) => coinciden.has(f.id)).length : fichas.length,
      };
    }).filter((g) => g.fichas.length > 0);
  });

  readonly antes = computed(() => this.permisos().filter((p) => p.asignado).length);
  readonly despues = computed(() => this.fichas().filter((f) => f.enRol).length);
  readonly inactivasEnRol = computed(() => this.fichas().filter((f) => f.enRol && !f.activo).length);
  readonly coincidenciasTotales = computed(() => this.coinciden()?.size ?? 0);

  /** Coincidencias del catálogo que se pueden agregar ya (activas y otorgables). */
  readonly elegiblesBusqueda = computed(() => {
    const coinciden = this.coinciden();
    if (!coinciden) {
      return [];
    }
    return this.fichas().filter((f) => !f.enRol && coinciden.has(f.id) && this.motivoFicha(f, 'rol') === null);
  });

  readonly cambios = computed<CambiosPermisos>(() => ({
    agregar: [...this.agregar()],
    quitar: [...this.quitar()],
  }));
  readonly totalCambios = computed(() => this.agregar().size + this.quitar().size);

  readonly validar = (ids: readonly number[], destino: string) => this.motivo(ids, destino as ZonaPermisos);
  readonly grupo = (id: number): readonly number[] => (this.elegidas().has(id) ? [...this.elegidas()] : [id]);

  private readonly catalogo = viewChild.required<ElementRef<HTMLElement>>('catalogo');
  private readonly columnasCatalogo = viewChildren<ElementRef<HTMLElement>>('columnaCatalogo');
  /** Columnas del catálogo que se ven al menos en un 60 % de su ancho (atajos resaltados). */
  readonly visibles = signal<ReadonlySet<string>>(new Set());

  constructor() {
    // Un IntersectionObserver en vez de escuchar el scroll: Angular solo se entera cuando cambia
    // qué columnas están a la vista, no en cada píxel desplazado.
    effect((alLimpiar) => {
      const raiz = this.catalogo().nativeElement;
      const columnas = this.columnasCatalogo().map((ref) => ref.nativeElement);
      if (typeof IntersectionObserver === 'undefined' || columnas.length === 0) {
        return;
      }
      const vistas = new Set<string>();
      const observador = new IntersectionObserver(
        (entradas) => {
          for (const entrada of entradas) {
            const accion = (entrada.target as HTMLElement).dataset['accion'] ?? '';
            const ancho = entrada.boundingClientRect.width;
            // Solo cuenta el ancho: una columna larga nunca entra entera a lo alto.
            if (ancho > 0 && entrada.intersectionRect.width / ancho >= 0.6) {
              vistas.add(accion);
            } else {
              vistas.delete(accion);
            }
          }
          this.visibles.set(new Set(vistas));
        },
        { root: raiz, threshold: [0, 0.25, 0.5, 0.6, 0.75, 1] },
      );
      for (const columna of columnas) {
        observador.observe(columna);
      }
      alLimpiar(() => observador.disconnect());
    });
  }

  /** Atajo: lleva la columna a la vista (a lo ancho en escritorio; bajo la barra al apilar). */
  irAColumna(accion: string): void {
    const columna = this.columnasCatalogo()
      .map((ref) => ref.nativeElement)
      .find((el) => el.dataset['accion'] === accion);
    if (!columna) {
      return;
    }
    const apilado = !matchMedia('(min-width: 1024px)').matches; // responsive:ok mismo corte lg del SCSS; solo elige cómo desplazar
    const suave = !matchMedia('(prefers-reduced-motion: reduce)').matches;
    columna.scrollIntoView({
      behavior: suave ? 'smooth' : 'auto',
      block: apilado ? 'start' : 'nearest',
      inline: 'start',
    });
  }

  buscar(termino: string): void {
    this.busqueda.set(termino);
  }

  /** Elige todas las coincidencias del catálogo que se pueden agregar. */
  elegirCoincidencias(): void {
    const ids = this.elegiblesBusqueda().map((f) => f.id);
    this.ladoElegido.set('catalogo');
    this.elegidas.set(new Set(ids));
    this.ancla = null;
    this.aviso.set(`${ids.length} ${ids.length === 1 ? 'permiso elegido' : 'permisos elegidos'}.`);
  }

  /**
   * Clic: alterna la ficha en la selección de su lado (elegir en el otro lado empieza de cero).
   * Mayús + clic: elige el tramo desde la última ficha tocada dentro de la misma columna.
   */
  alternar(ficha: Ficha, lado: ZonaPermisos, evento?: Event): void {
    if (this.soloLectura()) {
      return;
    }
    const motivo = this.motivoFicha(ficha, lado === 'catalogo' ? 'rol' : 'catalogo');
    if (motivo && !this.elegidas().has(ficha.id)) {
      this.aviso.set(motivo);
      return;
    }
    const mismoLado = this.ladoElegido() === lado;
    const actual = mismoLado ? this.elegidas() : VACIO;
    const siguiente = new Set(actual);
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

  /** Botones (teclado y pantallas táctiles): pasa la selección al otro lado. */
  moverElegidas(): void {
    const lado = this.ladoElegido();
    if (!lado) {
      return;
    }
    this.aplicar([...this.elegidas()], lado === 'catalogo' ? 'rol' : 'catalogo');
  }

  limpiarSeleccion(): void {
    this.elegidas.set(VACIO);
    this.ladoElegido.set(null);
    this.ancla = null;
  }

  /** Descarta todo lo que no se guardó. */
  descartar(): void {
    this.agregar.set(VACIO);
    this.quitar.set(VACIO);
    this.limpiarSeleccion();
    this.aviso.set('Cambios descartados.');
  }

  alSoltar(evento: SoltarFichas): void {
    if (evento.motivo) {
      this.aviso.set(evento.motivo);
      return;
    }
    this.aplicar(evento.ids, evento.destino as ZonaPermisos);
  }

  alEscape(evento: Event): void {
    // Antes de cerrar el diálogo, Esc limpia la selección y luego la búsqueda.
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

  private aplicar(ids: readonly number[], destino: ZonaPermisos): void {
    const motivo = this.motivo(ids, destino);
    if (motivo) {
      this.aviso.set(motivo);
      return;
    }
    const agregar = new Set(this.agregar());
    const quitar = new Set(this.quitar());
    let movidas = 0;
    for (const id of ids) {
      const ficha = this.porId().get(id);
      if (!ficha || ficha.enRol === (destino === 'rol')) {
        continue;
      }
      movidas++;
      if (destino === 'rol') {
        if (ficha.asignado) quitar.delete(id);
        else agregar.add(id);
      } else if (ficha.asignado) {
        quitar.add(id);
      } else {
        agregar.delete(id);
      }
    }
    this.agregar.set(agregar);
    this.quitar.set(quitar);
    this.limpiarSeleccion();
    const nombre = movidas === 1 ? 'permiso' : 'permisos';
    this.aviso.set(
      destino === 'rol'
        ? `${movidas} ${nombre} al rol (falta guardar).`
        : `${movidas} ${nombre} fuera del rol (falta guardar).`,
    );
  }

  /** Por qué esos permisos no pueden ir a `destino`; null si pueden. */
  private motivo(ids: readonly number[], destino: ZonaPermisos): string | null {
    if (this.soloLectura()) {
      return 'No puede cambiar los permisos de este rol.';
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

  /** Por qué la ficha no puede ir a `destino`; null si puede o si ya está ahí. */
  private motivoFicha(ficha: Ficha, destino: ZonaPermisos): string | null {
    return ficha.enRol === (destino === 'rol') ? null : ficha.bloqueo;
  }

  /** Por qué un permiso no puede pasar al otro lado del tablero (null = puede). */
  private bloqueoDe(permiso: PermisoDelRol, enRol: boolean): string | null {
    // `soloLectura` (p. ej. mientras se guarda) se valida en `motivo` y `alternar`: así guardar
    // no repinta todas las fichas.
    if (enRol) {
      return this.protegido() ? 'Al rol ADMINISTRADOR no se le quitan permisos: solo se le agregan.' : null;
    }
    if (permiso.asignado) {
      return null; // se quitó sin guardar: volver al rol siempre se puede
    }
    if (!permiso.activo) {
      return `«${permiso.nombre}» está inactivo: reactívelo en Permisos antes de asignarlo.`;
    }
    if (!this.propios().has(permiso.nombre.toUpperCase())) {
      return `No puede otorgar «${permiso.nombre}»: usted no tiene ese permiso.`;
    }
    return null;
  }

  /** Ids entre la ficha ancla y `ficha` en la misma columna y lado; null si no aplica. */
  private tramo(ficha: Ficha, lado: ZonaPermisos): number[] | null {
    const lista = (lado === 'catalogo' ? this.columnas() : this.grupos()).find(
      (c) => c.accion === ficha.accion,
    )?.fichas;
    const desde = lista?.findIndex((f) => f.id === this.ancla) ?? -1;
    const hasta = lista?.findIndex((f) => f.id === ficha.id) ?? -1;
    if (!lista || desde < 0 || hasta < 0) {
      return null;
    }
    const [a, b] = desde < hasta ? [desde, hasta] : [hasta, desde];
    return lista
      .slice(a, b + 1)
      .filter((f) => this.motivoFicha(f, lado === 'catalogo' ? 'rol' : 'catalogo') === null)
      .map((f) => f.id);
  }

}

/** Texto completo de la ficha para el título y los lectores de pantalla. */
function describir(ficha: Omit<Ficha, 'resumen'>): string {
  const partes = [
    ficha.nombre,
    TITULO_ACCION.get(ficha.accion),
    ficha.nombrePanel || null,
    ficha.descripcion || null,
    ficha.activo ? `en ${ficha.usos} ${ficha.usos === 1 ? 'rol' : 'roles'}` : 'inactivo',
    ficha.movida ? 'cambio sin guardar' : null,
    ficha.bloqueo,
  ];
  return partes.filter(Boolean).join(' · ');
}
