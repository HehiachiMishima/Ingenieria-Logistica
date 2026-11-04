import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { PERMISOS } from '../../../../../../core/config/permisos';
import { DirectorioUsuarios } from '../../../../../../core/services/directorio-usuarios';
import { Notificaciones } from '../../../../../../core/services/notificaciones';
import { EdicionCrud } from '../../../../../../core/utils/edicion-crud';
import { ListadoLocal } from '../../../../../../core/utils/listado-local';
import { OpcionesDependientes } from '../../../../../../core/utils/opciones-dependientes';
import { ColumnasRedimensionables } from '../../../../../../shared/directives/columnas-redimensionables.directive';
import { HasPermiso } from '../../../../../../shared/directives/has-permiso.directive';
import { ActorLabelPipe } from '../../../../../../shared/pipes/actor-label.pipe';
import { EmptyState } from '../../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../../shared/ui/modal/modal';
import { Paginador } from '../../../../../../shared/ui/paginador/paginador';
import { Sistema, TipoIncidencia } from '../../../data/bitacora.model';
import { SistemasApi } from '../../../data/sistemas.api';
import { BitacoraCatalogos } from '../../../state/bitacora-catalogos';

@Component({
  selector: 'app-bitacora-sistemas',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    DatePipe,
    ActorLabelPipe,
    Icon,
    Modal,
    EmptyState,
    HasPermiso,
    ColumnasRedimensionables,
    Paginador,
  ],
  templateUrl: './sistemas.html',
  styleUrl: './sistemas.scss',
})
export class Sistemas {
  private readonly fb = inject(FormBuilder);
  private readonly sistemasApi = inject(SistemasApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly catalogos = inject(BitacoraCatalogos);
  private readonly directorio = inject(DirectorioUsuarios);

  readonly permisos = PERMISOS;

  private readonly listado = new ListadoLocal<Sistema>(
    () => this.sistemasApi.listar(),
    (sistema) => sistema.sistema,
  );
  readonly cargando = this.listado.cargando;
  readonly error = this.listado.error;
  readonly busqueda = this.listado.busqueda;
  readonly filtrados = this.listado.filtrados;
  readonly pagina = this.listado.pagina;
  readonly porPagina = this.listado.porPagina;
  readonly paginaActual = this.listado.paginaActual;
  readonly paginados = this.listado.paginados;

  private readonly edicion = new EdicionCrud<Sistema>();
  readonly formAbierto = this.edicion.formAbierto;
  readonly editando = this.edicion.editando;
  readonly guardando = this.edicion.guardando;
  readonly eliminarAbierto = this.edicion.eliminarAbierto;
  readonly aEliminar = this.edicion.aEliminar;
  readonly eliminando = this.edicion.eliminando;

  readonly formulario = this.fb.nonNullable.group({
    sistema: ['', [Validators.required, Validators.maxLength(50)]],
  });

  readonly tiposAbierto = signal(false);
  readonly sistemaTipos = signal<Sistema | null>(null);
  /** Cada apertura cancela la carga anterior: nunca se ven los hijos de otro elemento. */
  private readonly tipos = new OpcionesDependientes<TipoIncidencia>(
    (id) => this.sistemasApi.tiposDe(id),
    'No se pudieron cargar los tipos',
  );
  readonly tiposLista = this.tipos.opciones;
  readonly cargandoTipos = this.tipos.cargando;

  private readonly edicionTipo = new EdicionCrud<TipoIncidencia>();
  readonly tipoFormAbierto = this.edicionTipo.formAbierto;
  readonly tipoEditando = this.edicionTipo.editando;
  readonly tipoGuardando = this.edicionTipo.guardando;
  readonly tipoEliminarAbierto = this.edicionTipo.eliminarAbierto;
  readonly tipoAEliminar = this.edicionTipo.aEliminar;
  readonly tipoEliminando = this.edicionTipo.eliminando;

  readonly tipoFormulario = this.fb.nonNullable.group({
    incidencia: ['', [Validators.required, Validators.maxLength(50)]],
  });

  constructor() {
    this.directorio.cargar();
    this.cargar();
  }

  cargar(): void {
    this.listado.recargar();
  }

  buscar(valor: string): void {
    this.listado.buscar(valor);
  }

  cambiarPorPagina(valor: number): void {
    this.listado.cambiarPorPagina(valor);
  }

  campoInvalido(campo: 'sistema'): boolean {
    const control = this.formulario.controls[campo];
    return control.touched && control.invalid;
  }

  abrirCrear(): void {
    this.formulario.reset({ sistema: '' });
    this.edicion.abrir(null);
  }

  abrirEditar(sistema: Sistema): void {
    this.formulario.reset({ sistema: sistema.sistema });
    this.edicion.abrir(sistema);
  }

  cerrarForm(): void {
    this.edicion.cerrar();
  }

  guardar(): void {
    if (this.guardando()) {
      return;
    }
    if (this.formulario.invalid) {
      this.formulario.markAllAsTouched();
      return;
    }

    const valores = this.formulario.getRawValue();
    const editando = this.editando();
    const peticion = { sistema: valores.sistema };
    const peticion$ = editando
      ? this.sistemasApi.actualizar(editando.id, peticion)
      : this.sistemasApi.crear(peticion);

    this.edicion.guardar(peticion$, (sistema, editado) => {
      this.catalogos.invalidar();
      this.notificaciones.exito(
        'Guardado',
        `Sistema ${sistema.sistema} ${editado ? 'actualizado' : 'creado'}.`,
      );
      this.cerrarForm();
      this.cargar();
    });
  }

  confirmarEliminar(sistema: Sistema): void {
    this.edicion.confirmarEliminar(sistema);
  }

  eliminar(): void {
    this.edicion.eliminar(
      (sistema) => this.sistemasApi.eliminar(sistema.id),
      (sistema) => {
        this.listado.quitar(sistema.id);
        this.catalogos.invalidar();
        this.notificaciones.exito('Eliminado', `Sistema ${sistema.sistema} eliminado.`);
      },
    );
  }

  abrirTipos(sistema: Sistema): void {
    this.sistemaTipos.set(sistema);
    this.tiposAbierto.set(true);
    this.edicionTipo.cerrar();
    this.cargarTipos();
  }

  private cargarTipos(): void {
    const sistema = this.sistemaTipos();
    if (sistema) {
      this.tipos.elegir(sistema.id);
    }
  }

  abrirTipoCrear(): void {
    this.tipoFormulario.reset({ incidencia: '' });
    this.edicionTipo.abrir(null);
  }

  abrirTipoEditar(tipo: TipoIncidencia): void {
    this.tipoFormulario.reset({ incidencia: tipo.incidencia });
    this.edicionTipo.abrir(tipo);
  }

  cerrarTipoForm(): void {
    this.edicionTipo.cerrar();
  }

  campoTipoInvalido(): boolean {
    const control = this.tipoFormulario.controls.incidencia;
    return control.touched && control.invalid;
  }

  guardarTipo(): void {
    const sistema = this.sistemaTipos();
    if (!sistema || this.tipoGuardando()) {
      return;
    }
    if (this.tipoFormulario.invalid) {
      this.tipoFormulario.markAllAsTouched();
      return;
    }

    const valores = this.tipoFormulario.getRawValue();
    const editando = this.tipoEditando();
    const peticion = { incidencia: valores.incidencia };
    const peticion$ = editando
      ? this.sistemasApi.actualizarTipo(sistema.id, editando.id, peticion)
      : this.sistemasApi.crearTipo(sistema.id, peticion);

    this.edicionTipo.guardar(peticion$, (_tipo, editado) => {
      this.catalogos.invalidar();
      this.notificaciones.exito(
        'Guardado',
        `Tipo de incidencia ${editado ? 'actualizado' : 'creado'}.`,
      );
      this.cerrarTipoForm();
      this.cargarTipos();
    });
  }

  confirmarEliminarTipo(tipo: TipoIncidencia): void {
    this.edicionTipo.confirmarEliminar(tipo);
  }

  eliminarTipo(): void {
    const sistema = this.sistemaTipos();
    if (!sistema) {
      return;
    }
    this.edicionTipo.eliminar(
      (tipo) => this.sistemasApi.eliminarTipo(sistema.id, tipo.id),
      (tipo) => {
        this.tiposLista.update((lista) => lista.filter((item) => item.id !== tipo.id));
        this.catalogos.invalidar();
        this.notificaciones.exito('Eliminado', `Tipo de incidencia ${tipo.incidencia} eliminado.`);
      },
    );
  }
}
