import { Routes } from '@angular/router';
import { PANEL_SIMULACION_SANTA } from '../../../core/config/paneles';
import { PERMISOS } from '../../../core/config/permisos';
import { permisoGuard } from '../../../core/guards/permiso.guard';
import { PanelShell } from '../panel-shell';
import { aPrimerModulo } from '../panel-rutas';

export const SIMULACION_SANTA_ROUTES: Routes = [
  {
    path: '',
    component: PanelShell,
    data: { panel: PANEL_SIMULACION_SANTA },
    children: [
      { path: '', pathMatch: 'full', redirectTo: aPrimerModulo(PANEL_SIMULACION_SANTA) },
      {
        path: 'registro',
        canActivate: [permisoGuard(PERMISOS.VER_REGISTRO_SIMULACION_SANTA)],
        loadComponent: () => import('./pages/registro/registro').then((m) => m.SantaRegistro),
      },
      {
        path: 'configuracion',
        canActivate: [permisoGuard(PERMISOS.VER_CONFIGURACION_SIMULACION_SANTA)],
        loadComponent: () =>
          import('./pages/configuracion/configuracion').then((m) => m.SantaConfiguracion),
      },
    ],
  },
];
