import { WritableSignal, computed, signal } from '@angular/core';
import { ChipFiltro, fechaChip } from './filtros-activos';

/** Definición de un filtro del panel: valor vacío y cómo se muestra su chip. */
export interface DefFiltro<T> {
  vacio: T;
  etiqueta: string;
  icono: string;
  /** Texto legible del valor aplicado. */
  texto: (valor: T) => string;
}

/** Filtro por un elemento de catálogo: el chip muestra su nombre (o «#id» si aún no cargó). */
export function filtroCatalogo<T extends { id: number }>(
  etiqueta: string,
  icono: string,
  lista: () => readonly T[],
  nombre: (item: T) => string,
): DefFiltro<number | null> {
  return {
    vacio: null,
    etiqueta,
    icono,
    texto: (id) => {
      const item = lista().find((x) => x.id === id);
      return item ? nombre(item) : `#${id}`;
    },
  };
}

/** Filtro de fecha («Desde» / «Hasta») en formato ISO corto. */
export function filtroFecha(etiqueta: string): DefFiltro<string> {
  return { vacio: '', etiqueta, icono: 'calendario', texto: fechaChip };
}

/**
 * Estado de los filtros de un panel lateral: una señal por filtro, los chips
 * de los aplicados (en el orden de la definición), cuántos hay activos y si
 * el panel está abierto. Quitar o limpiar solo cambia los valores: recargar
 * los datos queda a cargo de la página.
 */
export class EstadoFiltros<V extends Record<string, unknown>> {
  readonly abierto = signal(false);
  readonly valores: { readonly [K in keyof V]: WritableSignal<V[K]> };

  readonly chips = computed<ChipFiltro[]>(() =>
    this.claves.flatMap((clave) => {
      const def = this.defs[clave];
      const valor = this.valores[clave]();
      return valor === def.vacio
        ? []
        : [
            {
              clave: String(clave),
              etiqueta: def.etiqueta,
              valor: def.texto(valor),
              icono: def.icono,
            },
          ];
    }),
  );
  readonly activos = computed(() => this.chips().length);

  private readonly claves: (keyof V)[];

  constructor(private readonly defs: { readonly [K in keyof V]: DefFiltro<V[K]> }) {
    this.claves = Object.keys(defs) as (keyof V)[];
    this.valores = Object.fromEntries(
      this.claves.map((clave) => [clave, signal(defs[clave].vacio)]),
    ) as unknown as { [K in keyof V]: WritableSignal<V[K]> };
  }

  alternar(): void {
    this.abierto.update((abierto) => !abierto);
  }

  /** Vuelve un filtro (por la clave de su chip) a su valor vacío. */
  quitar(clave: string): void {
    if (clave in this.defs) {
      this.valores[clave as keyof V].set(this.defs[clave as keyof V].vacio);
    }
  }

  limpiar(): void {
    for (const clave of this.claves) {
      this.valores[clave].set(this.defs[clave].vacio);
    }
  }
}
