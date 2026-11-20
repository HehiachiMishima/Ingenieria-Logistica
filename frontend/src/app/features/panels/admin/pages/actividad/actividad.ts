import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { Paginador } from '../../../../../shared/ui/paginador/paginador';
import { ChipFiltro, FiltrosActivos } from '../../../../../shared/ui/panel-filtros/filtros-activos';
import { PanelFiltros } from '../../../../../shared/ui/panel-filtros/panel-filtros';
import { ObservabilidadApi } from '../../data/observabilidad.api';
import { Evento, Periodo } from '../../data/observabilidad.model';
import { ListadoPaginado } from '../../state/listado-paginado';
import {
  BADGE_RESULTADO,
  PERIODOS_MONITOREO,
  badgeEstado,
  badgeMetodo,
  inicialesDe,
  registroVisible,
} from '../../ui/observabilidad.util';
import { SelectorPeriodo } from '../../ui/selector-periodo';

/**
 * Actividad: peticiones de los usuarios, filtrables por período, usuario y «solo cambios».
 * La columna Cambio muestra qué datos modificó la petición y con qué resultado; las
 * consultas no cambian datos y muestran «—». Los indicadores de uso viven en el Dashboard.
 */
@Component({
  selector: 'app-admin-actividad',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, DecimalPipe, EmptyState, FiltrosActivos, Icon, Paginador, PanelFiltros, SelectorPeriodo],
  templateUrl: './actividad.html',
  styleUrl: './actividad.scss',
})
export class Actividad {
  private readonly api = inject(ObservabilidadApi);

  readonly periodos = PERIODOS_MONITOREO;
  readonly badgeEstado = badgeEstado;
  readonly badgeMetodo = badgeMetodo;
  readonly badgeResultado = BADGE_RESULTADO;
  readonly iniciales = inicialesDe;
  readonly registro = registroVisible;
  readonly vistas = [
    { soloCambios: false, etiqueta: 'Todas las peticiones' },
    { soloCambios: true, etiqueta: 'Solo cambios' },
  ] as const;

  readonly dias = signal<Periodo>(7);
  readonly usuario = signal('');
  readonly soloCambios = signal(false);
  readonly filtrosAbierto = signal(false);

  readonly lista = new ListadoPaginado<Evento>((pagina, tamano) =>
    this.api.actividad(this.dias(), this.usuario(), this.soloCambios(), pagina, tamano),
  );
  /** Hora de la última respuesta correcta: avanza con cada Actualizar, período o página. */
  readonly corte = this.lista.listado.actualizado;

  readonly chips = computed<ChipFiltro[]>(() => {
    const chips: ChipFiltro[] = [];
    if (this.soloCambios()) {
      chips.push({ clave: 'cambios', etiqueta: 'Mostrar', valor: 'Solo cambios', icono: 'edit' });
    }
    if (this.usuario()) {
      chips.push({ clave: 'usuario', etiqueta: 'Usuario', valor: this.usuario(), icono: 'user' });
    }
    return chips;
  });
  readonly filtrosActivos = computed(() => this.chips().length);

  /** Mensaje de la tabla vacía según lo filtrado (un nombre incompleto no se confunde con «sin actividad»). */
  readonly vacio = computed(() => {
    const usuario = this.usuario();
    const que = this.soloCambios() ? 'cambios' : 'peticiones';
    if (usuario) {
      return {
        titulo: 'Sin resultados',
        mensaje: `No hay ${que} de «${usuario}» en el período. Revisa que el nombre de usuario esté completo.`,
      };
    }
    return this.soloCambios()
      ? { titulo: 'Sin cambios', mensaje: 'Nadie cambió datos en el período.' }
      : { titulo: 'Sin actividad', mensaje: 'Aún no hay peticiones de usuarios en el período.' };
  });

  constructor() {
    this.lista.reiniciar();
  }

  cambiarPeriodo(dias: Periodo): void {
    if (dias !== this.dias()) {
      this.dias.set(dias);
      this.lista.reiniciar();
    }
  }

  aplicarUsuario(valor: string): void {
    const usuario = valor.trim();
    if (usuario !== this.usuario()) {
      this.usuario.set(usuario);
      this.lista.reiniciar();
    }
  }

  aplicarVista(soloCambios: boolean): void {
    if (soloCambios !== this.soloCambios()) {
      this.soloCambios.set(soloCambios);
      this.lista.reiniciar();
    }
  }

  quitarFiltro(clave: string): void {
    if (clave === 'usuario') {
      this.aplicarUsuario('');
    } else if (clave === 'cambios') {
      this.aplicarVista(false);
    }
  }

  limpiar(): void {
    const habia = this.usuario() !== '' || this.soloCambios();
    this.usuario.set('');
    this.soloCambios.set(false);
    if (habia) {
      this.lista.reiniciar();
    }
  }
}
