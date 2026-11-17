import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { PERMISOS } from '../../../../../core/config/permisos';
import { DirectorioUsuarios } from '../../../../../core/services/directorio-usuarios';
import { HasPermiso } from '../../../../../shared/directives/has-permiso.directive';
import { ActorLabelPipe } from '../../../../../shared/pipes/actor-label.pipe';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../shared/ui/modal/modal';
import { Paginador } from '../../../../../shared/ui/paginador/paginador';
import { ChipFiltro, FiltrosActivos } from '../../../../../shared/ui/panel-filtros/filtros-activos';
import { PanelFiltros } from '../../../../../shared/ui/panel-filtros/panel-filtros';
import { RegistroSantaStore } from '../../data/registro.store';
import { EstadoRegistroSanta, RegistroSanta } from '../../models/santa.model';
import { avanceAcumulado, duracion, porciento, tablaEnvios } from '../../santa-calculo';
import {
  CLUSTERS,
  ESTADOS_REGISTRO_SANTA,
  REGLAS_EXCEL,
  colorCluster,
  entero,
  etiquetaEstadoRegistro,
  tamanoArchivo,
} from '../../santa-formato';

@Component({
  selector: 'app-santa-registro',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    ActorLabelPipe,
    Icon,
    Modal,
    EmptyState,
    HasPermiso,
    Paginador,
    PanelFiltros,
    FiltrosActivos,
  ],
  providers: [RegistroSantaStore],
  templateUrl: './registro.html',
  styleUrl: './registro.scss',
})
export class SantaRegistro {
  readonly store = inject(RegistroSantaStore);
  private readonly router = inject(Router);
  private readonly inputArchivo = viewChild<ElementRef<HTMLInputElement>>('inputArchivo');

  readonly permisos = PERMISOS;
  readonly estadosFiltro = ESTADOS_REGISTRO_SANTA;
  readonly clusters = CLUSTERS;
  readonly entero = entero;
  readonly colorCluster = colorCluster;
  readonly etiquetaEstado = etiquetaEstadoRegistro;
  readonly tamanoArchivo = tamanoArchivo;
  readonly reglas = REGLAS_EXCEL;
  readonly duracion = duracion;
  readonly porciento = porciento;

  readonly filtrosAbierto = signal(false);

  readonly chips = computed<ChipFiltro[]>(() =>
    this.store.estados().map((estado) => ({
      clave: estado,
      etiqueta: 'Estado',
      valor: etiquetaEstadoRegistro(estado),
      icono: 'power',
    })),
  );

  readonly totalPaginas = computed(() =>
    Math.max(1, Math.ceil(this.store.total() / this.store.porPagina())),
  );
  readonly paginaActual = computed(() =>
    Math.min(Math.max(1, this.store.pagina()), this.totalPaginas()),
  );

  /** Envíos del archivo revisado: bultos, % del total, acumulado y tiempo con los predeterminados. */
  readonly enviosRevision = computed(() => {
    const revision = this.store.revision();
    if (!revision) {
      return [];
    }
    const op = this.store.operacion();
    return tablaEnvios(revision.bultosEnvios, op?.operarios ?? 0, op?.bultosHora ?? 0);
  });

  /** Por cluster: paletas, bultos y avance acumulado (%) al terminar cada envío. */
  readonly clustersRevision = computed(() => {
    const revision = this.store.revision();
    if (!revision) {
      return [];
    }
    return CLUSTERS.map((cluster, i) => {
      const porEnvio = revision.bultosClusterEnvio?.[i] ?? [];
      return {
        cluster,
        paletas: revision.paletasCluster[i],
        bultos: porEnvio.reduce((s, b) => s + b, 0),
        avance: avanceAcumulado(porEnvio),
      };
    });
  });

  /** Errores de la revisión que no se listan (el servidor envía hasta 50). */
  readonly erroresOcultos = computed(() => {
    const revision = this.store.revision();
    return revision ? Math.max(0, revision.totalErrores - revision.errores.length) : 0;
  });

  /* renombrar */
  readonly editando = signal<RegistroSanta | null>(null);
  readonly nombreEdicion = signal('');
  readonly nombreEdicionTocado = signal(false);
  readonly nombreEdicionValido = computed(() => {
    const limpio = this.nombreEdicion().trim();
    return limpio.length > 0 && limpio.length <= 150;
  });

  /* eliminar */
  readonly aEliminar = signal<RegistroSanta | null>(null);

  constructor() {
    inject(DirectorioUsuarios).cargar();
  }

  estaEstado(estado: EstadoRegistroSanta): boolean {
    return this.store.estados().includes(estado);
  }

  quitarFiltro(clave: string): void {
    this.store.alternarEstado(clave as EstadoRegistroSanta);
  }

  abrirSelectorArchivo(): void {
    this.inputArchivo()?.nativeElement.click();
  }

  elegirArchivo(evento: Event): void {
    const input = evento.target as HTMLInputElement;
    const archivo = input.files?.[0];
    input.value = '';
    if (archivo) {
      this.store.elegirArchivo(archivo);
    }
  }

  abrirEditar(registro: RegistroSanta): void {
    this.editando.set(registro);
    this.nombreEdicion.set(registro.nombre);
    this.nombreEdicionTocado.set(false);
  }

  guardarNombre(): void {
    const registro = this.editando();
    this.nombreEdicionTocado.set(true);
    if (!registro || !this.nombreEdicionValido()) {
      return;
    }
    this.store.renombrar(registro, this.nombreEdicion(), () => this.editando.set(null));
  }

  confirmarEliminar(): void {
    const registro = this.aEliminar();
    if (registro) {
      this.store.eliminar(registro, () => this.aEliminar.set(null));
    }
  }

  abrirConfiguracion(registro: RegistroSanta): void {
    void this.router.navigate(['/simulacion-santa/configuracion'], {
      queryParams: { registro: registro.id },
    });
  }
}
