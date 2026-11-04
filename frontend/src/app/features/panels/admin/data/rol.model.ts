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
