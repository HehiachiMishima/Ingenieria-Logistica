import { Routes } from '@angular/router';
import { PANEL_TRAZABILIDAD } from '../../../core/config/paneles';
import { PERMISOS } from '../../../core/config/permisos';
import { permisoGuard } from '../../../core/guards/permiso.guard';
import { PanelShell } from '../panel-shell';
import { aPrimerModulo } from '../panel-rutas';

export const TRAZABILIDAD_ROUTES: Routes = [
  {
    path: '',
    component: PanelShell,
    data: { panel: PANEL_TRAZABILIDAD },
    children: [
      { path: '', pathMatch: 'full', redirectTo: aPrimerModulo(PANEL_TRAZABILIDAD) },
      {
        path: 'resumen',
        canActivate: [permisoGuard(PERMISOS.PANEL_TRAZABILIDAD)],
        loadComponent: () => import('./pages/resumen/resumen').then((m) => m.TrazabilidadResumen),
      },
    ],
  },
];
