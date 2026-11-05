import { HttpClient } from '@angular/common/http';
import { Service, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../../../../environments/environment';
import { mensajeDeError } from '../../../../core/errors/error.util';
import { Api } from '../../../../core/services/api';
import { Notificaciones } from '../../../../core/services/notificaciones';
import { OpcionMultiple } from '../../../../shared/ui/select-multiple/select-multiple';
import {
  CAMPOS,
  CampoFiltro,
  DatosPicking,
  FILTROS_VACIOS,
  Filtros,
  agregar,
  agregarSkus,
  construir,
  filtrar,
  limaPs,
  nomCluster,
  universo,
} from './picking.logica';

/** Estado del archivo (GET /picking/estado): la última revisión del SFTP y el archivo de la carpeta. */
export interface EstadoPicking {
  sincronizacion: 'ACTUALIZADO' | 'SIN_CAMBIOS' | 'SIN_ARCHIVO' | 'ERROR' | 'PENDIENTE' | 'NO_CONFIGURADO';
  mensaje: string;
  revisado: string | null;
  disponible: boolean;
  archivo: string;
  bytes: number | null;
  modificado: string | null;
  huella: string | null;
}

/** El navegador vuelve a preguntar por el archivo cada 5 min (solo el estado; el CSV, si cambió). */
const REVISION_MS = 5 * 60_000;

/**
 * Estado compartido por Resumen, Órdenes y SKU: el archivo ya interpretado,
 * los filtros (se mantienen al cambiar de módulo) y lo calculado con ellos.
 */
@Service()
export class PickingStore {
  private readonly api = inject(Api);
  private readonly http = inject(HttpClient);
  private readonly notificaciones = inject(Notificaciones);
  private readonly url = `${environment.apiUrl.replace(/\/+$/, '')}/picking/archivo`;

  readonly datos = signal<DatosPicking | null>(null);
  /** Carga anterior: los KPI muestran cuánto cambió desde entonces. */
  readonly previo = signal<DatosPicking | null>(null);
  readonly estado = signal<EstadoPicking | null>(null);
  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);
  readonly filtros = signal<Filtros>(FILTROS_VACIOS);
  /** Hora de Lima (escala del archivo); avanza sola para la antigüedad de los datos. */
  readonly ahora = signal(limaPs(Date.now()));

  /** Detalle abierto (modal): una orden o un SKU; `volverSku` vuelve del detalle de orden al SKU. */
  readonly detalle = signal<{ tipo: 'orden' | 'sku'; id: string; volverSku?: string } | null>(null);

  private huella: string | null = null;
  private iniciado = false;

  readonly universo = computed(() => {
    const d = this.datos();
    return d ? universo(d, this.filtros().arrastre) : [];
  });
  readonly filas = computed(() => filtrar(this.universo(), this.filtros()));
  readonly agregado = computed(() => agregar(this.filas()));
  readonly agregadoPrevio = computed(() => {
    const p = this.previo();
    return p ? agregar(filtrar(universo(p, this.filtros().arrastre), this.filtros())) : null;
  });
  readonly skus = computed(() => agregarSkus(this.filas()));

  /** Código de sucursal → nombre (para mostrarlas en filtros y chips). */
  private readonly sucursales = computed(() => {
    const m = new Map<string, string>();
    for (const r of this.universo()) if (r.suc && !m.has(r.suc)) m.set(r.suc, r.sucNom);
    return m;
  });

  /** Valores de cada campo en el día (para el panel de filtros). */
  readonly opciones = computed(() => {
    const valores = Object.fromEntries(CAMPOS.map((c) => [c.k, new Set<string>()])) as Record<
      CampoFiltro,
      Set<string>
    >;
    for (const r of this.universo()) for (const c of CAMPOS) valores[c.k].add(r[c.k]);
    return Object.fromEntries(
      CAMPOS.map((c) => [
        c.k,
        [...valores[c.k]]
          .sort((a, b) => a.localeCompare(b, 'es', { numeric: true }))
          .map<OpcionMultiple>((v) => ({ id: v, etiqueta: this.etiqueta(c.k, v) })),
      ]),
    ) as Record<CampoFiltro, OpcionMultiple[]>;
  });

  readonly filtrosActivos = computed(() => {
    const f = this.filtros();
    return (
      Object.values(f.campos).filter((v) => v?.length).length + (f.busqueda.trim() ? 1 : 0) + (f.horas ? 1 : 0)
    );
  });

  /** Hora del corte de los datos: la del archivo en el WMS o, si no, su último evento. */
  readonly corte = computed(() => {
    const modificado = this.estado()?.modificado;
    return modificado ? limaPs(Date.parse(modificado)) : (this.datos()?.ultimo ?? null);
  });

  /** Lo llama cada módulo; carga una sola vez y deja programada la revisión. */
  iniciar(): void {
    if (this.iniciado) {
      return;
    }
    this.iniciado = true;
    void this.cargar(false);
    setInterval(() => void this.cargar(false), REVISION_MS);
    setInterval(() => this.ahora.set(limaPs(Date.now())), 30_000);
  }

  /** Pregunta por el estado y descarga el archivo solo si cambió su huella. */
  async cargar(manual: boolean): Promise<void> {
    if (this.cargando()) {
      return;
    }
    this.cargando.set(true);
    try {
      const estado = await firstValueFrom(this.api.get<EstadoPicking>('picking/estado'));
      this.estado.set(estado);
      this.ahora.set(limaPs(Date.now()));
      if (!estado.disponible) {
        this.error.set('No hay archivo de picking en la carpeta de datos: revisa la sincronización con el WMS.');
        return;
      }
      if (this.datos() && estado.huella === this.huella) {
        if (manual) {
          this.notificaciones.info('Sin cambios', 'El WMS aún no envía un archivo nuevo.');
        }
        return;
      }
      // El navegador revalida con ETag: si no cambió, el servidor no reenvía el archivo.
      const texto = await firstValueFrom(this.http.get(this.url, { responseType: 'text' }));
      const nuevo = construir(texto);
      if (this.datos()) {
        this.previo.set(this.datos());
        this.notificaciones.info('Datos actualizados', `${nuevo.cartones.length.toLocaleString('es-PE')} cartones`);
      }
      this.datos.set(nuevo);
      this.huella = estado.huella;
      this.error.set(null);
    } catch (err) {
      const mensaje = err instanceof Error && !('status' in err) ? err.message : mensajeDeError(err);
      this.error.set(mensaje);
    } finally {
      this.cargando.set(false);
    }
  }

  // ---------------- Filtros ----------------

  setCampo(campo: CampoFiltro, valores: string[]): void {
    this.filtros.update((f) => ({ ...f, campos: { ...f.campos, [campo]: valores } }));
  }

  /** Clic en una barra o fila: agrega o quita ese valor del filtro. */
  alternar(campo: CampoFiltro, valor: string): void {
    const actuales = this.filtros().campos[campo] ?? [];
    this.setCampo(campo, actuales.includes(valor) ? actuales.filter((v) => v !== valor) : [...actuales, valor]);
  }

  setBusqueda(busqueda: string): void {
    this.filtros.update((f) => ({ ...f, busqueda }));
  }

  setHoras(horas: [number, number] | null): void {
    this.filtros.update((f) => ({ ...f, horas }));
  }

  setArrastre(arrastre: boolean): void {
    this.filtros.update((f) => ({ ...f, arrastre }));
  }

  limpiar(): void {
    this.filtros.update((f) => ({ ...FILTROS_VACIOS, arrastre: f.arrastre }));
  }

  etiqueta(campo: CampoFiltro, valor: string): string {
    if (!valor) return '(vacío)';
    if (campo === 'cluster') return nomCluster(valor);
    if (campo === 'suc') {
      const nombre = this.sucursales().get(valor);
      return nombre ? `${valor} ${nombre}` : valor;
    }
    return valor;
  }
}
