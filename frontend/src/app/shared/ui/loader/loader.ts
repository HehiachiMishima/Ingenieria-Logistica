import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Carga } from '../../../core/services/carga';

/**
 * Pantalla de carga de Atlas: fondo de marca (Atlas de mármol) con velo
 * oscuro, emblema con anillo rojo/oro, texto de estado y barra indeterminada.
 */
@Component({
  selector: 'ui-loader',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (carga.visible()) {
      <div class="loader" role="status" aria-live="polite" animate.leave="loader--saliendo">
        <span class="loader__foto" aria-hidden="true"></span>
        <span class="loader__velo" aria-hidden="true"></span>

        <div class="loader__box">
          <span class="loader__emblema" aria-hidden="true">
            <span class="loader__anillo"></span>
            <img src="marca/atlas-logo.webp" alt="" width="256" height="256" />
          </span>

          <p class="loader__marca">AT<span>L</span>AS</p>
          <p class="loader__texto">
            {{ carga.texto() }}<span class="loader__puntos" aria-hidden="true"><i></i><i></i><i></i></span>
          </p>
          @if (carga.detalle()) {
            <p class="loader__detalle">{{ carga.detalle() }}</p>
          }
          <span class="loader__barra" aria-hidden="true"></span>
        </div>

        <p class="loader__pie">Farmacias Peruanas · Ingeniería Logística</p>
      </div>
    }
  `,
  styles: `
    :host { display: contents; }

    .loader {
      position: fixed;
      inset: 0;
      z-index: var(--z-loader);
      display: grid;
      place-items: center;
      overflow: hidden;
      color: #fff;
      background: #05080f;
      animation: loader-entrar 420ms cubic-bezier(0.16, 1, 0.3, 1) both;
    }

    .loader--saliendo {
      animation: loader-salir 380ms cubic-bezier(0.7, 0, 0.84, 0) both;
    }

    .loader__foto {
      position: absolute;
      inset: -4%;
      background: url('/marca/atlas-carga.webp') 62% 30% / cover no-repeat;
      filter: grayscale(0.25) contrast(1.05);
      animation: loader-zoom 9s ease-out both;
    }

    .loader__velo {
      position: absolute;
      inset: 0;
      background:
        radial-gradient(60% 55% at 50% 50%, rgba(5, 8, 15, 0.35), rgba(5, 8, 15, 0.86) 70%, #05080f),
        linear-gradient(180deg, rgba(120, 8, 18, 0.28), transparent 40%, rgba(120, 8, 18, 0.32));
      backdrop-filter: blur(2px);
    }

    .loader__box {
      position: relative;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 14px;
      padding: 32px;
      text-align: center;
      animation: loader-subir 640ms 80ms cubic-bezier(0.16, 1, 0.3, 1) both;
    }

    .loader__emblema {
      position: relative;
      display: grid;
      place-items: center;
      width: 132px;
      height: 132px;
      margin-bottom: 6px;
      border-radius: 50%;
      background: radial-gradient(circle at 50% 38%, rgba(255, 255, 255, 0.14), rgba(255, 255, 255, 0.02) 62%);
      box-shadow:
        0 0 0 1px rgba(255, 255, 255, 0.08) inset,
        0 30px 60px -20px rgba(0, 0, 0, 0.8),
        0 0 80px -10px rgba(225, 29, 46, 0.45);

      img {
        width: 96px;
        height: 96px;
        object-fit: contain;
        filter: drop-shadow(0 12px 18px rgba(0, 0, 0, 0.6));
        animation: loader-flotar 3.2s ease-in-out infinite;
      }
    }

    .loader__anillo {
      position: absolute;
      inset: -6px;
      border-radius: 50%;
      background: conic-gradient(from 0deg, transparent 0 55%, #f7c400 72%, #e11d2e 88%, transparent 100%);
      -webkit-mask: radial-gradient(farthest-side, transparent calc(100% - 3px), #000 calc(100% - 2px));
      mask: radial-gradient(farthest-side, transparent calc(100% - 3px), #000 calc(100% - 2px));
      animation: ui-spin 1.4s linear infinite;
    }

    .loader__marca {
      font-family: var(--font-display);
      font-size: clamp(2.6rem, 2rem + 3vw, 4rem);
      font-weight: 900;
      line-height: 1;
      letter-spacing: 0.04em;
      color: #ff2e42;
      text-shadow: 0 2px 0 #9d0a17, 0 18px 40px rgba(225, 29, 46, 0.45);

      span { color: #f7c400; text-shadow: 0 2px 0 #a37f00, 0 18px 40px rgba(247, 196, 0, 0.35); }
    }

    .loader__texto {
      display: inline-flex;
      align-items: baseline;
      font-size: clamp(1.05rem, 0.95rem + 0.5vw, 1.35rem);
      font-weight: 700;
      letter-spacing: 0.01em;
    }

    .loader__puntos {
      display: inline-flex;
      gap: 3px;
      margin-left: 4px;

      i {
        width: 5px;
        height: 5px;
        border-radius: 50%;
        background: currentColor;
        animation: loader-punto 1.2s ease-in-out infinite;
      }
      i:nth-child(2) { animation-delay: 0.15s; }
      i:nth-child(3) { animation-delay: 0.3s; }
    }

    .loader__detalle {
      margin-top: -6px;
      font-size: 0.9rem;
      color: rgba(255, 255, 255, 0.66);
    }

    .loader__barra {
      position: relative;
      width: min(260px, 70vw);
      height: 3px;
      margin-top: 8px;
      overflow: hidden;
      border-radius: 3px;
      background: rgba(255, 255, 255, 0.12);

      &::after {
        content: '';
        position: absolute;
        inset: 0;
        width: 40%;
        border-radius: inherit;
        background: linear-gradient(90deg, transparent, #e11d2e, #f7c400, transparent);
        animation: loader-barra 1.3s cubic-bezier(0.45, 0, 0.55, 1) infinite;
      }
    }

    .loader__pie {
      position: absolute;
      bottom: max(24px, env(safe-area-inset-bottom));
      left: 0;
      right: 0;
      text-align: center;
      font-size: 0.66rem;
      font-weight: 700;
      letter-spacing: 0.26em;
      text-transform: uppercase;
      color: rgba(255, 255, 255, 0.45);
    }

    @keyframes loader-entrar { from { opacity: 0; } }
    @keyframes loader-salir { to { opacity: 0; filter: blur(6px); transform: scale(1.02); } }
    @keyframes loader-zoom { from { transform: scale(1.12); } }
    @keyframes loader-subir { from { opacity: 0; transform: translateY(16px) scale(0.97); } }
    @keyframes loader-flotar { 50% { transform: translateY(-5px); } }
    @keyframes loader-punto { 0%, 80%, 100% { opacity: 0.25; transform: translateY(0); } 40% { opacity: 1; transform: translateY(-3px); } }
    @keyframes loader-barra { from { transform: translateX(-100%); } to { transform: translateX(250%); } }

    @media (prefers-reduced-motion: reduce) {
      .loader, .loader--saliendo, .loader__foto, .loader__box,
      .loader__emblema img, .loader__puntos i { animation: none; }
      .loader__anillo, .loader__barra::after { animation-duration: 3s; }
    }
  `,
})
export class Loader {
  protected readonly carga = inject(Carga);
}
