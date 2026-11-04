import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { ChipFiltro, FiltrosActivos } from '../../../../../shared/ui/panel-filtros/filtros-activos';
import { PanelFiltros } from '../../../../../shared/ui/panel-filtros/panel-filtros';
import { SelectMultiple } from '../../../../../shared/ui/select-multiple/select-multiple';
import { CAMPOS, CampoFiltro, diaOperativo, etiquetaHora, fechaDia, hhmm, nf } from '../../data/picking.logica';
import { PickingStore } from '../../data/picking.store';

/** Antigüedad del archivo: verde hasta 45 min, ámbar hasta 90, rojo después. */
const FRESCURA = { ambar: 45, rojo: 90 };

/**
 * Cabecera común de los módulos de Picking: título, estado de los datos del
 * WMS, búsqueda y filtros (los mismos en los tres módulos).
 */
@Component({
  selector: 'app-picking-barra',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, PanelFiltros, FiltrosActivos, SelectMultiple],
  templateUrl: './barra.html',
  styleUrl: './barra.scss',
})
export class PickingBarra {
  readonly store = inject(PickingStore);
  readonly titulo = input.required<string>();
  readonly subtitulo = input('');

  readonly campos = CAMPOS;
  readonly horas = Array.from({ length: 24 }, (_, i) => ({ valor: i, etiqueta: etiquetaHora(i) }));
  readonly filtrosAbierto = signal(false);
  private espera?: ReturnType<typeof setTimeout>;

  readonly meta = computed(() => {
    const d = this.store.datos();
    const corte = this.store.corte();
    if (!d || corte == null) {
      return null;
    }
    const minutos = Math.max(0, (this.store.ahora() - corte) / 60_000);
    return {
      dia: fechaDia(d.opKey),
      corte: hhmm(corte),
      hora: hhmm(this.store.ahora()),
      antiguedad: minutos < 1 ? 'hace menos de 1 min' : minutos < 120 ? `hace ${Math.round(minutos)} min` : `hace ${Math.floor(minutos / 60)} h`,
      nivel: minutos >= FRESCURA.rojo ? 'danger' : minutos >= FRESCURA.ambar ? 'warning' : 'success',
      /** El archivo es de otro día operativo que el que corre en Lima. */
      otroDia: diaOperativo(this.store.ahora()) !== d.opKey,
      arrastre: d.arrastre,
    };
  });

  /** Resultado de la última revisión del SFTP, si no fue normal. */
  readonly aviso = computed(() => {
    const e = this.store.estado();
    return e && ['SIN_ARCHIVO', 'ERROR'].includes(e.sincronizacion) ? e.mensaje : null;
  });

  readonly chips = computed<ChipFiltro[]>(() => {
    const f = this.store.filtros();
    const chips: ChipFiltro[] = CAMPOS.filter((c) => f.campos[c.k]?.length).map((c) => {
      const vs = f.campos[c.k]!.map((v) => this.store.etiqueta(c.k, v));
      return { clave: c.k, etiqueta: c.l, valor: vs.length > 3 ? `${vs.slice(0, 3).join(', ')} +${vs.length - 3}` : vs.join(', ') };
    });
    if (f.busqueda.trim()) chips.push({ clave: 'busqueda', etiqueta: 'Búsqueda', valor: f.busqueda.trim() });
    if (f.horas) {
      chips.push({
        clave: 'horas',
        etiqueta: 'Inicio de pick',
        valor: `${etiquetaHora(f.horas[0])} a ${etiquetaHora(f.horas[1]).slice(0, 2)}:59`,
      });
    }
    return chips;
  });

  readonly nf = nf;

  buscar(texto: string): void {
    clearTimeout(this.espera);
    this.espera = setTimeout(() => this.store.setBusqueda(texto), 220);
  }

  setCampo(campo: CampoFiltro, valores: (string | number)[]): void {
    this.store.setCampo(campo, valores.map(String));
  }

  /** Rango de horas: el extremo que falte toma el inicio o el fin del día operativo. */
  setHora(extremo: 0 | 1, valor: string): void {
    const rango: (number | null)[] = [...(this.store.filtros().horas ?? [null, null])];
    rango[extremo] = valor === '' ? null : Number(valor);
    if (rango[0] === null && rango[1] === null) {
      this.store.setHoras(null);
      return;
    }
    const desde = rango[0] ?? 0;
    const hasta = rango[1] ?? 23;
    this.store.setHoras([Math.min(desde, hasta), Math.max(desde, hasta)]);
  }

  quitar(clave: string): void {
    if (clave === 'busqueda') this.store.setBusqueda('');
    else if (clave === 'horas') this.store.setHoras(null);
    else this.store.setCampo(clave as CampoFiltro, []);
  }
}
