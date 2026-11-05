/** Analítica del dashboard de Administración (GET /admin/analitica). */
export interface AnaliticaAdmin {
  /** Los indicadores de riesgo cuentan solo usuarios activos. */
  kpis: {
    usuarios: number;
    usuariosActivos: number;
    /** Activos sin ningún rol activo. */
    usuariosSinRol: number;
    /** Activos con al menos un permiso individual (concedido o denegado). */
    usuariosConExcepcion: number;
    /** Activos con rol y sin excepciones: gobernados solo por su rol. */
    usuariosSoloRol: number;
    overridesGrant: number;
    overridesDeny: number;
    /** Activos con una sesión vigente ahora. */
    conectados: number;
    altas30: number;
    altasPrevias30: number;
    roles: number;
    rolesActivos: number;
    rolesSinUsuarios: number;
    rolesSinPermisos: number;
    permisos: number;
    permisosActivos: number;
  };
  coberturaPaneles: Conteo[];
  usuariosPorRol: Conteo[];
  /** Últimos 12 meses: altas del mes y total registrado al cierre. */
  crecimiento: { periodo: string; altas: number; total: number }[];
}

export interface Conteo {
  etiqueta: string;
  valor: number;
}
