import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { CONFIG } from '../config/configuracion';

const CLAVE = CONFIG.almacenamiento.intro;

/** Respaldo en memoria por si el almacenamiento de sesión no está disponible. */
let vista = false;

/** La intro se muestra una vez por pestaña: al cerrarla vuelve a aparecer. */
export function introVista(): boolean {
  if (vista) {
    return true;
  }
  try {
    return sessionStorage.getItem(CLAVE) === '1';
  } catch {
    return false;
  }
}

export function marcarIntroVista(): void {
  vista = true;
  try {
    sessionStorage.setItem(CLAVE, '1');
  } catch {
    /* almacenamiento no disponible */
  }
}

/** Antes del primer login de la pestaña pasa por la intro, conservando el returnUrl. */
export const introGuard: CanActivateFn = (route) => {
  const router = inject(Router);

  return introVista()
    ? true
    : router.createUrlTree([CONFIG.rutas.intro], { queryParams: route.queryParams });
};
