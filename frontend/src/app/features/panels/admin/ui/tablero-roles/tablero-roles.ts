import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, linkedSignal, signal } from '@angular/core';
import { contieneTexto } from '../../../../../core/utils/texto.util';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { ArrastreFichas, SoltarFichas } from '../tablero-fichas/arrastre-fichas.directive';
import { COLUMNAS_CRUD, FichaPermiso, clasificarPermiso } from '../tablero-permisos/ficha-permiso';

/**
 * Lado del tablero: el catálogo (roles que no están en el destino) o la columna del destino (el
 * usuario en «Roles del usuario»; los roles con el permiso en «Roles con este permiso»).
 */
export type ZonaRoles = 'catalogo' | 'usuario';

/** Rol que muestra el tablero. Las reglas por rol las decide la página que lo usa. */
export interface RolTablero {
  id: number;
  nombre: string;
  descripcion: string | null;
  activo: boolean;
  /** Ya está en el destino (guardado). */
  asignado: boolean;
  permisos: number;
  usuarios: number;
  /** Permisos PANEL_* activos del rol. */
  paneles: string[];
  /** false: quien edita no puede ponerlo en el destino porque trae permisos que no tiene. */
  otorgable?: boolean;
  /** Por qué no puede ir al destino (manda sobre `otorgable`); null o ausente = puede. */
  motivoDar?: string | null;
  /** Por qué no puede salir del destino si ya estaba (p. ej. ADMINISTRADOR). */
  motivoQuitar?: string | null;
}

/** Textos que cambian según lo que represente el destino. */
export interface TextosTableroRoles {
  /** Etiqueta de la cabecera del destino («Usuario», «Con el permiso»). */
  destino: string;
  dar: string;
  quitar: string;
  vacioDestino: string;
  vacioCatalogo: string;
  ariaCatalogo: string;
  ariaDestino: string;
  /** Tras mover: «2 roles al usuario», «1 rol recibe el permiso». */
  movidosAlDestino: [string, string];
  movidosAlCatalogo: [string, string];
}

export const TEXTOS_ROLES_USUARIO: TextosTableroRoles = {
  destino: 'Usuario',
  dar: 'Dar al usuario',
  quitar: 'Quitar al usuario',
  vacioDestino: 'Suelta aquí los roles de este usuario.',
  vacioCatalogo: 'El usuario ya tiene todos los roles.',
  ariaCatalogo: 'Roles que el usuario no tiene',
  ariaDestino: 'Roles del usuario',
  movidosAlDestino: ['rol al usuario', 'roles al usuario'],
  movidosAlCatalogo: ['rol fuera del usuario', 'roles fuera del usuario'],
};

/** Cabecera opcional: el permiso cuyo reparto se edita, con a cuántos usuarios llega. */
export interface CabeceraPermiso {
  nombre: string;
  descripcion: string | null;
  activo: boolean;
  /** Usuarios distintos que lo tienen hoy por sus roles activos. */
  usuarios: number;
}

/** Panel al que da entrada un rol (pestaña de color de la ficha y filtro). */
export interface PanelRol {
  clave: string;
  codigo: string;
  nombre: string;
  color: string;
}

/** Rol listo para dibujar: sus paneles y dónde queda con los cambios sin guardar. */
export interface FichaRol extends RolTablero {
  enUsuario: boolean;
  movida: boolean;
  /** Usuarios que lo tendrán al guardar. */
  usos: number;
  panelesRol: PanelRol[];
  /** Los dos primeros paneles van en la ficha; el resto se cuenta («+2»). */
  visibles: PanelRol[];
  extra: number;
  /** Por qué no puede pasar al otro lado (null = puede). */
  bloqueo: string | null;
  /** Texto completo para el título y los lectores de pantalla. */
  resumen: string;
}

export interface FiltroPanel extends PanelRol {
  /** Roles del catálogo con ese panel que también cumplen la búsqueda. */
  cantidad: number;
}

export interface CambiosRoles {
  agregar: number[];
  quitar: number[];
}

const VACIO: ReadonlySet<number> = new Set();
/** Orden de los filtros: los paneles con módulos en Administración primero, como en el menú. */
const ORDEN_PANELES = ['ADM', 'BIT', 'PICK', 'SIM', 'STA', 'MAL'];

function panelDe(clave: string): PanelRol {
  const clase = clasificarPermiso(clave);
  return {
    clave,
    codigo: clase.panel || clave.replace(/^PANEL_/, '').slice(0, 3),
    nombre: clase.nombrePanel || clase.etiqueta,
    color: clase.colorPanel ?? '#64748b',
  };
}

function ordenPanel(panel: PanelRol): number {
  const i = ORDEN_PANELES.indexOf(panel.codigo);
  return i < 0 ? ORDEN_PANELES.length : i;
}

/**
 * Tablero de roles (presentación) que usan «Roles del usuario» y «Roles con este permiso»: el
 * catálogo de roles en una rejilla de fichas con el diseño de Titan y, a la derecha, la columna del
 * destino (el usuario, o los roles que tienen el permiso). Textos, reglas por rol y la cabecera del
 * permiso los decide la página. Los roles se pasan de un lado a
 * otro arrastrando o eligiéndolos y usando los botones; los cambios quedan pendientes (rayados)
 * hasta que la página los guarda en lote. El buscador y los filtros por panel atenúan lo que no
 * coincide sin esconderlo.
 */
@Component({
  selector: 'app-tablero-roles',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ArrastreFichas, Icon, NgTemplateOutlet],
  templateUrl: './tablero-roles.html',
  styleUrls: [
    '../tablero-permisos/tablero-permisos.scss',
    '../tablero-permisos/tablero-ficha.scss',
    './tablero-roles.scss',
  ],
  host: { '(keydown.escape)': 'alEscape($event)' },
})
export class TableroRoles {
  readonly roles = input<readonly RolTablero[]>([]);
  /** Nombre en la cabecera del destino (el usuario, o el permiso). */
  readonly nombreDestino = input('');
  readonly textos = input<TextosTableroRoles>(TEXTOS_ROLES_USUARIO);
  /** Un rol inactivo puede ir al destino (al darle un permiso sí; a un usuario no). */
  readonly permitirInactivos = input(false);
  /** Ficha del permiso arriba del tablero («Roles con este permiso»). */
  readonly cabecera = input<CabeceraPermiso | null>(null);
  /** Motivo por el que quien edita no puede cambiar nada (todo de solo lectura). */
  readonly bloqueo = input<string | null>(null);
  /** Mientras se guarda: no se mueve nada. */
  readonly guardando = input(false);

  /** La cabecera dibujada como ficha Titan (acción, panel y etiqueta del permiso). */
  readonly fichaCabecera = computed<(FichaPermiso & { colorAccion: string }) | null>(() => {
    const cabecera = this.cabecera();
    if (!cabecera) {
      return null;
    }
    const clase = clasificarPermiso(cabecera.nombre);
    return { ...clase, colorAccion: COLUMNAS_CRUD.find((c) => c.accion === clase.accion)?.color ?? '#334155' };
  });

  /** Cambios sin guardar; se vacían cada vez que llega el catálogo (al abrir o tras guardar). */
  private readonly agregar = linkedSignal<readonly RolTablero[], ReadonlySet<number>>({
    source: this.roles,
    computation: () => VACIO,
  });
  private readonly quitar = linkedSignal<readonly RolTablero[], ReadonlySet<number>>({
    source: this.roles,
    computation: () => VACIO,
  });
  readonly elegidas = linkedSignal<readonly RolTablero[], ReadonlySet<number>>({
    source: this.roles,
    computation: () => VACIO,
  });
  readonly ladoElegido = linkedSignal<readonly RolTablero[], ZonaRoles | null>({
    source: this.roles,
    computation: () => null,
  });
  private ancla: number | null = null;

  readonly busqueda = signal('');
  /** Clave del panel filtrado (PANEL_PICKING…); null = todos. */
  readonly panelFiltro = signal<string | null>(null);
  /** Último rechazo o confirmación (lectores de pantalla incluidos); se limpia al llegar el catálogo. */
  readonly aviso = linkedSignal<readonly RolTablero[], string | null>({
    source: this.roles,
    computation: () => null,
  });

  readonly fichas = computed<FichaRol[]>(() => {
    const agregar = this.agregar();
    const quitar = this.quitar();
    return this.roles().map((rol) => {
      const enUsuario = agregar.has(rol.id) || (rol.asignado && !quitar.has(rol.id));
      const movida = enUsuario !== rol.asignado;
      const panelesRol = rol.paneles
        .map(panelDe)
        .sort((a, b) => ordenPanel(a) - ordenPanel(b) || a.nombre.localeCompare(b.nombre));
      const ficha = {
        ...rol,
        enUsuario,
        movida,
        usos: rol.usuarios + (movida ? (enUsuario ? 1 : -1) : 0),
        panelesRol,
        visibles: panelesRol.slice(0, 2),
        extra: Math.max(0, panelesRol.length - 2),
        bloqueo: this.bloqueoDe(rol, enUsuario),
      };
      return { ...ficha, resumen: describir(ficha) };
    });
  });

  private readonly porId = computed(() => new Map(this.fichas().map((f) => [f.id, f])));

  /** Ids que cumplen la búsqueda y el filtro de panel; null sin ninguno (nada se atenúa). */
  readonly coinciden = computed<ReadonlySet<number> | null>(() => {
    const termino = this.busqueda().trim();
    const panel = this.panelFiltro();
    if (!termino && !panel) {
      return null;
    }
    return new Set(this.fichas().filter((f) => this.cumple(f, termino, panel)).map((f) => f.id));
  });

  readonly catalogo = computed(() => this.fichas().filter((f) => !f.enUsuario));
  readonly delUsuario = computed(() => this.fichas().filter((f) => f.enUsuario));

  /** Filtros: un botón por panel que abre algún rol, con cuántos roles libres lo abren. */
  readonly filtros = computed<FiltroPanel[]>(() => {
    const termino = this.busqueda().trim();
    const paneles = new Map<string, PanelRol>();
    for (const ficha of this.fichas()) {
      for (const panel of ficha.panelesRol) {
        paneles.set(panel.clave, panel);
      }
    }
    return [...paneles.values()]
      .sort((a, b) => ordenPanel(a) - ordenPanel(b) || a.nombre.localeCompare(b.nombre))
      .map((panel) => ({
        ...panel,
        cantidad: this.catalogo().filter((f) => this.cumple(f, termino, panel.clave)).length,
      }));
  });

  readonly coincidenciasCatalogo = computed(() => {
    const coinciden = this.coinciden();
    return coinciden ? this.catalogo().filter((f) => coinciden.has(f.id)).length : 0;
  });
  readonly elegiblesBusqueda = computed(() => {
    const coinciden = this.coinciden();
    return coinciden ? this.catalogo().filter((f) => coinciden.has(f.id) && f.bloqueo === null) : [];
  });

  readonly antes = computed(() => this.roles().filter((r) => r.asignado).length);
  readonly despues = computed(() => this.delUsuario().length);
  readonly inactivosDelUsuario = computed(() => this.delUsuario().filter((f) => !f.activo).length);

  readonly cambios = computed<CambiosRoles>(() => ({ agregar: [...this.agregar()], quitar: [...this.quitar()] }));
  readonly totalCambios = computed(() => this.agregar().size + this.quitar().size);

  readonly validar = (ids: readonly number[], destino: string) => this.motivo(ids, destino as ZonaRoles);
  readonly grupo = (id: number): readonly number[] => (this.elegidas().has(id) ? [...this.elegidas()] : [id]);

  buscar(termino: string): void {
    this.busqueda.set(termino);
  }

  /** Un clic filtra por ese panel; otro clic en el mismo quita el filtro. */
  filtrarPanel(clave: string): void {
    this.panelFiltro.update((actual) => (actual === clave ? null : clave));
  }

  /** Elige todas las coincidencias del catálogo que se pueden asignar. */
  elegirCoincidencias(): void {
    const ids = this.elegiblesBusqueda().map((f) => f.id);
    this.ladoElegido.set(ids.length ? 'catalogo' : null);
    this.elegidas.set(new Set(ids));
    this.ancla = null;
    this.aviso.set(`${ids.length} ${ids.length === 1 ? 'rol elegido' : 'roles elegidos'}.`);
  }

  /**
   * Clic: alterna la ficha en la selección de su lado (elegir en el otro lado empieza de cero).
   * Mayús + clic: elige el tramo desde la última ficha tocada en el mismo lado.
   */
  alternar(ficha: FichaRol, lado: ZonaRoles, evento?: Event): void {
    if (this.soloLectura()) {
      this.aviso.set(this.bloqueo());
      return;
    }
    if (ficha.bloqueo && !this.elegidas().has(ficha.id)) {
      this.aviso.set(ficha.bloqueo);
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

  /** Botones (teclado y pantallas táctiles): pasa la selección al otro lado. */
  moverElegidas(): void {
    const lado = this.ladoElegido();
    if (lado) {
      this.aplicar([...this.elegidas()], lado === 'catalogo' ? 'usuario' : 'catalogo');
    }
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
    this.aplicar(evento.ids, evento.destino as ZonaRoles);
  }

  alEscape(evento: Event): void {
    // Antes de cerrar el diálogo, Esc limpia la selección, luego la búsqueda y el filtro.
    if (this.elegidas().size) {
      this.limpiarSeleccion();
    } else if (this.busqueda() || this.panelFiltro()) {
      this.busqueda.set('');
      this.panelFiltro.set(null);
    } else {
      return;
    }
    evento.stopPropagation();
    evento.preventDefault();
  }

  private soloLectura(): boolean {
    return this.guardando() || this.bloqueo() !== null;
  }

  private cumple(ficha: FichaRol, termino: string, panel: string | null): boolean {
    const nombresPanel = ficha.panelesRol.flatMap((p) => [p.nombre, p.codigo]);
    return (
      (!panel || ficha.paneles.includes(panel)) &&
      contieneTexto([ficha.nombre, ficha.descripcion, ...nombresPanel], termino)
    );
  }

  private aplicar(ids: readonly number[], destino: ZonaRoles): void {
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
      if (!ficha || ficha.enUsuario === (destino === 'usuario')) {
        continue;
      }
      movidas++;
      if (destino === 'usuario') {
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
    const [uno, varios] = destino === 'usuario' ? this.textos().movidosAlDestino : this.textos().movidosAlCatalogo;
    this.aviso.set(`${movidas} ${movidas === 1 ? uno : varios} (falta guardar).`);
  }

  /** Por qué esos roles no pueden ir a `destino`; null si pueden. */
  private motivo(ids: readonly number[], destino: ZonaRoles): string | null {
    if (this.soloLectura()) {
      return this.bloqueo() ?? 'Espere a que terminen de guardarse los cambios.';
    }
    for (const id of ids) {
      const ficha = this.porId().get(id);
      if (ficha && ficha.enUsuario !== (destino === 'usuario') && ficha.bloqueo) {
        return ficha.bloqueo;
      }
    }
    return null;
  }

  /** Por qué un rol no puede pasar al otro lado (null = puede). */
  private bloqueoDe(rol: RolTablero, enUsuario: boolean): string | null {
    const bloqueo = this.bloqueo();
    if (bloqueo) {
      return bloqueo;
    }
    if (enUsuario) {
      // Sacar uno que ya estaba puede estar prohibido (ADMINISTRADOR); uno recién puesto, no.
      return rol.asignado ? (rol.motivoQuitar ?? null) : null;
    }
    if (rol.asignado) {
      return null; // se sacó sin guardar: volver a ponerlo siempre se puede
    }
    if (!rol.activo && !this.permitirInactivos()) {
      return `«${rol.nombre}» está inactivo: reactívelo en Roles antes de asignarlo.`;
    }
    if (rol.motivoDar) {
      return rol.motivoDar;
    }
    if (rol.otorgable === false) {
      return `No puede otorgar «${rol.nombre}»: trae permisos que usted no tiene.`;
    }
    return null;
  }

  /** Ids entre la ficha ancla y `ficha` en el mismo lado; null si no aplica. */
  private tramo(ficha: FichaRol, lado: ZonaRoles): number[] | null {
    const lista = lado === 'catalogo' ? this.catalogo() : this.delUsuario();
    const desde = lista.findIndex((f) => f.id === this.ancla);
    const hasta = lista.findIndex((f) => f.id === ficha.id);
    if (desde < 0 || hasta < 0) {
      return null;
    }
    const [a, b] = desde < hasta ? [desde, hasta] : [hasta, desde];
    return lista
      .slice(a, b + 1)
      .filter((f) => f.bloqueo === null)
      .map((f) => f.id);
  }
}

/** Texto completo de la ficha para el título y los lectores de pantalla. */
function describir(ficha: Omit<FichaRol, 'resumen'>): string {
  const partes = [
    ficha.nombre,
    ficha.descripcion || null,
    ficha.panelesRol.length ? `entra a ${ficha.panelesRol.map((p) => p.nombre).join(', ')}` : 'sin paneles',
    `${ficha.permisos} ${ficha.permisos === 1 ? 'permiso' : 'permisos'}`,
    ficha.activo ? `${ficha.usos} ${ficha.usos === 1 ? 'usuario' : 'usuarios'}` : 'inactivo',
    ficha.movida ? 'cambio sin guardar' : null,
    ficha.bloqueo,
  ];
  return partes.filter(Boolean).join(' · ');
}
