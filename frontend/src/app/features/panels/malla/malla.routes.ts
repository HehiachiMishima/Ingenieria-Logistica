import { Routes } from '@angular/router';
import { PANEL_MALLA } from '../../../core/config/paneles';
import { PERMISOS } from '../../../core/config/permisos';
import { permisoGuard } from '../../../core/guards/permiso.guard';
import { PanelShell } from '../panel-shell';
import { aPrimerModulo } from '../panel-rutas';

export const MALLA_ROUTES: Routes = [
  {
    path: '',
    component: PanelShell,
    data: { panel: PANEL_MALLA },
    children: [
      { path: '', pathMatch: 'full', redirectTo: aPrimerModulo(PANEL_MALLA) },
      {
        path: 'configuracion',
        canActivate: [permisoGuard(PERMISOS.VER_MALLA)],
        loadComponent: () =>
          import('./pages/configuracion/configuracion').then((m) => m.MallaConfiguracion),
        canDeactivate: [
          (pagina: { puedeSalir(destino: string): boolean }, _ruta, _estado, siguiente) =>
            pagina.puedeSalir(siguiente.url),
        ],
      },
      {
        path: 'operativa',
        canActivate: [permisoGuard(PERMISOS.VER_MALLA)],
        loadComponent: () => import('./pages/operativa/operativa').then((m) => m.MallaOperativa),
      },
    ],
  },
];
