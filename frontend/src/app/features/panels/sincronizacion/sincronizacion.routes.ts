import { Routes } from '@angular/router';
import { PANEL_SINCRONIZACION } from '../../../core/config/paneles';
import { PanelShell } from '../panel-shell';
import { aPrimerModulo } from '../panel-rutas';

export const SINCRONIZACION_ROUTES: Routes = [
  {
    path: '',
    component: PanelShell,
    data: { panel: PANEL_SINCRONIZACION },
    children: [
      { path: '', pathMatch: 'full', redirectTo: aPrimerModulo(PANEL_SINCRONIZACION) },
      {
        path: 'resumen',
        loadComponent: () => import('./pages/resumen/resumen').then((m) => m.SincronizacionResumen),
      },
    ],
  },
];
