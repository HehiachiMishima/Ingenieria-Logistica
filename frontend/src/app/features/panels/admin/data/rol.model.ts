export interface Rol {
  id: number;
  nombre: string;
  descripcion: string | null;
  activo: boolean;
  creadoEn: string;
  actualizadoEn: string | null;
  creadoPor: number | null;
  actualizadoPor: number | null;
}

export interface RolCrearRequest {
  nombre: string;
  descripcion?: string | null;
  activo?: boolean;
}

export interface RolActualizarRequest {
  nombre?: string;
  descripcion?: string | null;
  activo?: boolean;
}

/** Rol del catálogo visto desde un usuario (tablero de roles del usuario). */
export interface RolDelUsuario {
  id: number;
  nombre: string;
  descripcion: string | null;
  activo: boolean;
  asignado: boolean;
  /** Permisos que trae el rol. */
  permisos: number;
  /** Usuarios que lo tienen. */
  usuarios: number;
  /** Permisos PANEL_* activos del rol: a qué paneles da entrada. */
  paneles: string[];
  /** Quien edita tiene todos sus permisos y puede otorgarlo. */
  otorgable: boolean;
}

export interface RolesUsuarioTablero {
  usuarioId: number;
  /** Por qué quien edita no puede cambiar los roles de este usuario; null si puede. */
  bloqueo: string | null;
  roles: RolDelUsuario[];
}

/** Cambios en lote de los roles de un usuario (una sola transacción). */
export interface UsuarioRolesLoteRequest {
  agregar: number[];
  quitar: number[];
}

export interface UsuarioRolesLote {
  usuarioId: number;
  agregados: number;
  quitados: number;
  asignados: number[];
}
