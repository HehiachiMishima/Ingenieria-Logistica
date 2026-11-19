import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { of } from 'rxjs';
import { RecursoRemoto } from '../../../../../core/utils/listado-remoto';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../shared/ui/modal/modal';
import { Paginador } from '../../../../../shared/ui/paginador/paginador';
import { ObservabilidadApi } from '../../data/observabilidad.api';
import { EndpointMonitoreo, Evento, Periodo } from '../../data/observabilidad.model';
import { ListaCursor } from '../../state/lista-cursor';
import {
  BADGE_NIVEL,
  META_P95_MS,
  Nivel,
  PERIODOS_MONITOREO,
  TEXTO_NIVEL_API,
  badgeEstado,
  badgeMetodo,
  nivelApi,
} from '../../ui/observabilidad.util';
import { PaginadorCursor } from '../../ui/paginador-cursor';
import { SelectorPeriodo } from '../../ui/selector-periodo';

const ORDEN_NIVEL: Record<Nivel, number> = { riesgo: 0, aviso: 1, ok: 2 };

/**
 * Monitoreo: tabla de APIs del período (primero las críticas) y el detalle de
 * peticiones de cada una. Los indicadores y gráficos viven en el Dashboard.
 */
@Component({
  selector: 'app-admin-monitoreo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, DecimalPipe, EmptyState, Icon, Modal, Paginador, PaginadorCursor, SelectorPeriodo],
  templateUrl: './monitoreo.html',
  styleUrl: './monitoreo.scss',
})
export class Monitoreo {
  private readonly api = inject(ObservabilidadApi);

  readonly periodos = PERIODOS_MONITOREO;
  readonly meta = META_P95_MS;
  readonly badgeNivel = BADGE_NIVEL;
  readonly textoNivel = TEXTO_NIVEL_API;
  readonly badgeEstado = badgeEstado;
  readonly badgeMetodo = badgeMetodo;

  readonly dias = signal<Periodo>(1);
  readonly busqueda = signal('');
  readonly soloFueraDeMeta = signal(false);
  readonly pagina = signal(1);
  readonly porPagina = signal(10);

  private readonly apis = new RecursoRemoto(() => this.api.endpoints(this.dias()));

  readonly cargando = this.apis.cargando;
  readonly error = this.apis.error;
  readonly cargada = computed(() => this.apis.valor() !== null);
  /** Hora en que llegaron los datos mostrados. */
  readonly corte = computed(() => (this.apis.valor() ? new Date() : null));

  private readonly todas = computed(() =>
    (this.apis.valor() ?? [])
      .map((e) => ({ ...e, nivel: nivelApi(e) }))
      .sort((a, b) => ORDEN_NIVEL[a.nivel] - ORDEN_NIVEL[b.nivel] || b.errores - a.errores || b.peticiones - a.peticiones),
  );

  readonly fueraDeMeta = computed(() => this.todas().filter((e) => e.nivel !== 'ok').length);

  readonly filas = computed(() => {
    const texto = this.busqueda().trim().toLowerCase();
    return this.todas().filter(
      (e) =>
        (!this.soloFueraDeMeta() || e.nivel !== 'ok') &&
        (!texto || `${e.metodo} ${e.ruta}`.toLowerCase().includes(texto)),
    );
  });

  readonly paginadas = computed(() => {
    const inicio = (this.pagina() - 1) * this.porPagina();
    return this.filas().slice(inicio, inicio + this.porPagina());
  });

  // --- Peticiones de una API (modal) ---
  readonly detalle = signal<EndpointMonitoreo | null>(null);
  readonly soloErrores = signal(false);
  readonly peticiones = new ListaCursor<Evento>((antes) => {
    const endpoint = this.detalle();
    return endpoint
      ? this.api.peticiones(this.dias(), endpoint, this.soloErrores(), antes)
      : of({ filas: [], siguiente: null });
  });

  constructor() {
    this.cargar();
  }

  cargar(): void {
    this.apis.recargar();
  }

  cambiarPeriodo(dias: Periodo): void {
    if (dias === this.dias()) {
      return;
    }
    this.dias.set(dias);
    this.pagina.set(1);
    this.cargar();
  }

  buscar(texto: string): void {
    this.busqueda.set(texto);
    this.pagina.set(1);
  }

  alternarFueraDeMeta(): void {
    this.soloFueraDeMeta.update((v) => !v);
    this.pagina.set(1);
  }

  cambiarPorPagina(n: number): void {
    this.porPagina.set(n);
    this.pagina.set(1);
  }

  abrir(endpoint: EndpointMonitoreo): void {
    this.detalle.set(endpoint);
    this.soloErrores.set(false);
    this.peticiones.reiniciar();
  }

  cerrarDetalle(): void {
    this.detalle.set(null);
  }

  alternarErrores(): void {
    this.soloErrores.update((v) => !v);
    this.peticiones.reiniciar();
  }
}
