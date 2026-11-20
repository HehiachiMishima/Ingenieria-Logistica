import { COLOR_PANEL, NOMBRE_PANEL } from '../paneles-admin';

/** Columna del tablero: qué permite hacer el permiso (Acceso + CRUD + Exportar). */
export type Accion = 'P' | 'C' | 'R' | 'U' | 'D' | 'E' | 'O';

export interface ColumnaCrud {
  accion: Accion;
  titulo: string;
  /** Color del bloque de la acción (texto blanco: ≥ 4,5:1 en los dos temas). */
  color: string;
}

export const COLUMNAS_CRUD: readonly ColumnaCrud[] = [
  { accion: 'P', titulo: 'Acceso', color: '#5b21b6' },
  { accion: 'C', titulo: 'Crear', color: '#047857' },
  { accion: 'R', titulo: 'Leer', color: '#1d4ed8' },
  { accion: 'U', titulo: 'Actualizar', color: '#c2410c' },
  { accion: 'D', titulo: 'Eliminar', color: '#b91c1c' },
  { accion: 'E', titulo: 'Exportar', color: '#475569' },
  { accion: 'O', titulo: 'Otros', color: '#334155' },
];

/** Cómo se dibuja un permiso en su ficha. */
export interface FichaPermiso {
  accion: Accion;
  /** Código corto del panel (pestaña de color); vacío si no pertenece a uno conocido. */
  panel: string;
  colorPanel: string | null;
  nombrePanel: string;
  /** Lo que el permiso abre o maneja, en mayúsculas y con tildes («ÓRDENES»). */
  etiqueta: string;
}

/** Verbo inicial del nombre → columna. ASIGNAR y CONFIGURAR cambian algo: van con Actualizar. */
const VERBOS: readonly [string, Accion][] = [
  ['PANEL', 'P'],
  ['CREAR', 'C'],
  ['VER', 'R'],
  ['MODIFICAR', 'U'],
  ['ASIGNAR', 'U'],
  ['CONFIGURAR', 'U'],
  ['ELIMINAR', 'D'],
  ['EXPORTAR', 'E'],
];
/** Verbos que no se leen en la columna y por eso quedan en la etiqueta («ASIGNAR ROLES»). */
const VERBOS_EN_ETIQUETA = new Set(['ASIGNAR', 'CONFIGURAR']);

interface PanelConocido {
  clave: string;
  codigo: string;
  /** Palabras del nombre que lo identifican (se quitan de la etiqueta). */
  palabras: readonly string[];
  /** Objetos que pertenecen al panel aunque su nombre no lo diga. */
  objetos?: readonly string[];
}

/** El orden importa: Santa Anita antes que Simulación (comparten «SIMULACION»). */
const PANELES: readonly PanelConocido[] = [
  { clave: 'PANEL_SIMULACION_SANTA', codigo: 'STA', palabras: ['SIMULACION', 'SANTA'] },
  { clave: 'PANEL_SIMULACION', codigo: 'SIM', palabras: ['SIMULACION'] },
  { clave: 'PANEL_PICKING', codigo: 'PICK', palabras: ['PICKING'] },
  { clave: 'PANEL_MALLA', codigo: 'MAL', palabras: ['MALLA'] },
  {
    clave: 'PANEL_BITACORA',
    codigo: 'BIT',
    palabras: ['BITACORA'],
    objetos: ['INCIDENCIAS', 'CONTROL', 'DASHBOARD'],
  },
  {
    clave: 'PANEL_ADMIN',
    codigo: 'ADM',
    palabras: ['ADMIN'],
    objetos: ['USUARIOS', 'ROLES', 'PERMISOS', 'ACTIVIDAD', 'AUDITORIA', 'MONITOREO'],
  },
];

/** Paneles sin módulos en Administración: pestaña gris con sus tres primeras letras. */
const COLOR_SIN_PANEL = '#64748b';

const TILDES: Record<string, string> = {
  ORDENES: 'ÓRDENES',
  CONFIGURACION: 'CONFIGURACIÓN',
  SIMULACION: 'SIMULACIÓN',
  BITACORA: 'BITÁCORA',
  ANALISIS: 'ANÁLISIS',
  AUTOMATIZACION: 'AUTOMATIZACIÓN',
  LOGISTICA: 'LOGÍSTICA',
  SINCRONIZACION: 'SINCRONIZACIÓN',
  AUDITORIA: 'AUDITORÍA',
  ADMIN: 'ADMINISTRACIÓN',
};

const conTildes = (palabras: readonly string[]) => palabras.map((p) => TILDES[p] ?? p).join(' ');

/**
 * Clasifica un permiso por su nombre: «VER_ORDENES_PICKING» → Leer · PICK · ÓRDENES. Los nombres
 * que no siguen la convención (VERBO_OBJETO) van a «Otros» con el nombre completo como etiqueta.
 */
export function clasificarPermiso(nombre: string): FichaPermiso {
  const palabras = nombre.toUpperCase().split('_').filter(Boolean);
  const verbo = VERBOS.find(([v]) => v === palabras[0]);
  // «DASHBOARD_ADMIN» se lee como «VER_DASHBOARD_ADMIN».
  const accion: Accion = verbo?.[1] ?? (palabras[0] === 'DASHBOARD' ? 'R' : 'O');
  const resto = verbo ? palabras.slice(1) : palabras;

  const panel =
    PANELES.find((p) => p.palabras.every((palabra) => resto.includes(palabra))) ??
    PANELES.find((p) => p.objetos?.some((objeto) => resto.includes(objeto)));
  const objeto = panel ? resto.filter((palabra) => !panel.palabras.includes(palabra)) : resto;
  const conVerbo = verbo && VERBOS_EN_ETIQUETA.has(verbo[0]) ? [verbo[0], ...objeto] : objeto;

  if (panel) {
    const nombrePanel = NOMBRE_PANEL[panel.clave] ?? panel.codigo;
    return {
      accion,
      panel: panel.codigo,
      colorPanel: COLOR_PANEL[panel.clave] ?? null,
      nombrePanel,
      etiqueta: conVerbo.length ? conTildes(conVerbo) : nombrePanel.toUpperCase(),
    };
  }
  if (accion === 'P' && resto.length) {
    // Panel propio sin módulos aquí (Análisis, Logística…): la pestaña es su abreviatura.
    const etiqueta = conTildes(resto);
    return {
      accion,
      panel: resto[0].slice(0, 3),
      colorPanel: COLOR_SIN_PANEL,
      nombrePanel: etiqueta.charAt(0) + etiqueta.slice(1).toLowerCase(),
      etiqueta,
    };
  }
  return {
    accion,
    panel: '',
    colorPanel: null,
    nombrePanel: '',
    etiqueta: conTildes(conVerbo.length ? conVerbo : palabras),
  };
}
