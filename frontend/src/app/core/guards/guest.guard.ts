import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SesionStore } from '../services/sesion';

export const guestGuard: CanActivateFn = () => {
  const sesion = inject(SesionStore);
  const router = inject(Router);

  return sesion.autenticado() ? router.createUrlTree(['/']) : true;
};
