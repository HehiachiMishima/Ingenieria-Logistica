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
import { AreasApi } from '../../../data/areas.api';
import { Area, Responsable } from '../../../data/bitacora.model';
import { BitacoraCatalogos } from '../../../state/bitacora-catalogos';

@Component({
  selector: 'app-bitacora-areas',
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
  templateUrl: './areas.html',
  styleUrl: './areas.scss',
})
export class Areas {
  private readonly fb = inject(FormBuilder);
  private readonly areasApi = inject(AreasApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly catalogos = inject(BitacoraCatalogos);
  private readonly directorio = inject(DirectorioUsuarios);

  readonly permisos = PERMISOS;

  private readonly listado = new ListadoLocal<Area>(
    () => this.areasApi.listar(),
    (area) => area.area,
  );
  readonly cargando = this.listado.cargando;
  readonly error = this.listado.error;
  readonly busqueda = this.listado.busqueda;
  readonly filtrados = this.listado.filtrados;
  readonly pagina = this.listado.pagina;
  readonly porPagina = this.listado.porPagina;
  readonly paginaActual = this.listado.paginaActual;
  readonly paginados = this.listado.paginados;

  private readonly edicion = new EdicionCrud<Area>();
  readonly formAbierto = this.edicion.formAbierto;
  readonly editando = this.edicion.editando;
  readonly guardando = this.edicion.guardando;
  readonly eliminarAbierto = this.edicion.eliminarAbierto;
  readonly aEliminar = this.edicion.aEliminar;
  readonly eliminando = this.edicion.eliminando;

  readonly formulario = this.fb.nonNullable.group({
    area: ['', [Validators.required, Validators.maxLength(50)]],
  });

  readonly hijosAbierto = signal(false);
  readonly areaHijos = signal<Area | null>(null);
  /** Cada apertura cancela la carga anterior: nunca se ven los hijos de otro elemento. */
  private readonly responsables = new OpcionesDependientes<Responsable>(
    (id) => this.areasApi.responsablesDe(id),
    'No se pudieron cargar los responsables',
  );
  readonly hijosLista = this.responsables.opciones;
  readonly cargandoHijos = this.responsables.cargando;

  private readonly edicionHijo = new EdicionCrud<Responsable>();
  readonly hijoFormAbierto = this.edicionHijo.formAbierto;
  readonly hijoEditando = this.edicionHijo.editando;
  readonly hijoGuardando = this.edicionHijo.guardando;
  readonly hijoEliminarAbierto = this.edicionHijo.eliminarAbierto;
  readonly hijoAEliminar = this.edicionHijo.aEliminar;
  readonly hijoEliminando = this.edicionHijo.eliminando;

  readonly hijoFormulario = this.fb.nonNullable.group({
    responsable: ['', [Validators.required, Validators.maxLength(50)]],
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

  campoInvalido(campo: 'area'): boolean {
    const control = this.formulario.controls[campo];
    return control.touched && control.invalid;
  }

  abrirCrear(): void {
    this.formulario.reset({ area: '' });
    this.edicion.abrir(null);
  }

  abrirEditar(area: Area): void {
    this.formulario.reset({ area: area.area });
    this.edicion.abrir(area);
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
    const peticion = { area: valores.area };
    const peticion$ = editando
      ? this.areasApi.actualizar(editando.id, peticion)
      : this.areasApi.crear(peticion);

    this.edicion.guardar(peticion$, (area, editado) => {
      this.catalogos.invalidar();
      this.notificaciones.exito(
        'Guardado',
        `Área ${area.area} ${editado ? 'actualizada' : 'creada'}.`,
      );
      this.cerrarForm();
      this.cargar();
    });
  }

  confirmarEliminar(area: Area): void {
    this.edicion.confirmarEliminar(area);
  }

  eliminar(): void {
    this.edicion.eliminar(
      (area) => this.areasApi.eliminar(area.id),
      (area) => {
        this.listado.quitar(area.id);
        this.catalogos.invalidar();
        this.notificaciones.exito('Eliminado', `Área ${area.area} eliminada.`);
      },
    );
  }

  abrirHijos(area: Area): void {
    this.areaHijos.set(area);
    this.hijosAbierto.set(true);
    this.edicionHijo.cerrar();
    this.cargarHijos();
  }

  private cargarHijos(): void {
    const area = this.areaHijos();
    if (area) {
      this.responsables.elegir(area.id);
    }
  }

  abrirHijoCrear(): void {
    this.hijoFormulario.reset({ responsable: '' });
    this.edicionHijo.abrir(null);
  }

  abrirHijoEditar(responsable: Responsable): void {
    this.hijoFormulario.reset({ responsable: responsable.responsable });
    this.edicionHijo.abrir(responsable);
  }

  cerrarHijoForm(): void {
    this.edicionHijo.cerrar();
  }

  campoHijoInvalido(): boolean {
    const control = this.hijoFormulario.controls.responsable;
    return control.touched && control.invalid;
  }

  guardarHijo(): void {
    const area = this.areaHijos();
    if (!area || this.hijoGuardando()) {
      return;
    }
    if (this.hijoFormulario.invalid) {
      this.hijoFormulario.markAllAsTouched();
      return;
    }

    const valores = this.hijoFormulario.getRawValue();
    const editando = this.hijoEditando();
    const peticion = { responsable: valores.responsable };
    const peticion$ = editando
      ? this.areasApi.actualizarResponsable(area.id, editando.id, peticion)
      : this.areasApi.crearResponsable(area.id, peticion);

    this.edicionHijo.guardar(peticion$, (_responsable, editado) => {
      this.catalogos.invalidar();
      this.notificaciones.exito('Guardado', `Responsable ${editado ? 'actualizado' : 'creado'}.`);
      this.cerrarHijoForm();
      this.cargarHijos();
    });
  }

  confirmarEliminarHijo(responsable: Responsable): void {
    this.edicionHijo.confirmarEliminar(responsable);
  }

  eliminarHijo(): void {
    const area = this.areaHijos();
    if (!area) {
      return;
    }
    this.edicionHijo.eliminar(
      (responsable) => this.areasApi.eliminarResponsable(area.id, responsable.id),
      (responsable) => {
        this.hijosLista.update((lista) => lista.filter((item) => item.id !== responsable.id));
        this.catalogos.invalidar();
        this.notificaciones.exito('Eliminado', `Responsable ${responsable.responsable} eliminado.`);
      },
    );
  }
}
