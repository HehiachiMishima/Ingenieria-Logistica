import { Routes } from '@angular/router';
import { PANEL_ANALISIS } from '../../../core/config/paneles';
import { PERMISOS } from '../../../core/config/permisos';
import { permisoGuard } from '../../../core/guards/permiso.guard';
import { PanelShell } from '../panel-shell';
import { aPrimerModulo } from '../panel-rutas';

export const ANALISIS_ROUTES: Routes = [
  {
    path: '',
    component: PanelShell,
    data: { panel: PANEL_ANALISIS },
    children: [
      { path: '', pathMatch: 'full', redirectTo: aPrimerModulo(PANEL_ANALISIS) },
      {
        path: 'resumen',
        canActivate: [permisoGuard(PERMISOS.PANEL_ANALISIS)],
        loadComponent: () => import('./pages/resumen/resumen').then((m) => m.AnalisisResumen),
      },
    ],
  },
];
