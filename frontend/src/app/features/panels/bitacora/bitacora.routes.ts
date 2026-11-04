import { Routes } from '@angular/router';
import { PANEL_BITACORA } from '../../../core/config/paneles';
import { PERMISOS } from '../../../core/config/permisos';
import { permisoGuard } from '../../../core/guards/permiso.guard';
import { PanelShell } from '../panel-shell';
import { aPrimerModulo } from '../panel-rutas';

export const BITACORA_ROUTES: Routes = [
  {
    path: '',
    component: PanelShell,
    data: { panel: PANEL_BITACORA },
    children: [
      { path: '', pathMatch: 'full', redirectTo: aPrimerModulo(PANEL_BITACORA) },
      {
        path: 'incidencias',
        canActivate: [permisoGuard(PERMISOS.VER_INCIDENCIAS)],
        loadComponent: () =>
          import('./pages/incidencias/incidencias').then((m) => m.Incidencias),
      },
      {
        path: 'control',
        canActivate: [permisoGuard(PERMISOS.VER_CONTROL)],
        loadChildren: () =>
          import('./pages/control/control.routes').then((m) => m.CONTROL_ROUTES),
      },
      {
        path: 'dashboard',
        canActivate: [permisoGuard(PERMISOS.VER_DASHBOARD)],
        loadComponent: () =>
          import('./pages/dashboard/dashboard').then((m) => m.DashboardBitacora),
      },
    ],
  },
];
