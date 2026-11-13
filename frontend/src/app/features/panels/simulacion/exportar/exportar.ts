import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';
import { descargarArchivo } from '../../../../core/utils/archivo.util';
import { Notificaciones } from '../../../../core/services/notificaciones';
import { Icon } from '../../../../shared/ui/icon/icon';
import { SimulacionApi } from '../data/simulacion.api';
import { DIAS_BALANCEO, DiaBalanceo, ETIQUETA_BALANCEO } from '../data/simulacion.model';
type Formato = 'EXCEL' | 'HTML' | 'PDF';
/** Lo que muestra el reporte (HTML y PDF): un día o los dos. */
type VistaExportar = DiaBalanceo | 'AMBOS';

/**
 * Botón «Exportar» de la simulación: el Excel (un solo archivo con los dos días y su selector de
 * frecuencia), el reporte interactivo en HTML (con su filtro) o el PDF ejecutivo del día elegido
 * (se abre en otra pestaña, listo para imprimir). Se habilita cuando la simulación terminó (todos
 * los clusters cerrados).
 */
@Component({
  selector: 'app-simulacion-exportar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="exp">
      <button
        type="button"
        class="ui-btn ui-btn--primary exp__boton"
        aria-haspopup="menu"
        [attr.aria-expanded]="abierto()"
        [disabled]="!registroId() || !listo() || generando() !== null"
        [title]="listo() ? 'Exportar la simulación' : 'Disponible cuando todos los clusters estén cerrados'"
        (click)="alternar()"
      >
        @if (generando() !== null) {
          <span class="ui-spinner exp__spinner" aria-hidden="true"></span>
        } @else {
          <ui-icon name="descargar" [size]="18" />
        }
        Exportar
        <ui-icon class="exp__flecha" name="chevronRight" [size]="14" />
      </button>
      @if (abierto()) {
        <div class="exp__menu" role="menu" aria-label="Formatos de exportación"
          [style.left.px]="posicion().left" [style.top.px]="posicion().top">
          <!-- El Excel lleva los dos días (con su selector); el día solo aplica al reporte HTML y al PDF. -->
          <button type="button" role="menuitem" class="exp__item" (click)="exportar('EXCEL')">
            <span class="exp__icono exp__icono--excel"><ui-icon name="analisis" [size]="18" /></span>
            <span class="exp__texto">
              <strong>Excel</strong>
              <small>Los dos días en un archivo, con selector de frecuencia</small>
            </span>
          </button>
          <div class="exp__separador" role="separator"></div>
          <span class="exp__rotulo" id="exp-dia">Día del reporte</span>
          <div class="exp__balanceo" role="group" aria-labelledby="exp-dia">
            @for (o of opciones(); track o.vista) {
              <button type="button" class="exp__opcion" [class.exp__opcion--activa]="vista() === o.vista"
                [attr.aria-pressed]="vista() === o.vista" [disabled]="!o.habilitada" [title]="o.titulo"
                (click)="vista.set(o.vista)">{{ o.etiqueta }}</button>
            }
          </div>
          <button type="button" role="menuitem" class="exp__item" (click)="exportar('HTML')">
            <span class="exp__icono exp__icono--html"><ui-icon name="ver" [size]="18" /></span>
            <span class="exp__texto">
              <strong>Reporte interactivo (HTML)</strong>
              <small>Con filtro de frecuencia; se abre en el navegador</small>
            </span>
          </button>
          <button type="button" role="menuitem" class="exp__item" (click)="exportar('PDF')">
            <span class="exp__icono exp__icono--pdf"><ui-icon name="dashboard" [size]="18" /></span>
            <span class="exp__texto">
              <strong>PDF ejecutivo</strong>
              <small>Gráficos del dashboard y cuadrículas, listo para imprimir</small>
            </span>
          </button>
        </div>
      }
    </div>
  `,
  styles: `
    :host { display: inline-flex; }
    .exp { position: relative; display: inline-flex; width: 100%; }
    .exp__boton { width: 100%; justify-content: center; }
    .exp__boton[aria-expanded='true'] .exp__flecha { transform: rotate(90deg); }
    .exp__flecha { transition: transform var(--transition-fast); }
    .exp__spinner { width: 16px; height: 16px; }
    /* Fijo y ubicado por código bajo el botón, siempre dentro de la pantalla. */
    .exp__menu {
      position: fixed;
      z-index: var(--z-modal);
      display: flex;
      flex-direction: column;
      gap: 2px;
      width: min(320px, calc(100vw - 32px));
      padding: var(--space-2);
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      box-shadow: var(--shadow-lg);
      animation: exp-entra 160ms cubic-bezier(0.16, 1, 0.3, 1) both;
    }
    .exp__balanceo {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 2px;
      padding: 2px;
      margin-bottom: var(--space-1);
      border: 1px solid var(--border);
      border-radius: var(--radius-sm);
    }
    .exp__opcion {
      min-height: 32px;
      border: 0;
      border-radius: 6px;
      background: transparent;
      color: var(--text-2);
      font: inherit;
      font-size: var(--fs-sm);
      font-weight: 700;
      cursor: pointer;
    }
    .exp__opcion--activa { color: #fff; background: var(--accent); }
    .exp__opcion:disabled { cursor: not-allowed; opacity: 0.45; }
    .exp__opcion:hover:not(:disabled):not(.exp__opcion--activa) { color: var(--text); background: var(--surface-2); }
    .exp__separador { height: 1px; margin: var(--space-1) 0; background: var(--border); }
    .exp__rotulo {
      padding: 0 var(--space-1);
      font-size: 0.66rem;
      font-weight: 800;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: var(--text-3);
    }
    .exp__opcion:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
    .exp__item {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      width: 100%;
      min-height: 52px;
      padding: var(--space-2) var(--space-3);
      border: 0;
      border-radius: var(--radius-sm);
      background: transparent;
      color: var(--text);
      font: inherit;
      text-align: left;
      cursor: pointer;
    }
    .exp__item:hover, .exp__item:focus-visible { background: var(--surface-2); outline: none; }
    .exp__item:focus-visible { box-shadow: inset 0 0 0 2px var(--accent); }
    .exp__icono {
      display: inline-flex;
      flex: none;
      align-items: center;
      justify-content: center;
      width: 36px;
      height: 36px;
      border-radius: var(--radius-sm);
    }
    .exp__icono--excel { color: var(--success); background: var(--success-soft); }
    .exp__icono--pdf { color: var(--danger); background: var(--danger-soft); }
    .exp__icono--html { color: var(--accent); background: var(--accent-soft); }
    .exp__texto { display: flex; flex-direction: column; min-width: 0; line-height: 1.25; }
    .exp__texto strong { font-size: var(--fs-sm); }
    .exp__texto small { font-size: var(--fs-xs); color: var(--text-3); }
    @keyframes exp-entra { from { opacity: 0; transform: translateY(-4px); } }
    @media (prefers-reduced-motion: reduce) { .exp__menu { animation: none; } }
  `,
})
export class SimulacionExportar {
  private readonly api = inject(SimulacionApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly registroId = input<number | null>(null);
  /** Nombre del registro, para el nombre del archivo. */
  readonly registroNombre = input('');
  /** La simulación terminó: todos los clusters cerrados. */
  readonly listo = input(false);
  /** Días con rutas en el registro: un día sin rutas no se puede elegir (ni «Ambos» sin los dos). */
  readonly dias = input<readonly DiaBalanceo[]>(DIAS_BALANCEO);
  /** Día que mira la página (L-X-V por defecto): el menú abre con él si tiene rutas. */
  readonly balanceoInicial = input<DiaBalanceo>('LXV');

  readonly abierto = signal(false);
  readonly generando = signal<Formato | null>(null);

  /** Vistas que se pueden elegir: los días con rutas y, con los dos, «Ambos». */
  private readonly disponibles = computed<VistaExportar[]>(() => {
    const dias = DIAS_BALANCEO.filter((d) => this.dias().includes(d));
    return dias.length > 1 ? [...dias, 'AMBOS'] : dias;
  });

  /** Vista del reporte (HTML y PDF): la de la página o, si no tiene rutas, la primera con rutas. */
  readonly vista = linkedSignal<VistaExportar>(() => this.vistaInicial());

  readonly opciones = computed(() => {
    const disponibles = this.disponibles();
    return (['LXV', 'MJS', 'AMBOS'] as const).map((v) => ({
      vista: v,
      etiqueta: v === 'AMBOS' ? 'Ambos' : ETIQUETA_BALANCEO[v],
      habilitada: disponibles.includes(v),
      titulo:
        v === 'AMBOS'
          ? disponibles.includes('AMBOS')
            ? 'L-X-V y M-J-S en el mismo reporte'
            : 'Hace falta que los dos días tengan rutas'
          : disponibles.includes(v)
            ? `Solo ${ETIQUETA_BALANCEO[v]} (sus rutas y las diarias)`
            : `Sin rutas ${ETIQUETA_BALANCEO[v]}`,
    }));
  });

  /** Menú abierto: se cierra al tocar fuera o con Escape (listeners nativos, sin zone.js). */
  private readonly cierre = effect((alLimpiar) => {
    if (!this.abierto()) {
      return;
    }
    const alTocar = (e: PointerEvent) => {
      if (!(e.target instanceof Node) || !this.host.nativeElement.contains(e.target)) {
        this.abierto.set(false);
      }
    };
    const alTeclear = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        this.abierto.set(false);
        this.host.nativeElement.querySelector<HTMLButtonElement>('.exp__boton')?.focus();
      }
    };
    // El menú es fijo: al desplazar o cambiar el tamaño se cerraría lejos del botón.
    const alMover = () => this.abierto.set(false);
    document.addEventListener('pointerdown', alTocar, true);
    document.addEventListener('keydown', alTeclear);
    document.addEventListener('scroll', alMover, { capture: true, passive: true });
    window.addEventListener('resize', alMover);
    alLimpiar(() => {
      document.removeEventListener('pointerdown', alTocar, true);
      document.removeEventListener('keydown', alTeclear);
      document.removeEventListener('scroll', alMover, true);
      window.removeEventListener('resize', alMover);
    });
  });

  /** Esquina del menú: alineado a la derecha del botón y recortado a la pantalla (16 px de margen). */
  readonly posicion = signal({ left: 16, top: 0 });

  alternar(): void {
    if (!this.abierto()) {
      // Al abrir, el menú parte del día que mira la página.
      this.vista.set(this.vistaInicial());
      const boton = this.host.nativeElement.querySelector<HTMLElement>('.exp__boton');
      const caja = boton?.getBoundingClientRect();
      if (caja) {
        const ancho = Math.min(320, window.innerWidth - 32);
        const left = Math.min(Math.max(16, caja.right - ancho), window.innerWidth - ancho - 16);
        this.posicion.set({ left, top: caja.bottom + 6 });
      }
    }
    this.abierto.update((valor) => !valor);
  }

  exportar(formato: Formato): void {
    const id = this.registroId();
    this.abierto.set(false);
    if (!id || this.generando() !== null) {
      return;
    }
    if (formato !== 'EXCEL') {
      // El reporte se arma en otra pestaña (recupera la sesión con la cookie). El PDF abre el
      // diálogo de impresión («Guardar como PDF»); el HTML se descarga con los dos días y su filtro.
      const dias = DIAS_BALANCEO.filter((d) => this.dias().includes(d));
      const url = this.router.serializeUrl(
        this.router.createUrlTree(['/simulacion/reporte'], {
          queryParams: {
            registro: id,
            vista: this.vista(),
            dias: (dias.length > 0 ? dias : DIAS_BALANCEO).join(','),
            ...(formato === 'HTML' ? { formato: 'html' } : {}),
          },
        }),
      );
      window.open(url, '_blank', 'noopener');
      return;
    }
    this.generando.set(formato);
    this.api
      .exportarExcel(id)
      .pipe(
        finalize(() => this.generando.set(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      // Los errores los avisa el interceptor global.
      .subscribe((archivo) => {
        descargarArchivo(archivo, nombreArchivo(this.registroNombre()));
        this.notificaciones.exito('Excel generado', 'Los dos días en un archivo, con selector de frecuencia.');
      });
  }

  /** El día de la página si tiene rutas; si no, el primero disponible. */
  private vistaInicial(): VistaExportar {
    const disponibles = this.disponibles();
    const inicial = this.balanceoInicial();
    return disponibles.includes(inicial) ? inicial : (disponibles[0] ?? 'LXV');
  }
}

/** «simulacion-proyeccion-enero.xlsx». */
function nombreArchivo(nombre: string): string {
  const base = nombre
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/(^-|-$)/g, '');
  return `simulacion-${base || 'registro'}.xlsx`;
}
