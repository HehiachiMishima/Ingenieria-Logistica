import { HttpHeaders } from '@angular/common/http';

/**
 * Login, renovación y cierre: viajan sin Bearer (el access token puede estar
 * vencido), con la cookie del refresh token y con X-Requested-With, que el
 * backend exige para que otro sitio no pueda dispararlas. Sus errores los
 * maneja quien las llama: un 401 aquí no es «sesión vencida».
 */
const RUTAS_ACCESO = ['/auth/login', '/auth/refresh', '/auth/logout'];

export function esRutaDeAcceso(url: string): boolean {
  return RUTAS_ACCESO.some((ruta) => url.includes(ruta));
}

export function opcionesAcceso() {
  return {
    withCredentials: true,
    headers: new HttpHeaders({ 'X-Requested-With': 'XMLHttpRequest' }),
  };
}
