import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SesionStore } from '../services/sesion';

export const authGuard: CanActivateFn = (_route, state) => {
  const sesion = inject(SesionStore);
  const router = inject(Router);

  if (sesion.autenticado()) {
    return true;
  }
  return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};
