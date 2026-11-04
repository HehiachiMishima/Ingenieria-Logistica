import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { diaNegocio, fechaIso } from '../../../core/utils/fecha.util';

export interface RangoFechas {
  desde: string;
  hasta: string;
}

interface Opcion {
  id: string;
  etiqueta: string;
  rango: () => RangoFechas;
}

function haceDias(dias: number): RangoFechas {
  const hoy = diaNegocio();
  const desde = new Date(hoy);
  desde.setDate(hoy.getDate() - (dias - 1));
  return { desde: fechaIso(desde), hasta: fechaIso(hoy) };
}

const OPCIONES: Opcion[] = [
  { id: 'hoy', etiqueta: 'Hoy', rango: () => haceDias(1) },
  { id: '7d', etiqueta: '7 días', rango: () => haceDias(7) },
  { id: '30d', etiqueta: '30 días', rango: () => haceDias(30) },
  {
    id: 'mes',
    etiqueta: 'Este mes',
    rango: () => {
      const hoy = diaNegocio();
      return {
        desde: fechaIso(new Date(hoy.getFullYear(), hoy.getMonth(), 1)),
        hasta: fechaIso(hoy),
      };
    },
  },
  {
    id: 'anio',
    etiqueta: 'Este año',
    rango: () => {
      const hoy = diaNegocio();
      return { desde: fechaIso(new Date(hoy.getFullYear(), 0, 1)), hasta: fechaIso(hoy) };
    },
  },
];

/** Atajos de rango de fechas; resalta el atajo que coincide con el rango actual. */
@Component({
  selector: 'ui-rango-rapido',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ui-rango" role="group" aria-label="Rangos rápidos de fecha">
      @for (o of opciones; track o.id) {
        <button
          type="button"
          class="ui-rango__btn"
          [class.ui-rango__btn--on]="activo() === o.id"
          [attr.aria-pressed]="activo() === o.id"
          (click)="rango.emit(o.rango())"
        >
          {{ o.etiqueta }}
        </button>
      }
    </div>
  `,
})
export class RangoRapido {
  readonly desde = input('');
  readonly hasta = input('');
  readonly rango = output<RangoFechas>();

  readonly opciones = OPCIONES;

  readonly activo = computed(() => {
    const desde = this.desde();
    const hasta = this.hasta();
    if (!desde || !hasta) {
      return null;
    }
    return (
      OPCIONES.find((o) => {
        const r = o.rango();
        return r.desde === desde && r.hasta === hasta;
      })?.id ?? null
    );
  });
}
