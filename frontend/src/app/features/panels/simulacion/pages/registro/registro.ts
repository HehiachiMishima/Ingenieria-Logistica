import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { Observable, finalize } from 'rxjs';
import { PERMISOS } from '../../../../../core/config/permisos';
import { Notificaciones } from '../../../../../core/services/notificaciones';
import { SesionStore } from '../../../../../core/services/sesion';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { DiccionarioApi } from '../../data/diccionario.api';
import {
  CambioRutas,
  ClusterDiccionario,
  Diccionario,
  FrecuenciaDiccionario,
  LocalDiccionario,
  RutaDiccionario,
  TEXTO_FRECUENCIA,
  TipoRutaDiccionario,
  UbicacionRuta,
  nombreRuta,
} from '../../data/diccionario.model';
import { EnteroPipe } from '../../numero.pipe';
import { colorCluster, coloresGrupos, compararNombre, formatearEntero } from '../../simulacion-formato';
import { ArrastreRutas, DestinoRuta } from '../configuracion/arrastre-rutas.directive';
import { PosicionFicha, RutaFicha } from './ruta-ficha/ruta-ficha';
import { RutaForm, RutaFormInicial } from './ruta-form/ruta-form';

/** Bloque de cada ruta en el arrastre: 0 = Datos (los clusters tienen id desde 1). */
const DATOS = 0;
/** Tope de bloques de cluster (igual que el servidor). */
const MAX_CLUSTERS = 30;
/** Espera tras la última tecla antes de filtrar. */
const ESPERA_BUSQUEDA_MS = 150;
/** Ficha flotante: ancho (igual que el SCSS de la ficha de ruta) y margen con la ventana. */
const ANCHO_FICHA = 420;
const MARGEN_FICHA = 12;
/** Mac: Ctrl+clic es el clic derecho, así que la selección usa ⌘ (y Ctrl llega como menú). */
const ES_MAC = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

/** Lo que dibuja cada ficha de ruta (la misma de dos filas de Configuración). */
interface FichaVista {
  ruta: RutaDiccionario;
  bultos: string;
  frecuencia: { texto: string; tipo: 'LXV' | 'MJS' | 'D' | null };
  grupoColor: string | null;
  titulo: string;
}

/** Datos o un cluster, con las fichas que pasan los filtros. */
interface BloqueVista {
  /** 0 = Datos; si no, el id del cluster. */
  clave: number;
  clusterId: number | null;
  nombre: string;
  color: string | null;
  fichas: FichaVista[];
  /** Rutas del bloque sin filtrar. */
  total: number;
  /** Rutas del cluster a las que les falta tipo, frecuencia o grupo (no aparecen en Configuración). */
  incompletas: number;
  /** Bultos de las que se ven. */
  bultos: number;
}

/** Último movimiento, para deshacerlo: cómo estaban las rutas antes. */
interface UltimoMovimiento {
  texto: string;
  antes: UbicacionRuta[];
}

/**
 * Registro v2 de Simulación Titan: diccionario de rutas. Las rutas se crean con uno o varios
 * códigos (locales) y nacen en Datos; se ordenan arrastrándolas a los bloques de cluster (la misma
 * ficha de Configuración). Con una o varias seleccionadas, la barra de acciones les asigna
 * frecuencia, grupo o tipo. Volver a Datos les quita cluster, frecuencia y grupo. Módulo
 * independiente: no lee ni cambia el registro por Excel.
 */
@Component({
  selector: 'app-simulacion-registro',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, NgTemplateOutlet, EnteroPipe, ArrastreRutas, RutaFicha, RutaForm],
  templateUrl: './registro.html',
  styleUrls: ['../configuracion/configuracion-ficha.scss', './registro.scss'],
})
export class SimulacionRegistro {
  private readonly api = inject(DiccionarioApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly sesion = inject(SesionStore);

  /** Permisos del registro: crear rutas, cambiarlas (arrastrar, acciones, clusters) y eliminarlas. */
  readonly puedeCrear = computed(() => this.sesion.permisos().includes(PERMISOS.CREAR_REGISTRO_SIMULACION));
  readonly puedeModificar = computed(() => this.sesion.permisos().includes(PERMISOS.MODIFICAR_REGISTRO_SIMULACION));
  readonly puedeEliminar = computed(() => this.sesion.permisos().includes(PERMISOS.ELIMINAR_REGISTRO_SIMULACION));

  readonly frecuencias: readonly FrecuenciaDiccionario[] = ['LXV', 'MJS', 'DIARIA'];
  readonly textoFrecuencia = TEXTO_FRECUENCIA;
  readonly maxClusters = MAX_CLUSTERS;

  // ---------------- Estado del diccionario ----------------

  readonly cargando = signal(true);
  readonly errorCarga = signal<string | null>(null);
  readonly clusters = signal<ClusterDiccionario[]>([]);
  readonly rutas = signal<RutaDiccionario[]>([]);
  readonly truncado = signal(false);
  /** Rutas con un cambio en vuelo (su ficha se atenúa y no se arrastra). */
  readonly guardando = signal<ReadonlySet<number>>(new Set());
  readonly cambiandoClusters = signal(false);
  readonly ultimo = signal<UltimoMovimiento | null>(null);
  readonly deshaciendo = signal(false);

  readonly porId = computed(() => new Map(this.rutas().map((r) => [r.id, r])));

  /** Clusters cerrados en Configuración: no se muestran (ni sus rutas) hasta que se reabran. */
  readonly cerrados = computed(() => this.clusters().filter((c) => c.cerrado));
  private readonly idsCerrados = computed(() => new Set(this.cerrados().map((c) => c.id)));

  /** «CL2 está cerrado en Configuración: vuelve a mostrarse aquí cuando lo reabras.» */
  readonly avisoCerrados = computed(() => {
    const nombres = this.cerrados().map((c) => c.nombre);
    if (nombres.length === 0) {
      return null;
    }
    const uno = nombres.length === 1;
    const lista = uno ? nombres[0] : `${nombres.slice(0, -1).join(', ')} y ${nombres.at(-1)}`;
    return uno
      ? `${lista} está cerrado en Configuración: vuelve a mostrarse aquí cuando lo reabras.`
      : `${lista} están cerrados en Configuración: vuelven a mostrarse aquí cuando los reabras.`;
  });

  constructor() {
    this.cargar();
    inject(DestroyRef).onDestroy(() => clearTimeout(this.esperaBusqueda));
  }

  cargar(): void {
    this.cargando.set(true);
    this.errorCarga.set(null);
    this.api
      .ver()
      .pipe(finalize(() => this.cargando.set(false)))
      .subscribe({
        next: (d) => this.aplicarDiccionario(d),
        error: () => this.errorCarga.set('No se pudo cargar el diccionario de rutas.'),
      });
  }

  private aplicarDiccionario(d: Diccionario): void {
    this.clusters.set(d.clusters);
    this.rutas.set(d.rutas);
    this.truncado.set(d.truncado);
  }

  // ---------------- Filtros ----------------

  /** Búsqueda ya aplicada (número de ruta o código de local). */
  readonly busqueda = signal('');
  readonly filtroTipo = signal<'' | TipoRutaDiccionario | 'SIN'>('');
  readonly filtroFrecuencia = signal<'' | FrecuenciaDiccionario | 'SIN'>('');
  readonly filtroGrupo = signal<string>('');
  private esperaBusqueda: ReturnType<typeof setTimeout> | undefined;

  readonly filtrando = computed(
    () => this.busqueda().trim() !== '' || !!this.filtroTipo() || !!this.filtroFrecuencia() || !!this.filtroGrupo(),
  );

  /** Grupos en uso (para el filtro), en orden natural. */
  readonly grupos = computed(() =>
    [...new Set(this.rutas().flatMap((r) => (r.grupo === null ? [] : [r.grupo])))].sort(compararNombre),
  );

  buscar(evento: Event): void {
    const valor = (evento.target as HTMLInputElement).value;
    clearTimeout(this.esperaBusqueda);
    this.esperaBusqueda = setTimeout(() => this.busqueda.set(valor), ESPERA_BUSQUEDA_MS);
  }

  elegirTipo(evento: Event): void {
    this.filtroTipo.set((evento.target as HTMLSelectElement).value as '' | TipoRutaDiccionario | 'SIN');
  }

  elegirFrecuencia(evento: Event): void {
    this.filtroFrecuencia.set((evento.target as HTMLSelectElement).value as '' | FrecuenciaDiccionario | 'SIN');
  }

  elegirGrupo(evento: Event): void {
    this.filtroGrupo.set((evento.target as HTMLSelectElement).value);
  }

  limpiarFiltros(): void {
    clearTimeout(this.esperaBusqueda);
    this.busqueda.set('');
    this.filtroTipo.set('');
    this.filtroFrecuencia.set('');
    this.filtroGrupo.set('');
  }

  private readonly visibles = computed(() => {
    const texto = this.busqueda().trim().toUpperCase();
    const tipo = this.filtroTipo();
    const frecuencia = this.filtroFrecuencia();
    const grupo = this.filtroGrupo();
    const cerrados = this.idsCerrados();
    return this.rutas().filter(
      (r) =>
        // Las de un cluster cerrado en Configuración no se muestran hasta que se reabra.
        (r.clusterId === null || !cerrados.has(r.clusterId)) &&
        (texto === '' || r.codigoRuta.includes(texto) || r.codigos.some((c) => c.includes(texto))) &&
        (tipo === '' || (tipo === 'SIN' ? r.tipo === null : r.tipo === tipo)) &&
        (frecuencia === '' || (frecuencia === 'SIN' ? r.frecuencia === null : r.frecuencia === frecuencia)) &&
        (grupo === '' || (grupo === 'SIN' ? r.grupo === null : r.grupo === grupo)),
    );
  });

  // ---------------- Bloques ----------------

  /** Datos primero y luego cada cluster, con sus fichas por número de ruta. */
  readonly bloques = computed<BloqueVista[]>(() => {
    const totales = new Map<number, number>();
    const incompletas = new Map<number, number>();
    const gruposPorCluster = new Map<number, Set<string>>();
    for (const r of this.rutas()) {
      const clave = r.clusterId ?? DATOS;
      totales.set(clave, (totales.get(clave) ?? 0) + 1);
      // Le falta tipo, frecuencia o grupo: aún no aparece en Configuración.
      if (r.clusterId !== null && (r.tipo === null || r.frecuencia === null || r.grupo === null)) {
        incompletas.set(clave, (incompletas.get(clave) ?? 0) + 1);
      }
      if (r.clusterId !== null && r.grupo !== null) {
        const grupos = gruposPorCluster.get(r.clusterId) ?? new Set<string>();
        grupos.add(r.grupo);
        gruposPorCluster.set(r.clusterId, grupos);
      }
    }
    // El color de cada grupo sale de todas las rutas de su cluster: filtrar no lo cambia.
    const colores = new Map<number, Map<string, string>>();
    for (const [clusterId, grupos] of gruposPorCluster) {
      colores.set(clusterId, coloresGrupos([...grupos]));
    }
    const porBloque = new Map<number, RutaDiccionario[]>();
    for (const r of this.visibles()) {
      const clave = r.clusterId ?? DATOS;
      const lista = porBloque.get(clave);
      if (lista) {
        lista.push(r);
      } else {
        porBloque.set(clave, [r]);
      }
    }
    const vista = (r: RutaDiccionario): FichaVista => ({
      ruta: r,
      bultos: formatearEntero(r.bultos),
      frecuencia:
        r.frecuencia === null
          ? { texto: '—', tipo: null }
          : { texto: TEXTO_FRECUENCIA[r.frecuencia], tipo: r.frecuencia === 'DIARIA' ? 'D' : r.frecuencia },
      grupoColor: r.clusterId !== null && r.grupo !== null ? (colores.get(r.clusterId)?.get(r.grupo) ?? null) : null,
      titulo:
        `Ruta ${nombreRuta(r)} · ${r.locales} ${r.locales === 1 ? 'local' : 'locales'}` +
        (r.frecuencia ? ` · ${TEXTO_FRECUENCIA[r.frecuencia]}` : '') +
        (r.grupo ? ` · grupo ${r.grupo}` : ''),
    });
    const orden = (a: RutaDiccionario, b: RutaDiccionario) =>
      compararNombre(a.codigoRuta, b.codigoRuta) || (a.tipo ?? '').localeCompare(b.tipo ?? '');
    const bloque = (clave: number, nombre: string, color: string | null): BloqueVista => {
      const rutas = (porBloque.get(clave) ?? []).sort(orden);
      return {
        clave,
        clusterId: clave === DATOS ? null : clave,
        nombre,
        color,
        fichas: rutas.map(vista),
        total: totales.get(clave) ?? 0,
        incompletas: incompletas.get(clave) ?? 0,
        bultos: rutas.reduce((s, r) => s + Number(r.bultos), 0),
      };
    };
    return [
      bloque(DATOS, 'Datos', null),
      ...this.clusters()
        .filter((c) => !c.cerrado)
        .map((c) => bloque(c.id, c.nombre, colorCluster(c.orden - 1))),
    ];
  });

  nombreBloque(clusterId: number | null): string {
    return clusterId === null ? 'Datos' : (this.clusters().find((c) => c.id === clusterId)?.nombre ?? 'su cluster');
  }

  colorBloque(clusterId: number | null): string | null {
    const cluster = this.clusters().find((c) => c.id === clusterId);
    return cluster ? colorCluster(cluster.orden - 1) : null;
  }

  // ---------------- Clusters ----------------

  cambiarClusters(delta: number): void {
    if (!this.puedeModificar()) {
      return;
    }
    const actual = this.clusters().length;
    const cantidad = actual + delta;
    if (cantidad < 0 || cantidad > MAX_CLUSTERS || this.cambiandoClusters()) {
      return;
    }
    if (delta < 0) {
      const ultimo = this.clusters()[actual - 1];
      const enUltimo = this.rutas().filter((r) => r.clusterId === ultimo.id).length;
      if (
        enUltimo > 0 &&
        !window.confirm(
          `${ultimo.nombre} tiene ${cantidadRutas(enUltimo)}: volverán a Datos sin tipo, frecuencia ni grupo. ¿Quitar ${ultimo.nombre}?`,
        )
      ) {
        return;
      }
    }
    this.cambiandoClusters.set(true);
    this.api
      .cambiarClusters(cantidad)
      .pipe(finalize(() => this.cambiandoClusters.set(false)))
      .subscribe((d) => {
        this.aplicarDiccionario(d);
        // Deshacer podría apuntar a un cluster que ya no existe.
        this.ultimo.set(null);
        this.detalle.set(null);
      });
  }

  // ---------------- Selección ----------------

  /** Modo para elegir tocando (celular y tablet, donde no hay Ctrl ni Mayús). */
  readonly modoSeleccion = signal(false);
  private readonly seleccionElegida = signal<ReadonlySet<number>>(new Set());
  private anclaSeleccion: number | null = null;

  /** Cada ruta visible con las de su bloque, en el orden en que se ven (Mayús+clic elige un tramo). */
  private readonly ordenBloques = computed(() => {
    const m = new Map<number, readonly number[]>();
    for (const b of this.bloques()) {
      const ids = b.fichas.map((f) => f.ruta.id);
      for (const id of ids) {
        m.set(id, ids);
      }
    }
    return m;
  });

  /** La selección vigente: sin las rutas que ya no se ven. */
  readonly seleccion = computed<ReadonlySet<number>>(() => {
    const elegidas = this.seleccionElegida();
    const visibles = this.ordenBloques();
    const vigentes = [...elegidas].filter((id) => visibles.has(id));
    return vigentes.length === elegidas.size ? elegidas : new Set(vigentes);
  });

  /** Lo que muestra la barra de acciones. */
  readonly resumen = computed(() => {
    const ids = this.seleccion();
    if (ids.size === 0) {
      return null;
    }
    const porId = this.porId();
    let bultos = 0;
    let enDatos = 0;
    const clusters = new Set<number>();
    for (const id of ids) {
      const r = porId.get(id);
      if (r) {
        bultos += Number(r.bultos);
        if (r.clusterId === null) {
          enDatos++;
        } else {
          clusters.add(r.clusterId);
        }
      }
    }
    // Grupos que ya usan esos clusters: se asignan con un clic.
    const sugeridos = new Set<string>();
    for (const r of this.rutas()) {
      if (r.grupo !== null && r.clusterId !== null && clusters.has(r.clusterId)) {
        sugeridos.add(r.grupo);
      }
    }
    return {
      rutas: ids.size,
      bultos,
      enDatos,
      todasEnDatos: enDatos === ids.size,
      grupos: [...sugeridos].sort(compararNombre).slice(0, 8),
    };
  });

  /**
   * Clic en una ruta: solo, abre su ficha; con Ctrl/⌘ (o en modo selección) la suma o la quita
   * de la selección; con Mayús elige el tramo desde la última pulsada (en el mismo bloque).
   */
  clicRuta(evento: Event, ruta: RutaDiccionario): void {
    const teclas = evento as MouseEvent;
    // Sin permiso para cambiar rutas no hay selección: el clic solo abre la ficha.
    if (!this.puedeModificar()) {
      this.abrirDetalle(evento, ruta);
      return;
    }
    if (ES_MAC && teclas.ctrlKey && !teclas.metaKey) {
      evento.preventDefault();
      return;
    }
    if (!teclas.ctrlKey && !teclas.metaKey && !teclas.shiftKey && !this.modoSeleccion()) {
      this.abrirDetalle(evento, ruta);
      return;
    }
    evento.preventDefault();
    this.alternarSeleccion(ruta.id, teclas.shiftKey);
  }

  /** Mayús+clic no debe seleccionar texto; sin Mayús no se cancela (impediría arrastrar). */
  presionarRuta(evento: MouseEvent): void {
    if (evento.shiftKey) {
      evento.preventDefault();
    }
  }

  /** Ctrl+clic en Mac llega como menú contextual: también suma o quita la ruta. */
  menuRuta(evento: MouseEvent, ruta: RutaDiccionario): void {
    if (ES_MAC && evento.ctrlKey && evento.button === 0) {
      evento.preventDefault();
      this.alternarSeleccion(ruta.id, evento.shiftKey);
    }
  }

  private alternarSeleccion(rutaId: number, tramo: boolean): void {
    const columnas = this.ordenBloques();
    const columna = columnas.get(rutaId);
    if (!columna) {
      return;
    }
    this.detalle.set(null);
    const nueva = new Set(this.seleccion());
    const ancla = this.anclaSeleccion;
    if (tramo && ancla !== null && columnas.get(ancla) === columna) {
      const desde = columna.indexOf(ancla);
      const hasta = columna.indexOf(rutaId);
      for (const id of columna.slice(Math.min(desde, hasta), Math.max(desde, hasta) + 1)) {
        nueva.add(id);
      }
    } else if (!nueva.delete(rutaId)) {
      nueva.add(rutaId);
    }
    this.anclaSeleccion = rutaId;
    this.seleccionElegida.set(nueva);
  }

  /** Elige (o suelta, si ya estaban todas) las rutas visibles de un bloque. */
  elegirBloque(bloque: BloqueVista): void {
    const ids = bloque.fichas.map((f) => f.ruta.id);
    const actual = this.seleccion();
    const todas = ids.length > 0 && ids.every((id) => actual.has(id));
    const nueva = new Set(actual);
    for (const id of ids) {
      if (todas) {
        nueva.delete(id);
      } else {
        nueva.add(id);
      }
    }
    this.detalle.set(null);
    this.seleccionElegida.set(nueva);
  }

  bloqueElegido(bloque: BloqueVista): boolean {
    const actual = this.seleccion();
    return bloque.fichas.length > 0 && bloque.fichas.every((f) => actual.has(f.ruta.id));
  }

  alternarModoSeleccion(): void {
    this.modoSeleccion.update((v) => !v);
    this.detalle.set(null);
  }

  limpiarSeleccion(): void {
    this.seleccionElegida.set(new Set());
    this.anclaSeleccion = null;
  }

  // ---------------- Arrastre ----------------

  /** Al tomar una ruta elegida viaja toda la selección; si no, solo ella. */
  readonly grupoArrastre = (rutaId: number): readonly number[] => {
    const seleccion = this.seleccion();
    return seleccion.size > 1 && seleccion.has(rutaId) ? [...seleccion] : [rutaId];
  };

  /**
   * Cualquier cluster acepta rutas (soltarlas en el suyo no hace nada). Datos no acepta dos rutas
   * con el mismo número: ahí quedan sin tipo (el servidor lo vuelve a comprobar).
   */
  readonly validarDestino = (rutaIds: readonly number[], clusterId: number): string | null => {
    if (clusterId !== DATOS) {
      return null;
    }
    const porId = this.porId();
    const llegan = rutaIds.map((id) => porId.get(id)).filter((r): r is RutaDiccionario => !!r && r.clusterId !== null);
    const moviendo = new Set(rutaIds);
    const enDatos = new Set(
      this.rutas()
        .filter((r) => r.clusterId === null && !moviendo.has(r.id))
        .map((r) => r.codigoRuta),
    );
    const vistas = new Set<string>();
    const repetidas = new Set<string>();
    for (const r of llegan) {
      if (enDatos.has(r.codigoRuta) || vistas.has(r.codigoRuta)) {
        repetidas.add(r.codigoRuta);
      }
      vistas.add(r.codigoRuta);
    }
    if (repetidas.size === 0) {
      return null;
    }
    const lista = [...repetidas].sort(compararNombre);
    return `En Datos quedarían repetidas sin tipo: ${lista.slice(0, 5).join(', ')}${lista.length > 5 ? ` y ${lista.length - 5} más` : ''}.`;
  };

  soltar(destino: DestinoRuta): void {
    if (destino.motivo) {
      this.notificaciones.advertencia('No se pudo mover', destino.motivo);
      return;
    }
    const clusterId = destino.clusterId === DATOS ? null : destino.clusterId;
    const porId = this.porId();
    const enVuelo = this.guardando();
    const ids = destino.rutaIds.filter((id) => {
      const r = porId.get(id);
      return !!r && r.clusterId !== clusterId && !enVuelo.has(id);
    });
    if (ids.length === 0) {
      return;
    }
    const antes = ids.map((id) => ubicacion(porId.get(id)!));
    // Se ve al instante; si el servidor lo rechaza, vuelven a donde estaban.
    this.reubicar(ids.map((id) => ({ id, clusterId, tipo: null, frecuencia: null, grupo: null })), clusterId !== null);
    const destinoTexto = this.nombreBloque(clusterId);
    this.aplicar(
      ids,
      this.api.mover(ids, clusterId),
      () => {
        this.ultimo.set({ texto: `${cantidadRutas(ids.length)} a ${destinoTexto}`, antes });
        return null;
      },
      () => this.reubicar(antes, false),
    );
  }

  /** Cambia cluster (y, si van a Datos o se restaura, frecuencia y grupo) en la vista. */
  private reubicar(ubicaciones: readonly UbicacionRuta[], conservar: boolean): void {
    const nuevas = new Map(ubicaciones.map((u) => [u.id, u]));
    this.rutas.update((lista) =>
      lista.map((r) => {
        const u = nuevas.get(r.id);
        if (!u) {
          return r;
        }
        // En Datos no hay tipo, frecuencia ni grupo; entre clusters se conservan.
        const enCluster = u.clusterId !== null;
        return {
          ...r,
          clusterId: u.clusterId,
          tipo: enCluster ? (conservar ? r.tipo : u.tipo) : null,
          frecuencia: enCluster ? (conservar ? r.frecuencia : u.frecuencia) : null,
          grupo: enCluster ? (conservar ? r.grupo : u.grupo) : null,
        };
      }),
    );
  }

  deshacer(): void {
    const ultimo = this.ultimo();
    if (!ultimo || this.deshaciendo()) {
      return;
    }
    this.deshaciendo.set(true);
    const ids = ultimo.antes.map((u) => u.id);
    this.aplicar(
      ids,
      this.api.restaurar(ultimo.antes).pipe(finalize(() => this.deshaciendo.set(false))),
      () => {
        this.ultimo.set(null);
        return `Volvieron como estaban: ${cantidadRutas(ids.length)}.`;
      },
    );
  }

  cerrarDeshacer(): void {
    this.ultimo.set(null);
  }

  // ---------------- Acciones sobre la selección ----------------

  /** Grupo escrito en la barra de acciones. */
  readonly grupoEscrito = signal('');

  asignarFrecuencia(frecuencia: FrecuenciaDiccionario): void {
    const ids = [...this.seleccion()];
    this.aplicar(ids, this.api.asignarFrecuencia(ids, frecuencia), (c) => {
      this.olvidarMovimiento();
      return `Frecuencia ${TEXTO_FRECUENCIA[frecuencia]} asignada a ${cantidadRutas(c.rutas.length)}.` + omitidas(c, 'frecuencia');
    });
  }

  /**
   * Tras otro cambio ya no se ofrece deshacer el movimiento: devolvería también la frecuencia,
   * el grupo o el tipo de antes y se perdería lo recién asignado.
   */
  private olvidarMovimiento(): void {
    this.ultimo.set(null);
  }

  asignarGrupo(valor?: string): void {
    const grupo = (valor ?? this.grupoEscrito()).trim();
    if (grupo === '') {
      this.notificaciones.advertencia('Falta el grupo', 'Escribe el grupo (por ejemplo «1») o elige uno de la lista.');
      return;
    }
    const ids = [...this.seleccion()];
    this.aplicar(ids, this.api.asignarGrupo(ids, grupo), (c) => {
      this.grupoEscrito.set('');
      this.olvidarMovimiento();
      return `Grupo ${grupo.toUpperCase()} asignado a ${cantidadRutas(c.rutas.length)}.` + omitidas(c, 'grupo');
    });
  }

  cambiarTipo(tipo: TipoRutaDiccionario): void {
    const ids = [...this.seleccion()];
    this.aplicar(ids, this.api.cambiarTipo(ids, tipo), (c) => {
      this.olvidarMovimiento();
      return `Tipo ${tipo} asignado a ${cantidadRutas(c.rutas.length)}.` + omitidas(c, 'tipo');
    });
  }

  escribirGrupo(evento: Event): void {
    this.grupoEscrito.set((evento.target as HTMLInputElement).value);
  }

  /**
   * Manda un cambio de varias rutas: sus fichas quedan «guardando» hasta la respuesta, que trae
   * cómo quedaron. Los errores los muestra el interceptor con el mensaje del servidor.
   */
  private aplicar(
    ids: readonly number[],
    peticion: Observable<CambioRutas>,
    exito: (cambio: CambioRutas) => string | null,
    alFallar?: () => void,
  ): void {
    if (ids.length === 0) {
      return;
    }
    this.marcarGuardando(ids, true);
    peticion.pipe(finalize(() => this.marcarGuardando(ids, false))).subscribe({
      next: (cambio) => {
        this.fusionar(cambio.rutas);
        const mensaje = exito(cambio);
        if (mensaje) {
          this.notificaciones.exito('Listo', mensaje);
        }
      },
      error: () => alFallar?.(),
    });
  }

  private marcarGuardando(ids: readonly number[], si: boolean): void {
    this.guardando.update((actual) => {
      const nueva = new Set(actual);
      for (const id of ids) {
        if (si) {
          nueva.add(id);
        } else {
          nueva.delete(id);
        }
      }
      return nueva;
    });
  }

  /** Reemplaza esas rutas por como las devolvió el servidor. */
  private fusionar(rutas: readonly RutaDiccionario[]): void {
    if (rutas.length === 0) {
      return;
    }
    const nuevas = new Map(rutas.map((r) => [r.id, r]));
    this.rutas.update((lista) => lista.map((r) => nuevas.get(r.id) ?? r));
  }

  // ---------------- Ficha de detalle ----------------

  readonly detalle = signal<{ id: number; ancla: HTMLElement; posicion: PosicionFicha } | null>(null);

  readonly rutaDetalle = computed(() => {
    const abierto = this.detalle();
    return abierto ? (this.porId().get(abierto.id) ?? null) : null;
  });

  abrirDetalle(evento: Event, ruta: RutaDiccionario): void {
    const ancla = evento.currentTarget as HTMLElement;
    if (this.detalle()?.id === ruta.id) {
      this.detalle.set(null);
      return;
    }
    this.detalle.set({ id: ruta.id, ancla, posicion: posicionFicha(ancla, 560) });
  }

  /**
   * Mientras hay una ficha abierta: se cierra con Escape, al tocar fuera, al desplazar (quedaría
   * lejos de su ruta) y si cambia el ancho de la ventana (no el alto: el teclado del celular).
   */
  private readonly cierreDetalle = effect((alLimpiar) => {
    const abierto = this.detalle();
    if (!abierto) {
      return;
    }
    const dentro = (objetivo: EventTarget | null): boolean => {
      if (!(objetivo instanceof Node)) {
        return false;
      }
      const elemento = objetivo instanceof Element ? objetivo : objetivo.parentElement;
      return abierto.ancla.contains(objetivo) || !!elemento?.closest('.detalle-pop, .ui-modal-backdrop');
    };
    const alTocar = (e: PointerEvent) => {
      if (!dentro(e.target)) {
        this.detalle.set(null);
      }
    };
    const alTeclear = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !this.formulario()) {
        this.detalle.set(null);
        abierto.ancla.focus({ preventScroll: true });
      }
    };
    const alDesplazar = (e: Event) => {
      if (!dentro(e.target)) {
        this.detalle.set(null);
      }
    };
    const anchoAlAbrir = window.innerWidth;
    const alRedimensionar = () => {
      if (window.innerWidth !== anchoAlAbrir) {
        this.detalle.set(null);
      }
    };
    document.addEventListener('pointerdown', alTocar, true);
    document.addEventListener('keydown', alTeclear);
    document.addEventListener('scroll', alDesplazar, { capture: true, passive: true });
    window.addEventListener('resize', alRedimensionar);
    alLimpiar(() => {
      document.removeEventListener('pointerdown', alTocar, true);
      document.removeEventListener('keydown', alTeclear);
      document.removeEventListener('scroll', alDesplazar, true);
      window.removeEventListener('resize', alRedimensionar);
    });
  });

  // ---------------- Crear, editar y eliminar ----------------

  readonly formulario = signal<RutaFormInicial | null>(null);

  nuevaRuta(): void {
    this.detalle.set(null);
    this.formulario.set({ ruta: null, locales: [] });
  }

  editar(datos: { ruta: RutaDiccionario; locales: LocalDiccionario[] }): void {
    this.detalle.set(null);
    this.formulario.set(datos);
  }

  alGuardar(evento: { ruta: RutaDiccionario; creada: boolean }): void {
    const { ruta, creada } = evento;
    if (creada) {
      this.rutas.update((lista) => [...lista, ruta]);
    } else {
      this.fusionar([ruta]);
      this.olvidarMovimiento();
    }
    this.formulario.set(null);
    this.notificaciones.exito(
      creada ? 'Ruta creada' : 'Ruta guardada',
      creada ? `La ruta ${nombreRuta(ruta)} está en Datos.` : `Ruta ${nombreRuta(ruta)}.`,
    );
  }

  eliminar(ruta: RutaDiccionario): void {
    if (!window.confirm(`¿Eliminar la ruta ${nombreRuta(ruta)} y sus locales? No se puede deshacer.`)) {
      return;
    }
    this.marcarGuardando([ruta.id], true);
    this.api
      .eliminar(ruta.id)
      .pipe(finalize(() => this.marcarGuardando([ruta.id], false)))
      .subscribe(() => {
        this.detalle.set(null);
        this.rutas.update((lista) => lista.filter((r) => r.id !== ruta.id));
        const ultimo = this.ultimo();
        if (ultimo?.antes.some((u) => u.id === ruta.id)) {
          this.ultimo.set(null);
        }
        this.notificaciones.exito('Ruta eliminada', `Ruta ${nombreRuta(ruta)}.`);
      });
  }
}

function ubicacion(r: RutaDiccionario): UbicacionRuta {
  return { id: r.id, clusterId: r.clusterId, tipo: r.tipo, frecuencia: r.frecuencia, grupo: r.grupo };
}

function cantidadRutas(n: number): string {
  return n === 1 ? '1 ruta' : `${formatearEntero(n)} rutas`;
}

function omitidas(c: CambioRutas, que: string): string {
  if (c.omitidas === 0) {
    return '';
  }
  return ` ${cantidadRutas(c.omitidas)} en Datos ${c.omitidas === 1 ? 'se dejó' : 'se dejaron'} sin ${que}.`;
}

/**
 * Dónde va la ficha: bajo (o sobre) la ruta, o como hoja inferior en pantallas angostas o bajas.
 * Igual que la de Configuración.
 */
function posicionFicha(ancla: HTMLElement, altoEstimado: number): PosicionFicha {
  const caja = ancla.getBoundingClientRect();
  const ancho = window.innerWidth;
  const alto = window.innerHeight;
  const abajo = alto - caja.bottom - MARGEN_FICHA - 6;
  const arriba = caja.top - MARGEN_FICHA - 6;
  const hoja = { hoja: true, left: null, top: null, bottom: null, maxAlto: null };
  if (ancho < 640 || Math.max(abajo, arriba) < 220) {
    return hoja;
  }
  const left = Math.min(
    Math.max(MARGEN_FICHA, caja.left),
    ancho - Math.min(ANCHO_FICHA, ancho - MARGEN_FICHA * 2) - MARGEN_FICHA,
  );
  if (abajo >= altoEstimado || abajo >= arriba) {
    return { hoja: false, left, top: caja.bottom + 6, bottom: null, maxAlto: abajo };
  }
  return { hoja: false, left, top: null, bottom: alto - caja.top + 6, maxAlto: arriba };
}
