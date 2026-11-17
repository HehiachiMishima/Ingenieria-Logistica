import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { PERMISOS } from '../../../../../core/config/permisos';
import { SesionStore } from '../../../../../core/services/sesion';
import { HasPermiso } from '../../../../../shared/directives/has-permiso.directive';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../shared/ui/modal/modal';
import { ConfiguracionSantaStore } from '../../data/configuracion.store';
import { Cluster, OrdenLlenado, PaletaSimulacion } from '../../models/santa.model';
import { BLOQUES } from '../../plano/plano-santa';
import {
  CLUSTERS,
  colorCluster,
  decimal,
  entero,
  etiquetaEstadoPaleta,
  etiquetaEstadoRegistro,
  porcentaje,
} from '../../santa-formato';
import { AndenRampas, RampaVista } from '../../ui/anden-rampas/anden-rampas';
import { BloquePasillos, PasilloVista } from '../../ui/bloque-pasillos/bloque-pasillos';
import { MovimientoUbicaciones, TableroClusters } from '../../ui/tablero-clusters/tablero-clusters';

type Ventana = 'rampas' | '1-6' | '7-12' | '13-18' | '19-24';

/** Ventanas del plano en el orden de los botones (y de las teclas 1 a 5). */
const VENTANAS: { id: Ventana; etiqueta: string }[] = [
  { id: 'rampas', etiqueta: 'Rampas' },
  { id: '1-6', etiqueta: '1–6' },
  { id: '7-12', etiqueta: '7–12' },
  { id: '13-18', etiqueta: '13–18' },
  { id: '19-24', etiqueta: '19–24' },
];

const ZOOMS = [
  { valor: 1, etiqueta: 'M' },
  { valor: 1.25, etiqueta: 'L' },
  { valor: 1.5, etiqueta: 'XL' },
];

@Component({
  selector: 'app-santa-configuracion',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Icon, Modal, EmptyState, HasPermiso, AndenRampas, BloquePasillos, TableroClusters],
  providers: [ConfiguracionSantaStore],
  host: { '(document:keydown)': 'atajo($event)' },
  templateUrl: './configuracion.html',
  styleUrl: './configuracion.scss',
})
export class SantaConfiguracion {
  readonly store = inject(ConfiguracionSantaStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly sesion = inject(SesionStore);

  readonly permisos = PERMISOS;
  readonly ventanas = VENTANAS;
  readonly zooms = ZOOMS;
  readonly clusters = CLUSTERS;
  readonly envios = [1, 2, 3];
  readonly entero = entero;
  readonly decimal = decimal;
  readonly colorCluster = colorCluster;
  readonly etiquetaEstadoPaleta = etiquetaEstadoPaleta;
  readonly etiquetaEstadoRegistro = etiquetaEstadoRegistro;

  readonly ventana = signal<Ventana>('rampas');
  readonly zoom = signal(1);
  readonly paletaElegida = signal<PaletaSimulacion | null>(null);
  readonly asignarAbierto = signal(false);
  readonly leyendaAbierta = signal(false);
  readonly confirmarGuardar = signal(false);
  readonly confirmarReinicio = signal(false);

  readonly puedeModificar = computed(() =>
    this.sesion.tienePermiso(PERMISOS.MODIFICAR_CONFIGURACION_SIMULACION_SANTA),
  );

  readonly bloque = computed(() => BLOQUES.find((b) => b.id === this.ventana()) ?? null);
  readonly envioActual = computed(() => this.store.registro()?.envioActual ?? 0);

  /** Rampas del borrador con la capacidad física que envía el backend. */
  readonly rampasVista = computed<RampaVista[]>(() => {
    const config = this.store.configuracion();
    const borrador = this.store.borrador();
    if (!config || !borrador) {
      return [];
    }
    const capacidad = new Map(config.rampas.map((r) => [r.numero, r.capacidad]));
    return borrador.rampas.map((r) => ({ ...r, capacidad: capacidad.get(r.numero) ?? 0 }));
  });

  readonly pasillosVista = computed<PasilloVista[]>(() => this.store.borrador()?.pasillos ?? []);

  readonly kpis = computed(() => this.store.simulacionVisible()?.kpis ?? null);
  readonly avance = computed(() => {
    const k = this.kpis();
    return k ? porcentaje(k.bultosRecibidos, k.bultosTotal) : 0;
  });
  readonly sinUbicar = computed(() =>
    (this.store.simulacionVisible()?.paletas ?? []).filter((p) => p.ubicacion === null),
  );

  constructor() {
    const pedido = Number(this.route.snapshot.queryParamMap.get('registro'));
    this.store.iniciar(Number.isFinite(pedido) && pedido > 0 ? pedido : null);
  }

  elegirRegistro(valor: string): void {
    const id = Number(valor);
    this.paletaElegida.set(null);
    this.store.seleccionar(id);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { registro: id },
      replaceUrl: true,
    });
  }

  irA(ventana: Ventana): void {
    this.ventana.set(ventana);
  }

  /** Teclas 1 a 5: cambian de ventana (si no se está escribiendo ni hay un modal abierto). */
  atajo(evento: KeyboardEvent): void {
    if (this.asignarAbierto() || evento.ctrlKey || evento.metaKey || evento.altKey) {
      return;
    }
    const objetivo = evento.target as HTMLElement | null;
    if (objetivo?.closest('input, select, textarea, [contenteditable="true"], .ui-modal')) {
      return;
    }
    const indice = Number(evento.key) - 1;
    if (indice >= 0 && indice < VENTANAS.length) {
      this.ventana.set(VENTANAS[indice].id);
    }
  }

  elegirPaleta(paleta: PaletaSimulacion): void {
    this.paletaElegida.set(this.paletaElegida()?.id === paleta.id ? null : paleta);
  }

  textoUbicacion(paleta: PaletaSimulacion): string {
    const u = paleta.ubicacion;
    if (!u) {
      return 'Sin ubicación';
    }
    const lado = u.lado === 'IZQ' ? 'izquierda' : 'derecha';
    return u.tipo === 'RAMPA'
      ? `Rampa ${u.numero} · columna ${lado} · fila ${u.fila}`
      : `Pasillo ${u.numero} · rack ${u.lado === 'IZQ' ? 'izquierdo' : 'derecho'} · fila ${u.fila}`;
  }

  /* ---------- envíos ---------- */

  recibirSiguiente(): void {
    this.store.fijarEnvio(this.envioActual() + 1);
  }

  reiniciar(): void {
    this.confirmarReinicio.set(false);
    this.paletaElegida.set(null);
    this.store.fijarEnvio(0);
  }

  /* ---------- asignación ---------- */

  mover(evento: MovimientoUbicaciones): void {
    this.store.mover(evento.tipo, evento.numeros, evento.cluster);
  }

  cambiarOrden(evento: { cluster: Cluster; orden: OrdenLlenado }): void {
    this.store.fijarOrden(evento.cluster, evento.orden);
  }

  fijarPosiciones(numero: number, valor: string): void {
    this.store.fijarPosiciones(numero, Number(valor));
  }

  fijarOperarios(valor: string): void {
    const b = this.store.borrador();
    if (b) {
      this.store.fijarOperacion(Number(valor), b.bultosHora);
    }
  }

  fijarBultosHora(valor: string): void {
    const b = this.store.borrador();
    if (b) {
      this.store.fijarOperacion(b.operarios, Number(valor));
    }
  }

  guardar(): void {
    if (this.store.perdidaPorGuardar() > 0) {
      this.confirmarGuardar.set(true);
      return;
    }
    this.store.guardar();
  }

  guardarIgual(): void {
    this.confirmarGuardar.set(false);
    this.store.guardar();
  }
}
