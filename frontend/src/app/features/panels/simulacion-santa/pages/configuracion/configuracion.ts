import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { DatePipe, NgTemplateOutlet } from '@angular/common';
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
import {
  FichaAsignacion,
  MatrizAsignacion,
  MovimientoFichas,
} from '../../ui/matriz-asignacion/matriz-asignacion';

type Ventana = 'rampas' | '1-6' | '7-12' | '13-18' | '19-24';
type Pestana = 'clusters' | 'posiciones';

/** Pestañas de Asignar, en orden (las flechas ← → pasan de una a otra). */
const PESTANAS: { id: Pestana; etiqueta: string }[] = [
  { id: 'clusters', etiqueta: 'Clusters' },
  { id: 'posiciones', etiqueta: 'Posiciones por rampa' },
];

/** Ventanas del plano en el orden de los botones (y de las teclas 1 a 5). */
const VENTANAS: { id: Ventana; etiqueta: string }[] = [
  { id: 'rampas', etiqueta: 'Rampas' },
  { id: '1-6', etiqueta: '1–6' },
  { id: '7-12', etiqueta: '7–12' },
  { id: '13-18', etiqueta: '13–18' },
  { id: '19-24', etiqueta: '19–24' },
];

/**
 * Configuración de Santa Anita: una sola página con el plano arriba (alto de una
 * pantalla) y Asignar debajo. Los dos leen el mismo store, así que lo que se
 * arrastra abajo se ve arriba como vista previa. Como el plano tiene su propio
 * scroll, se pasa de una sección a otra con los botones «Asignar» y «Plano».
 */
@Component({
  selector: 'app-santa-configuracion',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    NgTemplateOutlet,
    RouterLink,
    Icon,
    Modal,
    EmptyState,
    HasPermiso,
    AndenRampas,
    BloquePasillos,
    MatrizAsignacion,
  ],
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

  private readonly seccionPlano = viewChild<ElementRef<HTMLElement>>('seccionPlano');
  private readonly seccionAsignar = viewChild<ElementRef<HTMLElement>>('seccionAsignar');

  readonly permisos = PERMISOS;
  readonly ventanas = VENTANAS;
  readonly pestanas = PESTANAS;
  readonly clusters = CLUSTERS;
  readonly envios = [1, 2, 3];
  readonly entero = entero;
  readonly decimal = decimal;
  readonly colorCluster = colorCluster;
  readonly etiquetaEstadoPaleta = etiquetaEstadoPaleta;
  readonly etiquetaEstadoRegistro = etiquetaEstadoRegistro;

  readonly ventana = signal<Ventana>('rampas');
  readonly paletaElegida = signal<PaletaSimulacion | null>(null);
  readonly leyendaAbierta = signal(false);
  readonly confirmarGuardar = signal(false);
  readonly confirmarReinicio = signal(false);
  readonly confirmarBloqueo = signal(false);
  /** Lista de paletas sin ubicación desplegada (la abre el aviso del plano). */
  readonly faltanAbierto = signal(false);
  /** Pestaña, búsqueda y filtro de Asignar. */
  readonly pestana = signal<Pestana>('clusters');
  readonly busqueda = signal('');
  readonly soloCambios = signal(false);

  readonly puedeModificar = computed(() =>
    this.sesion.tienePermiso(PERMISOS.MODIFICAR_CONFIGURACION_SIMULACION_SANTA),
  );
  /**
   * Toda la configuración de Asignar (operarios, productividad, clusters,
   * posiciones y orden de llenado) se edita con permiso y sin candado.
   */
  readonly configEditable = computed(() => this.puedeModificar() && !this.store.bloqueado());

  /** Registros que reciben la configuración al bloquear (los que aún no reciben envíos). */
  readonly pendientes = computed(() => this.store.registros().filter((r) => r.estado === 'PENDIENTE').length);

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

  /** Paletas ubicadas en cada rampa / pasillo según la simulación visible. */
  private readonly ocupacion = computed(() => {
    const conteo = new Map<string, number>();
    for (const p of this.store.simulacionVisible()?.paletas ?? []) {
      if (p.ubicacion) {
        const clave = `${p.ubicacion.tipo === 'RAMPA' ? 'R' : 'P'}${p.ubicacion.numero}`;
        conteo.set(clave, (conteo.get(clave) ?? 0) + 1);
      }
    }
    return conteo;
  });

  /** Fichas de la matriz de asignación: cluster del borrador vs el guardado. */
  readonly fichas = computed<FichaAsignacion[]>(() => {
    const config = this.store.configuracion();
    if (!config) {
      return [];
    }
    const guardadoR = new Map(config.rampas.map((r) => [r.numero, r.cluster]));
    const guardadoP = new Map(config.pasillos.map((p) => [p.numero, p.cluster]));
    const ocupacion = this.ocupacion();
    return [
      ...this.rampasVista().map((r) => ({
        clave: `R${r.numero}`,
        tipo: 'R' as const,
        numero: r.numero,
        cluster: r.cluster,
        posiciones: r.posiciones,
        ocupadas: ocupacion.get(`R${r.numero}`) ?? 0,
        cambiada: guardadoR.get(r.numero) !== r.cluster,
      })),
      ...this.pasillosVista().map((p) => ({
        clave: `P${p.numero}`,
        tipo: 'P' as const,
        numero: p.numero,
        cluster: p.cluster,
        posiciones: 24,
        ocupadas: ocupacion.get(`P${p.numero}`) ?? 0,
        cambiada: guardadoP.get(p.numero) !== p.cluster,
      })),
    ];
  });

  readonly cambiosDeCluster = computed(() => this.fichas().filter((f) => f.cambiada).length);

  /**
   * Regla U2: los operarios trabajan solo en rampas, así que un cluster con
   * paletas y sin ninguna rampa no tiene quién lo atienda.
   */
  readonly clustersSinRampa = computed(() => {
    const conRampa = new Set(this.rampasVista().map((r) => r.cluster));
    return (this.store.simulacionVisible()?.clusters ?? [])
      .filter((c) => c.paletas > 0 && !conRampa.has(c.cluster))
      .map((c) => c.cluster);
  });

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

  /* ---------- navegación entre secciones ---------- */

  irAAsignar(abrirFaltan = false): void {
    if (abrirFaltan) {
      this.faltanAbierto.set(true);
    }
    this.desplazar(this.seccionAsignar());
  }

  irAlPlano(): void {
    this.desplazar(this.seccionPlano());
  }

  private desplazar(seccion: ElementRef<HTMLElement> | undefined): void {
    const suave = !matchMedia('(prefers-reduced-motion: reduce)').matches;
    seccion?.nativeElement.scrollIntoView({ behavior: suave ? 'smooth' : 'auto', block: 'start' });
  }

  /** El plano se ve en pantalla (las teclas 1 a 5 solo actúan entonces). */
  private planoEnPantalla(): boolean {
    const r = this.seccionPlano()?.nativeElement.getBoundingClientRect();
    return !!r && r.bottom > window.innerHeight * 0.35 && r.top < window.innerHeight * 0.65;
  }

  /** Teclas 1 a 5 en el plano: cambian de ventana (si no se está escribiendo). */
  atajo(evento: KeyboardEvent): void {
    if (evento.ctrlKey || evento.metaKey || evento.altKey) {
      return;
    }
    const objetivo = evento.target as HTMLElement | null;
    if (objetivo?.closest('input, select, textarea, [contenteditable="true"], .ui-modal')) {
      return;
    }
    const indice = Number(evento.key) - 1;
    if (indice >= 0 && indice < VENTANAS.length && this.planoEnPantalla()) {
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

  moverFichas(m: MovimientoFichas): void {
    const rampas = m.claves.filter((c) => c.startsWith('R')).map((c) => Number(c.slice(1)));
    const pasillos = m.claves.filter((c) => c.startsWith('P')).map((c) => Number(c.slice(1)));
    if (rampas.length) {
      this.store.mover('RAMPA', rampas, m.cluster);
    }
    if (pasillos.length) {
      this.store.mover('PASILLO', pasillos, m.cluster);
    }
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

  /* ---------- pestañas y candado ---------- */

  /** ← → Inicio Fin entre pestañas (patrón de pestañas accesibles). */
  alTecladoPestanas(evento: KeyboardEvent): void {
    const actual = PESTANAS.findIndex((p) => p.id === this.pestana());
    const destino =
      evento.key === 'ArrowRight' ? (actual + 1) % PESTANAS.length
      : evento.key === 'ArrowLeft' ? (actual - 1 + PESTANAS.length) % PESTANAS.length
      : evento.key === 'Home' ? 0
      : evento.key === 'End' ? PESTANAS.length - 1
      : -1;
    if (destino < 0) {
      return;
    }
    evento.preventDefault();
    this.pestana.set(PESTANAS[destino].id);
    const lista = (evento.currentTarget as HTMLElement).querySelectorAll<HTMLElement>('[role="tab"]');
    lista[destino]?.focus();
  }

  /** Bloquear pide confirmación (cambia otros registros); desbloquear es directo. */
  pulsarCandado(): void {
    if (this.store.bloqueado()) {
      this.store.alternarBloqueo();
    } else {
      this.confirmarBloqueo.set(true);
    }
  }

  bloquear(): void {
    this.confirmarBloqueo.set(false);
    this.soloCambios.set(false);
    this.store.alternarBloqueo();
  }

  restablecer(): void {
    this.store.descartar();
    this.soloCambios.set(false);
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
