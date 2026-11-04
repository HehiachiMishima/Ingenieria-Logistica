import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { compacto } from './formato';

export interface GrupoComparativo {
  etiqueta: string;
  antes: number;
  despues: number;
  /** Texto del valor cuando no es un número representable (p. ej. «Sin operarios»). */
  textoAntes?: string;
  textoDespues?: string;
  /** Referencia del grupo (p. ej. límite de horas): se dibuja como línea discontinua. */
  limite?: number;
}

/**
 * Columnas agrupadas «antes vs después» con escala común: cada grupo (chute,
 * cluster…) muestra dos barras con su valor encima. Los grupos tienen un ancho
 * mínimo fijo; si no caben, el gráfico se desplaza en horizontal en lugar de
 * estrecharse o deformarse.
 */
@Component({
  selector: 'ui-comparativo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cm" [style.--alto.px]="altura()" [style.--despues]="color()">
      <ul class="cm__leyenda">
        <li><i class="cm__sw cm__sw--antes"></i>{{ etiquetaAntes() }}</li>
        <li><i class="cm__sw cm__sw--despues"></i>{{ etiquetaDespues() }}</li>
        @if (hayLimites()) {
          <li><i class="cm__sw cm__sw--limite"></i>Límite</li>
        }
      </ul>
      <div class="cm__cuerpo">
        <div class="cm__eje" aria-hidden="true">
          @for (t of ticks(); track t.pct) {
            <span class="cm__tick" [style.bottom.%]="t.pct">{{ t.texto }}</span>
          }
        </div>
        <div class="cm__scroll">
          <div class="cm__grupos" [style.min-width.px]="grupos().length * anchoGrupo()">
            <div class="cm__guias" aria-hidden="true">
              @for (t of ticks(); track t.pct) {
                <span class="cm__guia" [style.bottom.%]="t.pct"></span>
              }
            </div>
            @for (g of grupos(); track g.etiqueta; let i = $index) {
              <div
                class="cm__grupo"
                role="img"
                [attr.aria-label]="g.etiqueta + ': ' + etiquetaAntes() + ' ' + g.textoAntes + ', ' + etiquetaDespues() + ' ' + g.textoDespues"
              >
                <div class="cm__barras">
                  <span class="cm__col">
                    <span class="cm__valor cm__valor--antes">{{ g.textoAntes }}</span>
                    <span class="cm__barra cm__barra--antes" [style.height.%]="g.pctAntes" [style.animation-delay]="i * 30 + 'ms'"></span>
                  </span>
                  <span class="cm__col">
                    <span class="cm__valor">{{ g.textoDespues }}</span>
                    <span class="cm__barra cm__barra--despues" [style.height.%]="g.pctDespues" [style.animation-delay]="i * 30 + 60 + 'ms'"></span>
                  </span>
                  @if (g.pctLimite !== null) {
                    <span class="cm__limite" [style.bottom.%]="g.pctLimite" [attr.title]="'Límite ' + g.textoLimite"></span>
                  }
                </div>
                <span class="cm__etiqueta" [title]="g.etiqueta">{{ g.etiqueta }}</span>
              </div>
            }
          </div>
        </div>
      </div>
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .cm__leyenda { display: flex; flex-wrap: wrap; gap: 6px 16px; margin: 0 0 var(--space-4); padding: 0; list-style: none; font-size: var(--fs-xs); font-weight: 600; color: var(--text-2); }
    .cm__leyenda li { display: inline-flex; align-items: center; gap: 6px; }
    .cm__sw { width: 12px; height: 12px; border-radius: 3px; }
    .cm__sw--antes { background: var(--chart-otros); }
    .cm__sw--despues { background: var(--despues); }
    .cm__sw--limite { height: 0; border-top: 2px dashed var(--danger); border-radius: 0; }
    .cm__cuerpo { display: flex; gap: var(--space-2); }
    .cm__eje { position: relative; flex: none; width: 44px; height: var(--alto); }
    .cm__tick { position: absolute; right: 0; transform: translateY(50%); font-size: 0.64rem; font-weight: 600; color: var(--text-3); font-variant-numeric: tabular-nums; white-space: nowrap; }
    .cm__scroll { flex: 1; min-width: 0; overflow-x: auto; padding-bottom: 2px; }
    .cm__grupos { position: relative; display: flex; height: calc(var(--alto) + 30px); }
    .cm__guias { position: absolute; top: 0; left: 0; right: 0; height: var(--alto); pointer-events: none; }
    .cm__guia { position: absolute; left: 0; right: 0; height: 0; border-top: 1px dashed var(--border); }
    .cm__grupo { flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; align-items: center; }
    .cm__barras { position: relative; display: flex; align-items: flex-end; justify-content: center; gap: 4px; width: 100%; height: var(--alto); padding: 0 12%; }
    .cm__col { position: relative; display: flex; flex-direction: column; justify-content: flex-end; align-items: center; flex: 1 1 0; max-width: 34px; height: 100%; }
    .cm__barra { display: block; width: 100%; min-height: 2px; border-radius: 5px 5px 2px 2px; transform-origin: bottom; animation: cm-crece 700ms cubic-bezier(0.16, 1, 0.3, 1) both; }
    .cm__barra--antes { background: var(--chart-otros); opacity: 0.75; }
    .cm__barra--despues { background: linear-gradient(180deg, var(--despues), color-mix(in srgb, var(--despues) 70%, transparent)); }
    .cm__valor { margin-bottom: 4px; font-size: 0.66rem; font-weight: 800; color: var(--text); font-variant-numeric: tabular-nums; white-space: nowrap; }
    .cm__valor--antes { color: var(--text-3); font-weight: 700; }
    .cm__limite { position: absolute; left: 6%; right: 6%; height: 0; border-top: 2px dashed var(--danger); }
    .cm__etiqueta { max-width: 100%; height: 30px; padding-top: 8px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 0.7rem; font-weight: 700; color: var(--text-2); }
    @keyframes cm-crece { from { transform: scaleY(0); } to { transform: scaleY(1); } }
    @media (prefers-reduced-motion: reduce) { .cm__barra { animation: none; } }
  `,
})
export class ComparativoChart {
  readonly datos = input<GrupoComparativo[]>([]);
  /** Formato de valores y eje (p. ej. horas «3h 20m»); por defecto, número compacto. */
  readonly formato = input<(valor: number) => string>(compacto);
  readonly etiquetaAntes = input('Antes');
  readonly etiquetaDespues = input('Después');
  readonly color = input('var(--accent)');
  readonly altura = input(300);
  /** Ancho mínimo por grupo: con más grupos el gráfico se desplaza, no se comprime. */
  readonly anchoGrupo = input(76);

  readonly hayLimites = computed(() => this.datos().some((g) => (g.limite ?? 0) > 0));

  /** Máximo del eje redondeado a un valor «limpio» por encima del mayor dato. */
  private readonly maximo = computed(() => {
    const max = Math.max(0, ...this.datos().flatMap((g) => [g.antes, g.despues, g.limite ?? 0]));
    if (max <= 0) {
      return 1;
    }
    const paso = Math.pow(10, Math.floor(Math.log10(max)));
    return Math.ceil((max * 1.12) / paso) * paso;
  });

  readonly ticks = computed(() => {
    const max = this.maximo();
    const formato = this.formato();
    return [0, 0.25, 0.5, 0.75, 1].map((f) => ({ pct: f * 100, texto: formato(max * f) }));
  });

  readonly grupos = computed(() => {
    const max = this.maximo();
    const formato = this.formato();
    return this.datos().map((g) => ({
      etiqueta: g.etiqueta,
      textoAntes: g.textoAntes ?? formato(g.antes),
      textoDespues: g.textoDespues ?? formato(g.despues),
      textoLimite: formato(g.limite ?? 0),
      pctAntes: (Math.max(0, g.antes) / max) * 100,
      pctDespues: (Math.max(0, g.despues) / max) * 100,
      pctLimite: (g.limite ?? 0) > 0 ? Math.min(100, ((g.limite ?? 0) / max) * 100) : null,
    }));
  });
}
