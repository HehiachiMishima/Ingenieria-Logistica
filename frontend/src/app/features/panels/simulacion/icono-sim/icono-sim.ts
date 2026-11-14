import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Íconos a color de la simulación: la única fuente de sus SVG. El tablero de rutas de
 * Configuración y la cuadrícula del PDF dibujan su caja en la plantilla (miles de rutas: un
 * componente por caja sería más lento).
 */
export type NombreIconoSim =
  | 'caja'
  | 'operario'
  | 'reloj'
  | 'balanza'
  | 'mano'
  | 'rampa'
  | 'cara'
  | 'lineas'
  | 'unidades'
  | 'cod'
  // Claves de los conceptos de totales: se dibujan como la caja y el operario.
  | 'bultos'
  | 'operarios';

const ALIAS: Partial<Record<NombreIconoSim, NombreIconoSim>> = {
  bultos: 'caja',
  operarios: 'operario',
};

/** Estado de un chute en la carita: saturado, casi vacío o parejo. */
export type EstadoCara = 'mal' | 'medio' | 'bien';

/**
 * Ícono a color de la simulación: caras planas y contorno oscuro fino, se leen en tema claro y
 * oscuro. Decorativo (aria-hidden): el texto de al lado dice lo que significa.
 */
@Component({
  selector: 'app-icono-sim',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    'aria-hidden': 'true',
    class: 'icono-sim',
    '[class.icono-sim--rampa]': "nombre() === 'rampa'",
    '[style.width.px]': "nombre() === 'rampa' ? null : tamano()",
    '[style.height.px]': "nombre() === 'rampa' ? null : tamano()",
  },
  template: `
    @switch (forma()) {
      @case ('caja') {
        <svg viewBox="0 0 16 16" focusable="false">
          <g stroke="#6b4220" stroke-width="0.8" stroke-linejoin="round">
            <path fill="#e8b97a" d="M8 1.5 14.5 4.5 8 7.5 1.5 4.5Z" />
            <path fill="#c8894a" d="M1.5 4.5 8 7.5V14.5L1.5 11.5Z" />
            <path fill="#a9692f" d="M8 7.5 14.5 4.5V11.5L8 14.5Z" />
          </g>
          <path
            d="M4.75 3 11.25 6V13"
            fill="none"
            stroke="#f6e2b3"
            stroke-width="1.5"
            stroke-linecap="round"
          />
        </svg>
      }
      @case ('operario') {
        <svg viewBox="0 0 16 16" focusable="false">
          <g stroke="#5a2e0e" stroke-width="0.8" stroke-linejoin="round">
            <path
              fill="#f97316"
              d="M2.8 15v-2.3A3.6 3.6 0 0 1 6.4 9.1h3.2a3.6 3.6 0 0 1 3.6 3.6V15Z"
            />
            <path d="M3.1 12.6h9.8" stroke="#fde68a" stroke-width="1.2" />
            <circle fill="#f2c39b" cx="8" cy="6.4" r="2.3" />
            <path fill="#facc15" d="M5.2 5.2a2.8 2.8 0 0 1 5.6 0Z" />
            <rect fill="#eab308" x="4.3" y="4.9" width="7.4" height="1.1" rx="0.5" />
          </g>
        </svg>
      }
      @case ('reloj') {
        <svg viewBox="0 0 16 16" focusable="false">
          <circle cx="8" cy="8" r="6.8" fill="#fff" stroke="#1d63ed" stroke-width="1.4" />
          <path
            d="M8 4.5V8l2.5 1.5"
            stroke="#0b1424"
            stroke-width="1.3"
            fill="none"
            stroke-linecap="round"
          />
          <circle cx="12.3" cy="12.3" r="3.2" fill="#10b981" />
          <path
            d="M10.9 12.3l1 1 1.8-1.8"
            stroke="#fff"
            stroke-width="1"
            fill="none"
            stroke-linecap="round"
          />
        </svg>
      }
      @case ('balanza') {
        <svg viewBox="0 0 16 16" focusable="false">
          <path d="M8 2v11M4 13h8" stroke="#475569" stroke-width="1.3" stroke-linecap="round" />
          <path d="M2.5 4.5h11" stroke="#64748b" stroke-width="1.2" />
          <path d="M1 8.5l1.5-4 1.5 4Z" fill="#a5f3fc" stroke="#0891b2" stroke-width="0.8" />
          <path d="M12 8.5l1.5-4 1.5 4Z" fill="#a5f3fc" stroke="#0891b2" stroke-width="0.8" />
        </svg>
      }
      @case ('mano') {
        <svg viewBox="0 0 16 16" focusable="false">
          <path
            d="M5 8V3.5a1 1 0 0 1 2 0V7m0-1V2.5a1 1 0 0 1 2 0V7m0-1V3.5a1 1 0 0 1 2 0V8m0-1.5a1 1 0 0 1 2 0V10a5 5 0 0 1-5 5H7.5a4 4 0 0 1-3.2-1.6L2 10.4a1.1 1.1 0 0 1 1.7-1.4L5 10.5"
            fill="#fde68a"
            stroke="#b45309"
            stroke-width="0.9"
            stroke-linejoin="round"
          />
        </svg>
      }
      @case ('lineas') {
        <svg viewBox="0 0 16 16" focusable="false">
          <g stroke="#4a2c12" stroke-width="0.8" stroke-linejoin="round">
            <rect fill="#b7793f" x="2.4" y="2.4" width="11.2" height="12.6" rx="1.2" />
            <rect fill="#ffffff" x="3.9" y="4" width="8.2" height="9.6" rx="0.4" />
            <rect fill="#94a3b8" x="5.8" y="1.2" width="4.4" height="2.4" rx="0.6" />
          </g>
          <path
            d="M5.5 6.4h5M5.5 8.6h5M5.5 10.8h3.2"
            stroke="#16a34a"
            stroke-width="1.2"
            stroke-linecap="round"
          />
        </svg>
      }
      @case ('unidades') {
        <svg viewBox="0 0 16 16" focusable="false">
          <g stroke="#5b3208" stroke-width="0.8" stroke-linejoin="round">
            <rect fill="#ffffff" x="1.4" y="5.2" width="7.6" height="9.4" rx="0.5" />
            <path fill="#f59e0b" d="M1.4 5.2H9V7H1.4Z" />
            <path
              fill="#d97706"
              d="M10.4 7.1h3.4a.6.6 0 0 1 .6.6v6.2a.8.8 0 0 1-.8.8h-3a.8.8 0 0 1-.8-.8V7.7a.6.6 0 0 1 .6-.6Z"
            />
            <rect fill="#f8fafc" x="10.3" y="4.6" width="3.6" height="2.5" rx="0.4" />
            <path fill="#fff7ed" d="M9.8 9.6h4.6v2.4H9.8Z" />
          </g>
          <path fill="#16a34a" d="M4.4 8.2h1.6v1.5h1.5v1.6H6v1.5H4.4v-1.5H2.9V9.7h1.5Z" />
        </svg>
      }
      @case ('cod') {
        <svg viewBox="0 0 16 16" focusable="false">
          <g stroke="#0c3a4a" stroke-width="0.8" stroke-linejoin="round">
            <path fill="#f1f5f9" d="M2.4 7h11.2v7.6H2.4Z" />
            <path fill="#0891b2" d="M1.4 3.6h13.2l-.9 3.6H2.3Z" />
            <path fill="#ffffff" d="M4.6 3.6h2.2l-.3 3.6H4Zm4.6 0h2.2l.6 3.6H9.5Z" />
            <path fill="#a5f3fc" d="M3.6 9h2.2v2.2H3.6Zm6.6 0h2.2v2.2h-2.2Z" />
            <path fill="#0e7490" d="M6.7 10h2.6v4.6H6.7Z" />
          </g>
        </svg>
      }
      @case ('rampa') {
        <svg viewBox="0 0 60 16" preserveAspectRatio="none" focusable="false">
          <path d="M2 2h56l-6 12H8Z" fill="#94a3b8" stroke="#475569" stroke-width="1" />
        </svg>
      }
      @case ('cara') {
        <svg viewBox="0 0 16 16" focusable="false">
          <circle cx="8" cy="8" r="7" [attr.fill]="colorCara[estado()]" />
          <circle cx="5.7" cy="6.3" r="0.9" fill="#1f2937" />
          <circle cx="10.3" cy="6.3" r="0.9" fill="#1f2937" />
          <path
            [attr.d]="bocaCara[estado()]"
            stroke="#1f2937"
            stroke-width="1.1"
            fill="none"
            stroke-linecap="round"
          />
        </svg>
      }
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      flex: none;
    }

    svg {
      display: block;
      width: 100%;
      height: 100%;
      overflow: visible;
    }

    :host(.icono-sim--rampa) {
      display: block;
      width: 100%;
      height: 12px;
    }
  `,
})
export class IconoSim {
  readonly nombre = input.required<NombreIconoSim>();
  /** Lado en px; null = lo fija el CSS de quien lo usa (p. ej. otro tamaño en el riel minimizado). */
  readonly tamano = input<number | null>(20);
  /** Forma a dibujar (las claves de conceptos usan la de la caja o el operario). */
  readonly forma = computed(() => ALIAS[this.nombre()] ?? this.nombre());
  /** Solo para la carita. */
  readonly estado = input<EstadoCara>('bien');

  readonly colorCara: Record<EstadoCara, string> = {
    mal: '#ef4444',
    medio: '#f59e0b',
    bien: '#10b981',
  };
  readonly bocaCara: Record<EstadoCara, string> = {
    mal: 'M5.5 11.5q2.5-2.5 5 0',
    medio: 'M5.5 11h5',
    bien: 'M5.5 10q2.5 3 5 0',
  };
}

/**
 * Estado de cada chute según su carga frente al promedio de los que tienen carga: más de 35 %
 * por encima, saturado; más de 35 % por debajo, casi vacío; si no, parejo.
 */
export function estadosCarga(valores: readonly number[]): (EstadoCara | null)[] {
  const con = valores.filter((v) => v > 0);
  const media = con.reduce((t, v) => t + v, 0) / (con.length || 1);
  return valores.map((v) =>
    v <= 0 ? null : v > media * 1.35 ? 'mal' : v < media * 0.65 ? 'medio' : 'bien',
  );
}

/** Diferencia entre chutes (como la de Configuración): (máximo − mínimo) ÷ promedio, en %. */
export function diferenciaCarga(valores: readonly number[]): number | null {
  const con = valores.filter((v) => v > 0);
  if (con.length < 2) {
    return null;
  }
  const media = con.reduce((t, v) => t + v, 0) / con.length;
  return Math.round(((Math.max(...con) - Math.min(...con)) / media) * 100);
}
