import { inject } from '@angular/core';
import { CanActivateFn, CanMatchFn, Router, UrlTree } from '@angular/router';
import { CONFIG } from '../config/configuracion';
import { SesionStore } from '../services/sesion';

/**
 * Guard de acceso utilizable como `canMatch` (raíz de cada panel: se evalúa
 * antes de cargar el panel y de la redirección a su primer módulo) o como
 * `canActivate` (módulos dentro del panel).
 */
type GuardAcceso = CanMatchFn & CanActivateFn;

/**
 * URL que se está intentando abrir. En `canMatch` aún no hay URL final
 * (las redirecciones no se han resuelto), así que se usa la pedida.
 */
function urlEnCurso(router: Router): string {
  const navegacion = router.currentNavigation();
  if (!navegacion) {
    return router.url;
  }
  return router.serializeUrl(navegacion.finalUrl ?? navegacion.extractedUrl);
}

function comprobar(
  permitido: (sesion: SesionStore) => boolean,
  permiso: string,
): boolean | UrlTree {
  const sesion = inject(SesionStore);
  const router = inject(Router);

  if (!sesion.autenticado()) {
    return router.createUrlTree([CONFIG.rutas.login], {
      queryParams: { returnUrl: urlEnCurso(router) },
    });
  }
  if (permitido(sesion)) {
    return true;
  }
  return router.createUrlTree([CONFIG.rutas.sinAcceso], { queryParams: { permiso } });
}

export function permisoGuard(permiso: string): GuardAcceso {
  return () => comprobar((sesion) => sesion.tienePermiso(permiso), permiso);
}

/**
 * Guard de acceso a un panel. A diferencia de permisoGuard, permite el paso a
 * los paneles marcados como `publico` (aún sin permisos), exigiendo solo estar
 * autenticado.
 */
export function panelGuard(panel: { permiso: string; publico?: boolean }): GuardAcceso {
  return () =>
    comprobar(
      (sesion) => panel.publico === true || sesion.tienePermiso(panel.permiso),
      panel.permiso,
    );
}
