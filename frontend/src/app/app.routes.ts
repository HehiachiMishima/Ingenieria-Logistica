import { Routes } from '@angular/router';
import { PERMISOS } from './core/config/permisos';
import { authGuard } from './core/guards/auth.guard';
import { guestGuard } from './core/guards/guest.guard';
import { introGuard } from './core/guards/intro.guard';
import { permisoGuard } from './core/guards/permiso.guard';

/**
 * Los paneles se protegen con `canMatch`: la sesión y el permiso se comprueban
 * antes de cargar el panel y de su redirección al primer módulo, de modo que
 * un enlace directo sin sesión vuelve a esa misma URL tras el login.
 */
export const routes: Routes = [
  {
    path: 'intro',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/intro/intro').then((m) => m.Intro),
  },
  {
    path: 'login',
    canActivate: [guestGuard, introGuard],
    loadComponent: () => import('./features/auth/login/login').then((m) => m.Login),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./features/launcher/launcher').then((m) => m.Launcher),
  },
  {
    path: 'sin-acceso',
    canActivate: [authGuard],
    loadComponent: () => import('./features/sin-acceso/sin-acceso').then((m) => m.SinAcceso),
  },
  {
    path: 'admin',
    canMatch: [permisoGuard(PERMISOS.PANEL_ADMIN)],
    loadChildren: () => import('./features/panels/admin/admin.routes').then((m) => m.ADMIN_ROUTES),
  },
  {
    path: 'bitacora',
    canMatch: [permisoGuard(PERMISOS.PANEL_BITACORA)],
    loadChildren: () =>
      import('./features/panels/bitacora/bitacora.routes').then((m) => m.BITACORA_ROUTES),
  },
  {
    path: 'simulacion',
    canMatch: [permisoGuard(PERMISOS.PANEL_SIMULACION)],
    loadChildren: () =>
      import('./features/panels/simulacion/simulacion.routes').then((m) => m.SIMULACION_ROUTES),
  },
  {
    path: 'malla',
    canMatch: [permisoGuard(PERMISOS.PANEL_MALLA)],
    loadChildren: () => import('./features/panels/malla/malla.routes').then((m) => m.MALLA_ROUTES),
  },
  {
    path: 'picking',
    canMatch: [permisoGuard(PERMISOS.PANEL_PICKING)],
    loadChildren: () => import('./features/panels/picking/picking.routes').then((m) => m.PICKING_ROUTES),
  },
  {
    path: 'analisis',
    canMatch: [permisoGuard(PERMISOS.PANEL_ANALISIS)],
    loadChildren: () =>
      import('./features/panels/analisis/analisis.routes').then((m) => m.ANALISIS_ROUTES),
  },
  {
    path: 'trazabilidad',
    canMatch: [permisoGuard(PERMISOS.PANEL_TRAZABILIDAD)],
    loadChildren: () =>
      import('./features/panels/trazabilidad/trazabilidad.routes').then(
        (m) => m.TRAZABILIDAD_ROUTES,
      ),
  },
  {
    path: 'logistica',
    canMatch: [permisoGuard(PERMISOS.PANEL_LOGISTICA)],
    loadChildren: () =>
      import('./features/panels/logistica/logistica.routes').then((m) => m.LOGISTICA_ROUTES),
  },
  {
    path: 'automatizacion',
    canMatch: [permisoGuard(PERMISOS.PANEL_AUTOMATIZACION)],
    loadChildren: () =>
      import('./features/panels/automatizacion/automatizacion.routes').then(
        (m) => m.AUTOMATIZACION_ROUTES,
      ),
  },
  {
    path: 'sincronizacion',
    canMatch: [permisoGuard(PERMISOS.PANEL_SINCRONIZACION)],
    loadChildren: () =>
      import('./features/panels/sincronizacion/sincronizacion.routes').then(
        (m) => m.SINCRONIZACION_ROUTES,
      ),
  },
  { path: '**', redirectTo: '' },
];
