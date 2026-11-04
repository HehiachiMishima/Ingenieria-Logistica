import { Routes } from '@angular/router';
import { PANEL_PICKING } from '../../../core/config/paneles';
import { PERMISOS } from '../../../core/config/permisos';
import { permisoGuard } from '../../../core/guards/permiso.guard';
import { PanelShell } from '../panel-shell';
import { aPrimerModulo } from '../panel-rutas';

export const PICKING_ROUTES: Routes = [
  {
    path: '',
    component: PanelShell,
    data: { panel: PANEL_PICKING },
    children: [
      { path: '', pathMatch: 'full', redirectTo: aPrimerModulo(PANEL_PICKING) },
      {
        path: 'resumen',
        canActivate: [permisoGuard(PERMISOS.VER_RESUMEN_PICKING)],
        loadComponent: () => import('./pages/resumen/resumen').then((m) => m.PickingResumen),
      },
      {
        path: 'ordenes',
        canActivate: [permisoGuard(PERMISOS.VER_ORDENES_PICKING)],
        loadComponent: () => import('./pages/ordenes/ordenes').then((m) => m.PickingOrdenes),
      },
      {
        path: 'sku',
        canActivate: [permisoGuard(PERMISOS.VER_SKU_PICKING)],
        loadComponent: () => import('./pages/sku/sku').then((m) => m.PickingSku),
      },
    ],
  },
];
