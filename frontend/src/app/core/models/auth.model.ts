import { Usuario } from './usuario.model';

export interface LoginRequest {
  usuario: string;
  contrasena: string;
}

export interface CambiarContrasenaRequest {
  contrasenaActual: string;
  contrasenaNueva: string;
}

export interface AuthResponse {
  token: string;
  tipo: string;
  expiraEn: string;
  /** Hasta cuándo la sesión se renueva sin pedir la contraseña (se extiende al renovar). */
  renovableHasta: string;
  usuario: Usuario;
  permisos: string[];
}

/** Sesión en memoria: el token no se guarda en el navegador (lo renueva la cookie HttpOnly). */
export interface Sesion {
  token: string;
  tipo: string;
  expiraEn: string;
  renovableHasta: string;
  usuario: Usuario;
  permisos: string[];
}

/** Sesión vigente según el servidor (GET /auth/sesion). */
export interface SesionVigente {
  usuario: Usuario;
  permisos: string[];
}
