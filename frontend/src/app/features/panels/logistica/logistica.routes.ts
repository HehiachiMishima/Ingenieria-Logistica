import { Routes } from '@angular/router';
import { PANEL_LOGISTICA } from '../../../core/config/paneles';
import { PanelShell } from '../panel-shell';
import { aPrimerModulo } from '../panel-rutas';

export const LOGISTICA_ROUTES: Routes = [
  {
    path: '',
    component: PanelShell,
    data: { panel: PANEL_LOGISTICA },
    children: [
      { path: '', pathMatch: 'full', redirectTo: aPrimerModulo(PANEL_LOGISTICA) },
      {
        path: 'resumen',
        loadComponent: () => import('./pages/resumen/resumen').then((m) => m.LogisticaResumen),
      },
    ],
  },
];
