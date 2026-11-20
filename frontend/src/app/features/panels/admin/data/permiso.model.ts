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
