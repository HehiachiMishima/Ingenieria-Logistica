import { HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CONFIG } from '../config/configuracion';
import { mensajeDeError } from '../errors/error.util';
import { Notificaciones } from '../services/notificaciones';
import { SesionStore } from '../services/sesion';
import { esRutaDeAcceso } from './rutas-acceso';

/**
 * Marca una petición de segundo plano: sus errores no muestran toasts
 * (un 401 sigue cerrando la sesión).
 */
export const PETICION_SILENCIOSA = new HttpContextToken<boolean>(() => false);

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const notificaciones = inject(Notificaciones);
  const sesionStore = inject(SesionStore);
  const router = inject(Router);
  // Login, renovación y cierre manejan sus propios errores.
  const esAcceso = esRutaDeAcceso(req.url);

  return next(req).pipe(
    catchError((err: unknown) => {
      if (esAcceso || !(err instanceof HttpErrorResponse)) {
        return throwError(() => err);
      }

      const silenciosa = req.context.get(PETICION_SILENCIOSA);

      // Un 401 aquí ya pasó por la renovación (authInterceptor): no hay sesión que recuperar.
      if (err.status === 401) {
        const teniaSesion = sesionStore.sesion() !== null;
        sesionStore.limpiar();
        if (teniaSesion) {
          notificaciones.advertencia('Sesión expirada', 'Vuelve a iniciar sesión para continuar.');
        }
        redirigirAlLogin(router);
      } else if (silenciosa) {
        // Petición de fondo: sin toasts.
      } else if (err.status === 403) {
        notificaciones.error('Acceso denegado', mensajeDeError(err));
      } else if (err.status === 429) {
        notificaciones.advertencia('Demasiadas solicitudes', mensajeDeError(err));
      } else if (err.status !== 0) {
        notificaciones.error('Error', mensajeDeError(err));
      } else {
        notificaciones.error('Sin conexión', mensajeDeError(err));
      }

      return throwError(() => err);
    }),
  );
};

function redirigirAlLogin(router: Router): void {
  if (router.url.startsWith(CONFIG.rutas.login)) {
    return;
  }
  void router.navigate([CONFIG.rutas.login], {
    queryParams: { returnUrl: router.url },
  });
}
