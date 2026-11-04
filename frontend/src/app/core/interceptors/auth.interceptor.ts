import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { RenovacionSesion } from '../services/renovacion-sesion';
import { SesionStore } from '../services/sesion';
import { esRutaDeAcceso } from './rutas-acceso';

/**
 * Agrega el Bearer. Si el token venció, lo renueva antes de enviar; si la
 * respuesta es 401, renueva y reintenta una vez (el 401 llega antes de
 * ejecutar nada, así que repetir un POST es seguro). Si la sesión venció del
 * todo, la petición espera a que el usuario escriba su contraseña.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const sesion = inject(SesionStore);
  const renovacion = inject(RenovacionSesion);

  if (esRutaDeAcceso(req.url) || req.headers.has('Authorization') || sesion.sesion() === null) {
    return next(req);
  }

  const enviar = (token: string | null) =>
    token
      ? next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }))
      : throwError(() => sesionCerrada(req));

  return renovacion
    .tokenListo()
    .pipe(
      switchMap((token) =>
        enviar(token).pipe(
          catchError((err: unknown) =>
            err instanceof HttpErrorResponse && err.status === 401
              ? renovacion.recuperar().pipe(switchMap(enviar))
              : throwError(() => err),
          ),
        ),
      ),
    );
};

/** El usuario cerró sesión mientras la petición esperaba. */
function sesionCerrada(req: HttpRequest<unknown>): HttpErrorResponse {
  return new HttpErrorResponse({ status: 401, statusText: 'Sesión cerrada', url: req.url });
}
