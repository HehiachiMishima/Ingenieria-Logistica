import { Routes } from '@angular/router';
import { PANEL_AUTOMATIZACION } from '../../../core/config/paneles';
import { PanelShell } from '../panel-shell';
import { aPrimerModulo } from '../panel-rutas';

export const AUTOMATIZACION_ROUTES: Routes = [
  {
    path: '',
    component: PanelShell,
    data: { panel: PANEL_AUTOMATIZACION },
    children: [
      { path: '', pathMatch: 'full', redirectTo: aPrimerModulo(PANEL_AUTOMATIZACION) },
      {
        path: 'resumen',
        loadComponent: () => import('./pages/resumen/resumen').then((m) => m.AutomatizacionResumen),
      },
    ],
  },
];
