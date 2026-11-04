import { HttpErrorResponse } from '@angular/common/http';

export interface CuerpoError {
  status?: number;
  error?: string;
  mensaje?: string;
  campos?: Record<string, string>;
}

export function cuerpoDeError(err: unknown): CuerpoError | null {
  if (err instanceof HttpErrorResponse && err.error && typeof err.error === 'object') {
    return err.error as CuerpoError;
  }
  return null;
}

export function mensajeDeError(err: unknown): string {
  const cuerpo = cuerpoDeError(err);
  if (cuerpo?.mensaje) {
    return cuerpo.mensaje;
  }
  if (err instanceof HttpErrorResponse) {
    if (err.status === 0) {
      return 'No se pudo conectar con el servidor.';
    }
    if (err.status >= 500) {
      return 'Ocurrió un error en el servidor. Intenta nuevamente.';
    }
    return err.statusText || 'Ocurrió un error inesperado.';
  }
  return 'Ocurrió un error inesperado.';
}

/** true si el backend respondio 403 (acceso denegado / operacion no permitida). */
export function esAccesoDenegado(err: unknown): boolean {
  return err instanceof HttpErrorResponse && err.status === 403;
}
