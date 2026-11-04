import { Routes } from '@angular/router';
import { Control } from './control';

export const CONTROL_ROUTES: Routes = [
  {
    path: '',
    component: Control,
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'centros' },
      {
        path: 'centros',
        loadComponent: () => import('./centros/centros').then((m) => m.Centros),
      },
      {
        path: 'sistemas',
        loadComponent: () => import('./sistemas/sistemas').then((m) => m.Sistemas),
      },
      {
        path: 'areas',
        loadComponent: () => import('./areas/areas').then((m) => m.Areas),
      },
    ],
  },
];
