import { PERMISOS } from './permisos';

export interface ModuloPanel {
  id: string;
  nombre: string;
  descripcion: string;
  icono: string;
  ruta: string;
  permiso: string;
}

export interface PanelDef {
  id: string;
  nombre: string;
  descripcion: string;
  icono: string;
  ruta: string;
  permiso: string;
  /** Color de señal del panel (CSS): tarjeta del launcher y detalles del panel. */
  acento: string;
  modulos: ModuloPanel[];
}

export const PANEL_ADMIN: PanelDef = {
  id: 'admin',
  nombre: 'Administración',
  descripcion: 'Gobierno de identidades, accesos y trazabilidad del sistema.',
  icono: 'shield',
  ruta: '/admin',
  permiso: PERMISOS.PANEL_ADMIN,
  acento: 'var(--violet)',
  modulos: [
    {
      id: 'dashboard',
      nombre: 'Dashboard',
      descripcion: 'Resumen operativo del control de accesos.',
      icono: 'dashboard',
      ruta: 'dashboard',
      permiso: PERMISOS.DASHBOARD_ADMIN,
    },
    {
      id: 'usuarios',
      nombre: 'Usuarios',
      descripcion: 'Altas, edición, estado y asignación de roles.',
      icono: 'usuarios',
      ruta: 'usuarios',
      permiso: PERMISOS.VER_USUARIOS,
    },
    {
      id: 'roles',
      nombre: 'Roles',
      descripcion: 'Roles y los permisos que conceden.',
      icono: 'roles',
      ruta: 'roles',
      permiso: PERMISOS.VER_ROLES,
    },
    {
      id: 'permisos',
      nombre: 'Permisos',
      descripcion: 'Catálogo de permisos del sistema.',
      icono: 'permisos',
      ruta: 'permisos',
      permiso: PERMISOS.VER_PERMISOS,
    },
  ],
};

export const PANEL_BITACORA: PanelDef = {
  id: 'bitacora',
  nombre: 'Bitácora',
  descripcion: 'Registro de incidencias, catálogos de control y métricas operativas.',
  icono: 'bitacora',
  ruta: '/bitacora',
  permiso: PERMISOS.PANEL_BITACORA,
  acento: 'var(--brand)',
  modulos: [
    {
      id: 'incidencias',
      nombre: 'Incidencias',
      descripcion: 'Registro y seguimiento de incidencias.',
      icono: 'incidencias',
      ruta: 'incidencias',
      permiso: PERMISOS.VER_INCIDENCIAS,
    },
    {
      id: 'control',
      nombre: 'Control',
      descripcion: 'Centros, sistemas, áreas y responsables.',
      icono: 'control',
      ruta: 'control',
      permiso: PERMISOS.VER_CONTROL,
    },
    {
      id: 'dashboard',
      nombre: 'Dashboard',
      descripcion: 'Indicadores de la bitácora.',
      icono: 'dashboard',
      ruta: 'dashboard',
      permiso: PERMISOS.VER_DASHBOARD,
    },
  ],
};

export const PANEL_SIMULACION: PanelDef = {
  id: 'simulacion',
  nombre: 'Simulación',
  descripcion: 'Proyección de rutas, balanceo por chutes y análisis de tiempos.',
  icono: 'engranaje',
  ruta: '/simulacion',
  permiso: PERMISOS.PANEL_SIMULACION,
  acento: 'var(--cyan)',
  modulos: [
    {
      id: 'registro',
      nombre: 'Registro',
      descripcion: 'Carga y administración de los archivos Excel de proyección.',
      icono: 'almacen',
      ruta: 'registro',
      permiso: PERMISOS.VER_REGISTRO_SIMULACION,
    },
    {
      id: 'configuracion',
      nombre: 'Configuración',
      descripcion: 'Balanceo de rutas por cluster para igualar los bultos de sus chutes.',
      icono: 'control',
      ruta: 'configuracion',
      permiso: PERMISOS.VER_CONFIGURACION_SIMULACION,
    },
    {
      id: 'dashboard',
      nombre: 'Dashboard',
      descripcion: 'Comparativo de tiempos antes y después del balanceo.',
      icono: 'dashboard',
      ruta: 'dashboard',
      permiso: PERMISOS.VER_DASHBOARD_SIMULACION,
    },
  ],
};

export const PANEL_MALLA: PanelDef = {
  id: 'malla',
  nombre: 'Malla Operativa',
  descripcion: 'Turnos, flujos de proceso, olas y capacidad por centro de distribución.',
  icono: 'calendario',
  ruta: '/malla',
  permiso: PERMISOS.PANEL_MALLA,
  acento: 'var(--cyan)',
  modulos: [
    {
      id: 'configuracion',
      nombre: 'Configuración',
      descripcion: 'Datos de cada centro: turnos, flujos, olas, zonas, capacidad y dotación.',
      icono: 'control',
      ruta: 'configuracion',
      permiso: PERMISOS.VER_MALLA,
    },
    {
      id: 'operativa',
      nombre: 'Malla',
      descripcion: 'Vista operativa de cada centro, por turno y por proceso.',
      icono: 'calendario',
      ruta: 'operativa',
      permiso: PERMISOS.VER_MALLA,
    },
  ],
};

export const PANEL_PICKING: PanelDef = {
  id: 'picking',
  nombre: 'Picking',
  descripcion: 'Avance del picking del día: órdenes, SKU y cumplimiento por área y cluster.',
  icono: 'paquete',
  ruta: '/picking',
  permiso: PERMISOS.PANEL_PICKING,
  acento: 'var(--warning)',
  modulos: [
    {
      id: 'resumen',
      nombre: 'Resumen',
      descripcion: 'Avance del día por área, cluster, ruta y hora.',
      icono: 'dashboard',
      ruta: 'resumen',
      permiso: PERMISOS.VER_RESUMEN_PICKING,
    },
    {
      id: 'ordenes',
      nombre: 'Órdenes',
      descripcion: 'Control de órdenes: estado, unidades y detalle por cartón.',
      icono: 'logistica',
      ruta: 'ordenes',
      permiso: PERMISOS.VER_ORDENES_PICKING,
    },
    {
      id: 'sku',
      nombre: 'SKU',
      descripcion: 'Detalle por SKU: unidades solicitadas, asignadas y empacadas.',
      icono: 'almacen',
      ruta: 'sku',
      permiso: PERMISOS.VER_SKU_PICKING,
    },
  ],
};

/**
 * Paneles que todavía no tienen módulos con lógica propia: misma estructura
 * que los paneles reales (permiso, módulos, acento) para que su acceso se
 * valide igual que el resto.
 */
export const PANEL_ANALISIS: PanelDef = {
  id: 'analisis',
  nombre: 'Análisis',
  descripcion: 'Indicadores, Fill Rate, incidencias y desempeño operativo.',
  icono: 'analisis',
  ruta: '/analisis',
  permiso: PERMISOS.PANEL_ANALISIS,
  acento: 'var(--brand)',
  modulos: [
    {
      id: 'resumen',
      nombre: 'Resumen',
      descripcion: 'Vista general del panel de análisis.',
      icono: 'dashboard',
      ruta: 'resumen',
      permiso: PERMISOS.PANEL_ANALISIS,
    },
  ],
};

export const PANEL_TRAZABILIDAD: PanelDef = {
  id: 'trazabilidad',
  nombre: 'Trazabilidad',
  descripcion: 'Seguimiento de pedidos, rutas, procesos e incidencias.',
  icono: 'trazabilidad',
  ruta: '/trazabilidad',
  permiso: PERMISOS.PANEL_TRAZABILIDAD,
  acento: 'var(--accent)',
  modulos: [
    {
      id: 'resumen',
      nombre: 'Resumen',
      descripcion: 'Vista general del panel de trazabilidad.',
      icono: 'dashboard',
      ruta: 'resumen',
      permiso: PERMISOS.PANEL_TRAZABILIDAD,
    },
  ],
};

export const PANEL_LOGISTICA: PanelDef = {
  id: 'logistica',
  nombre: 'Logística',
  descripcion: 'Gestión de las herramientas y procesos de la operación.',
  icono: 'logistica',
  ruta: '/logistica',
  permiso: PERMISOS.PANEL_LOGISTICA,
  acento: 'var(--success)',
  modulos: [
    {
      id: 'resumen',
      nombre: 'Resumen',
      descripcion: 'Vista general del panel de logística.',
      icono: 'dashboard',
      ruta: 'resumen',
      permiso: PERMISOS.PANEL_LOGISTICA,
    },
  ],
};

export const PANEL_AUTOMATIZACION: PanelDef = {
  id: 'automatizacion',
  nombre: 'Automatización',
  descripcion: 'Reducción de tareas manuales y consolidación de reportes.',
  icono: 'automatizacion',
  ruta: '/automatizacion',
  permiso: PERMISOS.PANEL_AUTOMATIZACION,
  acento: 'var(--violet)',
  modulos: [
    {
      id: 'resumen',
      nombre: 'Resumen',
      descripcion: 'Vista general del panel de automatización.',
      icono: 'dashboard',
      ruta: 'resumen',
      permiso: PERMISOS.PANEL_AUTOMATIZACION,
    },
  ],
};

export const PANEL_SINCRONIZACION: PanelDef = {
  id: 'sincronizacion',
  nombre: 'Sincronización',
  descripcion: 'Integración de información de diferentes sistemas y fuentes.',
  icono: 'sincronizacion',
  ruta: '/sincronizacion',
  permiso: PERMISOS.PANEL_SINCRONIZACION,
  acento: '#f59e0b',
  modulos: [
    {
      id: 'resumen',
      nombre: 'Resumen',
      descripcion: 'Vista general del panel de sincronización.',
      icono: 'dashboard',
      ruta: 'resumen',
      permiso: PERMISOS.PANEL_SINCRONIZACION,
    },
  ],
};

export const PANELES: PanelDef[] = [
  PANEL_ADMIN,
  PANEL_BITACORA,
  PANEL_SIMULACION,
  PANEL_MALLA,
  PANEL_PICKING,
  PANEL_ANALISIS,
  PANEL_TRAZABILIDAD,
  PANEL_LOGISTICA,
  PANEL_AUTOMATIZACION,
  PANEL_SINCRONIZACION,
];

/** Módulos del panel que el usuario puede abrir. */
export function modulosAccesibles(panel: PanelDef, permisos: readonly string[]): ModuloPanel[] {
  return panel.modulos.filter((modulo) => permisos.includes(modulo.permiso));
}

export function primerModuloAccesible(panel: PanelDef, permisos: readonly string[]): ModuloPanel | undefined {
  return modulosAccesibles(panel, permisos)[0];
}

export function panelAccesible(panel: PanelDef, permisos: readonly string[]): boolean {
  return permisos.includes(panel.permiso);
}

export function tienePermiso(permisos: readonly string[], permiso: string): boolean {
  return permisos.includes(permiso);
}
