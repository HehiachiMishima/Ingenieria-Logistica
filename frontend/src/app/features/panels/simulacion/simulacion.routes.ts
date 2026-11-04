import { Routes } from '@angular/router';
import { PANEL_SIMULACION } from '../../../core/config/paneles';
import { PERMISOS } from '../../../core/config/permisos';
import { permisoGuard } from '../../../core/guards/permiso.guard';
import { PanelShell } from '../panel-shell';
import { aPrimerModulo } from '../panel-rutas';

export const SIMULACION_ROUTES: Routes = [
  {
    path: '',
    component: PanelShell,
    data: { panel: PANEL_SIMULACION },
    children: [
      { path: '', pathMatch: 'full', redirectTo: aPrimerModulo(PANEL_SIMULACION) },
      {
        path: 'registro',
        canActivate: [permisoGuard(PERMISOS.VER_REGISTRO_SIMULACION)],
        loadComponent: () => import('./pages/registro/registro').then((m) => m.SimulacionRegistro),
      },
      {
        path: 'configuracion',
        canActivate: [permisoGuard(PERMISOS.VER_CONFIGURACION_SIMULACION)],
        loadComponent: () =>
          import('./pages/configuracion/configuracion').then((m) => m.SimulacionConfiguracion),
      },
      {
        path: 'dashboard',
        canActivate: [permisoGuard(PERMISOS.VER_DASHBOARD_SIMULACION)],
        loadComponent: () =>
          import('./pages/dashboard/dashboard').then((m) => m.SimulacionDashboard),
      },
    ],
  },
];
