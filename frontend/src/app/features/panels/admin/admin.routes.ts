import { Routes } from '@angular/router';
import { PANEL_ADMIN } from '../../../core/config/paneles';
import { PERMISOS } from '../../../core/config/permisos';
import { permisoGuard } from '../../../core/guards/permiso.guard';
import { PanelShell } from '../panel-shell';
import { aPrimerModulo } from '../panel-rutas';

export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    component: PanelShell,
    data: { panel: PANEL_ADMIN },
    children: [
      { path: '', pathMatch: 'full', redirectTo: aPrimerModulo(PANEL_ADMIN) },
      {
        path: 'dashboard',
        canActivate: [permisoGuard(PERMISOS.DASHBOARD_ADMIN)],
        loadComponent: () => import('./pages/dashboard/dashboard').then((m) => m.Dashboard),
      },
      {
        path: 'usuarios',
        canActivate: [permisoGuard(PERMISOS.VER_USUARIOS)],
        loadComponent: () => import('./pages/usuarios/usuarios').then((m) => m.Usuarios),
      },
      {
        path: 'roles',
        canActivate: [permisoGuard(PERMISOS.VER_ROLES)],
        loadComponent: () => import('./pages/roles/roles').then((m) => m.Roles),
      },
      {
        path: 'permisos',
        canActivate: [permisoGuard(PERMISOS.VER_PERMISOS)],
        loadComponent: () => import('./pages/permisos/permisos').then((m) => m.Permisos),
      },
    ],
  },
];
