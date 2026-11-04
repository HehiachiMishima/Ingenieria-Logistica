import { DATE_PIPE_DEFAULT_OPTIONS } from '@angular/common';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { routes } from './app.routes';
import { CONFIG } from './core/config/configuracion';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { errorInterceptor } from './core/interceptors/error.interceptor';
import { RenovacionSesion } from './core/services/renovacion-sesion';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
    ),
    // errorInterceptor va por fuera: solo ve los errores que la renovación no pudo resolver.
    provideHttpClient(withInterceptors([errorInterceptor, authInterceptor])),
    // Recupera la sesión con la cookie antes de la primera navegación (los guards la necesitan).
    provideAppInitializer(() => inject(RenovacionSesion).iniciar()),
    // La auditoría llega en UTC: el pipe date la muestra siempre en la zona del negocio.
    { provide: DATE_PIPE_DEFAULT_OPTIONS, useValue: { timezone: CONFIG.zonaHoraria } },
  ],
};
