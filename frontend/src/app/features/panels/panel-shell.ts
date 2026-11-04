import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { CONFIG } from '../../core/config/configuracion';
import { PanelDef, modulosAccesibles } from '../../core/config/paneles';
import { Auth } from '../../core/services/auth';
import { TemaService } from '../../core/services/tema';
import { iniciales } from '../../core/utils/texto.util';
import { Icon } from '../../shared/ui/icon/icon';
import { MiCuenta } from '../../shared/ui/mi-cuenta/mi-cuenta';

const ESCRITORIO = '(min-width: 1024px)';

/**
 * Marco común de todos los paneles de Atlas (definidos en core/config/paneles).
 *
 * - Escritorio (≥1024px): sidebar fijo que empuja el contenido; el botón de
 *   menú lo colapsa a un riel de iconos (la preferencia se recuerda).
 * - Móvil/tablet: el sidebar es un drawer con velo y se cierra al navegar.
 */
@Component({
  selector: 'app-panel-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon, MiCuenta],
  templateUrl: './panel-shell.html',
  styleUrl: './panel-shell.scss',
})
export class PanelShell {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  readonly tema = inject(TemaService);

  readonly panel = input.required<PanelDef>();
  readonly usuario = this.auth.usuario;

  private readonly consulta = window.matchMedia(ESCRITORIO);
  readonly escritorio = signal(this.consulta.matches);
  /** Escritorio: sidebar expandido (true) o en riel (false). */
  readonly expandido = signal(this.leerPreferencia());
  /** Móvil: drawer visible. */
  readonly drawerAbierto = signal(false);
  /** Modal "Mi cuenta". */
  readonly cuentaAbierta = signal(false);

  readonly ahora = signal(new Date());
  private readonly url = signal(this.router.url);

  readonly modulos = computed(() => modulosAccesibles(this.panel(), this.auth.permisos()));

  readonly moduloActual = computed(() => {
    const segmentos = this.url().split('?')[0].split('/').filter(Boolean);
    const ruta = segmentos[1] ?? '';
    return this.panel().modulos.find((modulo) => modulo.ruta === ruta) ?? null;
  });

  readonly iniciales = computed(() => iniciales(this.usuario()));

  readonly hora = computed(() =>
    this.ahora().toLocaleTimeString('es-PE', { hour: '2-digit', minute: '2-digit' }),
  );
  readonly fecha = computed(() =>
    this.ahora().toLocaleDateString('es-PE', { weekday: 'short', day: '2-digit', month: 'short' }),
  );

  constructor() {
    const destroyRef = inject(DestroyRef);

    const alCambiar = (evento: MediaQueryListEvent): void => {
      this.escritorio.set(evento.matches);
      this.drawerAbierto.set(false);
    };
    this.consulta.addEventListener('change', alCambiar);

    // Reloj de la barra superior: se alinea al cambio de minuto.
    let intervalo: ReturnType<typeof setInterval> | undefined;
    const arranque = setTimeout(
      () => {
        this.ahora.set(new Date());
        intervalo = setInterval(() => this.ahora.set(new Date()), 60_000);
      },
      60_000 - (Date.now() % 60_000),
    );

    destroyRef.onDestroy(() => {
      this.consulta.removeEventListener('change', alCambiar);
      clearTimeout(arranque);
      clearInterval(intervalo);
    });

    this.router.events
      .pipe(
        filter((evento): evento is NavigationEnd => evento instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe((evento) => {
        this.url.set(evento.urlAfterRedirects);
        this.drawerAbierto.set(false);
      });
  }

  alternarMenu(): void {
    if (this.escritorio()) {
      this.expandido.update((valor) => !valor);
      this.guardarPreferencia(this.expandido());
    } else {
      this.drawerAbierto.update((valor) => !valor);
    }
  }

  cerrarDrawer(): void {
    this.drawerAbierto.set(false);
  }

  @HostListener('document:keydown.escape')
  cerrarConEscape(): void {
    this.cerrarDrawer();
  }

  alternarTema(evento: Event): void {
    this.tema.alternar(evento);
  }

  irAPaneles(): void {
    void this.router.navigateByUrl('/');
  }

  abrirCuenta(): void {
    this.cuentaAbierta.set(true);
  }

  cerrarSesion(): void {
    this.auth.cerrarSesion();
  }

  private leerPreferencia(): boolean {
    try {
      return localStorage.getItem(CONFIG.almacenamiento.sidebar) !== 'riel';
    } catch {
      return true;
    }
  }

  private guardarPreferencia(expandido: boolean): void {
    try {
      localStorage.setItem(CONFIG.almacenamiento.sidebar, expandido ? 'expandido' : 'riel');
    } catch {
      /* almacenamiento no disponible */
    }
  }
}
