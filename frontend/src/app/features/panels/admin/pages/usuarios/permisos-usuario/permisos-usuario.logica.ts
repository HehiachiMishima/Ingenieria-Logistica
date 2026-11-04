import { contieneTexto } from '../../../../../../core/utils/texto.util';
import { Permiso } from '../../../data/permiso.model';

/**
 * Lógica pura de los permisos individuales de un usuario: cómo se combina lo
 * que concede su rol con los overrides (grant/deny) que tiene asignados.
 */

/** De dónde sale el acceso del usuario a un permiso. */
export type OrigenPermiso = 'concedido' | 'denegado' | 'heredado' | 'sin';

export interface FilaPermiso {
  permiso: Permiso;
  origen: OrigenPermiso;
}

/** El override manda (deny-wins / grant); sin override, se hereda del rol. */
export function origenPermiso(
  override: boolean | undefined,
  otorgadoPorRol: boolean,
): OrigenPermiso {
  if (override === true) {
    return 'concedido';
  }
  if (override === false) {
    return 'denegado';
  }
  return otorgadoPorRol ? 'heredado' : 'sin';
}

/** Permisos del catálogo que coinciden con la búsqueda, con su origen ya resuelto. */
export function filasPermisos(
  catalogo: readonly Permiso[],
  termino: string,
  overrides: ReadonlyMap<number, boolean>,
  otorgaRol: ReadonlySet<number>,
): FilaPermiso[] {
  return catalogo
    .filter((permiso) => contieneTexto([permiso.nombre, permiso.descripcion], termino))
    .map((permiso) => ({
      permiso,
      origen: origenPermiso(overrides.get(permiso.id), otorgaRol.has(permiso.id)),
    }));
}

/** Totales del resumen: permisos efectivos y overrides concedidos / denegados. */
export function resumenPermisos(
  catalogo: readonly Permiso[],
  overrides: ReadonlyMap<number, boolean>,
  otorgaRol: ReadonlySet<number>,
): { efectivos: number; concedidos: number; denegados: number } {
  const valores = [...overrides.values()];
  return {
    efectivos: catalogo.filter((p) => overrides.get(p.id) ?? otorgaRol.has(p.id)).length,
    concedidos: valores.filter((otorgado) => otorgado).length,
    denegados: valores.filter((otorgado) => !otorgado).length,
  };
}
