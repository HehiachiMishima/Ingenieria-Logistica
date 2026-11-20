export interface Permiso {
  id: number;
  nombre: string;
  descripcion: string | null;
  activo: boolean;
  creadoEn: string;
  actualizadoEn: string | null;
  creadoPor: number | null;
  actualizadoPor: number | null;
}

export interface PermisoCrearRequest {
  nombre: string;
  descripcion?: string | null;
  activo?: boolean;
}

export interface PermisoActualizarRequest {
  nombre?: string;
  descripcion?: string | null;
  activo?: boolean;
}

export interface UsuarioPermisoOverride {
  permisoId: number;
  otorgado: boolean;
}

/** Fila de override individual (grant/deny) de un usuario sobre un permiso. */
export interface UsuarioPermisoDetalle {
  usuarioId: number;
  usuario: string;
  permisoId: number;
  permisoNombre: string;
  otorgado: boolean;
  creadoEn: string;
  actualizadoEn: string | null;
}

/** Fila de v_permisos_efectivos: aporte del ROL (true) o de un OVERRIDE, antes de MIN(). */
export interface PermisoEfectivo {
  usuarioId: number;
  permisoId: number;
  otorgado: boolean;
  origen: 'ROL' | 'OVERRIDE' | string;
}

/** Permiso del catálogo visto desde un rol: si el rol lo concede y en cuántos roles está. */
export interface PermisoDelRol {
  id: number;
  nombre: string;
  descripcion: string | null;
  activo: boolean;
  asignado: boolean;
  roles: number;
}

/** Cambios en lote de los permisos de un rol (una sola transacción). */
export interface RolPermisosLoteRequest {
  agregar: number[];
  quitar: number[];
}

/** Lo que cambió de verdad y los ids que el rol tiene ahora. */
export interface RolPermisosLote {
  rolId: number;
  agregados: number;
  quitados: number;
  asignados: number[];
}

/** Permiso del catálogo visto desde un usuario (tablero de permisos individuales). */
export interface PermisoDelUsuario {
  id: number;
  nombre: string;
  descripcion: string | null;
  activo: boolean;
  /** Algún rol activo del usuario lo concede. */
  porRol: boolean;
  /** Excepción del usuario: true concede, false deniega, null hereda del rol. */
  excepcion: boolean | null;
  /** Quien edita lo tiene y puede concederlo. */
  otorgable: boolean;
}

export interface PermisosUsuarioTablero {
  usuarioId: number;
  /** Por qué quien edita no puede cambiar las excepciones de este usuario; null si puede. */
  bloqueo: string | null;
  permisos: PermisoDelUsuario[];
}

/** Cambios en lote de las excepciones de un usuario (una sola transacción). */
export interface UsuarioPermisosLoteRequest {
  conceder: number[];
  denegar: number[];
  heredar: number[];
}

export interface UsuarioPermisosLote {
  usuarioId: number;
  concedidos: number;
  denegados: number;
  heredados: number;
  excepciones: { permisoId: number; otorgado: boolean }[];
}

/** Rol visto desde un permiso (tablero «Roles con este permiso»). */
export interface RolDelPermiso {
  id: number;
  nombre: string;
  descripcion: string | null;
  activo: boolean;
  /** Ya concede este permiso. */
  asignado: boolean;
  permisos: number;
  usuarios: number;
  paneles: string[];
  /** Rol ADMINISTRADOR: no se le quitan permisos. */
  protegido: boolean;
}

export interface RolesPermisoTablero {
  permisoId: number;
  nombre: string;
  descripcion: string | null;
  activo: boolean;
  /** Quien edita tiene el permiso y puede darlo. */
  otorgable: boolean;
  /** Usuarios distintos que lo tienen hoy por sus roles activos. */
  usuarios: number;
  roles: RolDelPermiso[];
}

/** Da (agregar) o quita el permiso a varios roles (una sola transacción). */
export interface PermisoRolesLoteRequest {
  agregar: number[];
  quitar: number[];
}

export interface PermisoRolesLote {
  permisoId: number;
  agregados: number;
  quitados: number;
  asignados: number[];
  usuarios: number;
}
