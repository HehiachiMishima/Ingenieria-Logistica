import { Signal, computed, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { valorTexto } from '../../../../core/utils/evento.util';
import { RecursoRemoto } from '../../../../core/utils/listado-remoto';
import { PaginacionLocal } from '../../../../core/utils/paginacion-local';
import { contieneTexto } from '../../../../core/utils/texto.util';
import { ChipFiltro } from '../../../../shared/ui/panel-filtros/filtros-activos';
import {
  FiltroEstado,
  OPCIONES_ESTADO,
  chipEstado,
  chipPeriodo,
  cumpleEstado,
  enPeriodo,
} from '../../../../shared/ui/panel-filtros/filtro-registro';
import { RangoFechas } from '../../../../shared/ui/panel-filtros/rango-rapido';

/** Campos comunes de usuarios, roles y permisos sobre los que filtra el panel. */
export interface Registro {
  id: number;
  activo: boolean;
  creadoEn: string;
}

/** Filtro propio de una página (p. ej. el rol en Usuarios), con su chip. */
export interface FiltroExtra<T> {
  /** Clave del chip: `quitarFiltro(clave)` lo limpia. */
  clave: string;
  chip: () => ChipFiltro | null;
  /** Condición vigente, o null si el filtro no se aplica. */
  condicion: () => ((item: T) => boolean) | null;
  limpiar: () => void;
}

export interface OpcionesListado<T> {
  fuente: () => Observable<T[]>;
  /** Campos en los que busca el cuadro de búsqueda (sin distinguir tildes). */
  campos: (item: T) => ReadonlyArray<string | null | undefined>;
  /** Se ejecuta con cada lista recibida (solo la de la petición vigente). */
  alCargar?: (lista: T[]) => void;
  /** Se muestra entre el chip de estado y el de periodo. */
  extra?: FiltroExtra<T>;
}

/**
 * Listado de Administración (usuarios, roles, permisos): el backend devuelve
 * el catálogo completo y aquí se busca, filtra (estado, periodo de creación y
 * un filtro propio opcional) y pagina en el navegador. La carga usa
 * RecursoRemoto: «Actualizar» cancela la petición anterior y un error
 * conserva las filas ya visibles.
 *
 * Debe crearse en un contexto de inyección (campo o constructor de componente).
 */
export class ListadoRegistros<T extends Registro> {
  private readonly recurso: RecursoRemoto<T[]>;
  private readonly extra: FiltroExtra<T> | undefined;
  private readonly campos: OpcionesListado<T>['campos'];

  readonly cargando: Signal<boolean>;
  readonly error: Signal<string | null>;
  readonly items = computed(() => this.recurso.valor() ?? []);

  readonly busqueda = signal('');
  readonly estados = OPCIONES_ESTADO;
  readonly filtroEstado = signal<FiltroEstado>('todos');
  readonly filtroDesde = signal('');
  readonly filtroHasta = signal('');
  readonly filtrosAbierto = signal(false);

  private readonly coincidenBusqueda = computed(() => {
    const termino = this.busqueda();
    return this.items().filter((item) => contieneTexto(this.campos(item), termino));
  });

  /** Búsqueda + filtros del panel. */
  readonly filtrados = computed(() => {
    const estado = this.filtroEstado();
    const desde = this.filtroDesde();
    const hasta = this.filtroHasta();
    const condicion = this.extra?.condicion() ?? null;
    return this.coincidenBusqueda().filter(
      (item) =>
        cumpleEstado(estado, item.activo) &&
        (condicion === null || condicion(item)) &&
        enPeriodo(item.creadoEn, desde, hasta),
    );
  });

  readonly chips = computed<ChipFiltro[]>(() =>
    [
      chipEstado(this.filtroEstado()),
      this.extra?.chip() ?? null,
      chipPeriodo(this.filtroDesde(), this.filtroHasta()),
    ].filter((chip): chip is ChipFiltro => chip !== null),
  );

  readonly filtrosActivos = computed(() => this.chips().length);

  private readonly paginacion = new PaginacionLocal(this.filtrados);
  readonly pagina = this.paginacion.pagina;
  readonly porPagina = this.paginacion.porPagina;
  readonly paginaActual = this.paginacion.paginaActual;
  readonly paginados = this.paginacion.paginados;

  constructor(opciones: OpcionesListado<T>) {
    const { fuente, alCargar } = opciones;
    this.campos = opciones.campos;
    this.extra = opciones.extra;
    this.recurso = new RecursoRemoto(() =>
      alCargar ? fuente().pipe(tap((lista) => alCargar(lista))) : fuente(),
    );
    this.cargando = this.recurso.cargando;
    this.error = this.recurso.error;
  }

  recargar(): void {
    this.recurso.recargar();
  }

  /** Sustituye un elemento actualizado (p. ej. al activarlo) sin volver a pedir la lista. */
  reemplazar(actualizado: T): void {
    this.recurso.valor.update(
      (lista) => lista?.map((item) => (item.id === actualizado.id ? actualizado : item)) ?? null,
    );
  }

  /** Quita un elemento eliminado sin volver a pedir la lista. */
  quitar(id: number): void {
    this.recurso.valor.update((lista) => lista?.filter((item) => item.id !== id) ?? null);
  }

  buscar(valor: string): void {
    this.busqueda.set(valor);
    this.pagina.set(1);
  }

  cambiarPorPagina(valor: number): void {
    this.paginacion.cambiarPorPagina(valor);
  }

  alternarFiltros(): void {
    this.filtrosAbierto.update((abierto) => !abierto);
  }

  setEstado(estado: FiltroEstado): void {
    this.filtroEstado.set(estado);
    this.pagina.set(1);
  }

  setRango(rango: RangoFechas): void {
    this.filtroDesde.set(rango.desde);
    this.filtroHasta.set(rango.hasta);
    this.pagina.set(1);
  }

  setFiltroDesde(evento: Event): void {
    this.filtroDesde.set(valorTexto(evento));
    this.pagina.set(1);
  }

  setFiltroHasta(evento: Event): void {
    this.filtroHasta.set(valorTexto(evento));
    this.pagina.set(1);
  }

  /** Vuelve a la primera página tras cambiar un filtro propio de la página. */
  irAPrimeraPagina(): void {
    this.pagina.set(1);
  }

  quitarFiltro(clave: string): void {
    if (clave === 'estado') {
      this.filtroEstado.set('todos');
    }
    if (clave === 'periodo') {
      this.filtroDesde.set('');
      this.filtroHasta.set('');
    }
    if (this.extra && clave === this.extra.clave) {
      this.extra.limpiar();
    }
    this.pagina.set(1);
  }

  limpiarFiltros(): void {
    this.filtroEstado.set('todos');
    this.extra?.limpiar();
    this.filtroDesde.set('');
    this.filtroHasta.set('');
    this.pagina.set(1);
  }
}
