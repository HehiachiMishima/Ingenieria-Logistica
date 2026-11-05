import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { mensajeDeError } from '../../../../../core/errors/error.util';
import { PERMISOS } from '../../../../../core/config/permisos';
import { Notificaciones } from '../../../../../core/services/notificaciones';
import { DirectorioUsuarios } from '../../../../../core/services/directorio-usuarios';
import { PermisosApi } from '../../data/permisos.api';
import { Permiso } from '../../data/permiso.model';
import { Rol } from '../../data/rol.model';
import { HasPermiso } from '../../../../../shared/directives/has-permiso.directive';
import { ColumnasRedimensionables } from '../../../../../shared/directives/columnas-redimensionables.directive';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../shared/ui/modal/modal';
import { Paginador } from '../../../../../shared/ui/paginador/paginador';
import { FiltrosActivos } from '../../../../../shared/ui/panel-filtros/filtros-activos';
import { PanelFiltros } from '../../../../../shared/ui/panel-filtros/panel-filtros';
import { RangoRapido } from '../../../../../shared/ui/panel-filtros/rango-rapido';
import { ActorLabelPipe } from '../../../../../shared/pipes/actor-label.pipe';
import { ListadoRegistros } from '../../state/listado-registros';
import { ListaAsignacion } from '../../ui/lista-asignacion';

@Component({
  selector: 'app-admin-permisos',
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
    PanelFiltros,
    FiltrosActivos,
    RangoRapido,
    ListaAsignacion,
  ],
  templateUrl: './permisos.html',
  styleUrl: './permisos.scss',
})
export class Permisos {
  private readonly fb = inject(FormBuilder);
  private readonly permisosApi = inject(PermisosApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly directorio = inject(DirectorioUsuarios);

  readonly permisos = PERMISOS;

  readonly listado = new ListadoRegistros<Permiso>({
    fuente: () => this.permisosApi.listar(),
    campos: (permiso) => [permiso.nombre, permiso.descripcion],
  });

  readonly formAbierto = signal(false);
  readonly editando = signal<Permiso | null>(null);
  readonly guardando = signal(false);

  readonly formulario = this.fb.nonNullable.group({
    nombre: ['', [Validators.required, Validators.maxLength(100)]],
    descripcion: [''],
    activo: [true],
  });

  readonly eliminarAbierto = signal(false);
  readonly aEliminar = signal<Permiso | null>(null);
  readonly eliminando = signal(false);

  readonly rolesAbierto = signal(false);
  readonly permisoRoles = signal<Permiso | null>(null);
  readonly cargandoRoles = signal(false);
  readonly errorRoles = signal<string | null>(null);
  readonly rolesConPermiso = signal<Rol[]>([]);
  /** Descarta la respuesta de un modal abierto antes (otro permiso). */
  private solicitudRoles = 0;

  constructor() {
    this.directorio.cargar();
    this.cargar();
  }

  cargar(): void {
    this.listado.recargar();
  }

  exportar(): void {
    this.listado.exportar({
      archivo: 'permisos',
      singular: 'permiso',
      plural: 'permisos',
      columnas: [
        { titulo: 'Permiso', valor: (p) => p.nombre },
        { titulo: 'Descripción', valor: (p) => p.descripcion },
      ],
    });
  }

  campoInvalido(campo: 'nombre'): boolean {
    const control = this.formulario.controls[campo];
    return control.touched && control.invalid;
  }

  abrirCrear(): void {
    this.editando.set(null);
    this.formulario.reset({ nombre: '', descripcion: '', activo: true });
    this.formAbierto.set(true);
  }

  abrirEditar(permiso: Permiso): void {
    this.editando.set(permiso);
    this.formulario.reset({
      nombre: permiso.nombre,
      descripcion: permiso.descripcion ?? '',
      activo: permiso.activo,
    });
    this.formAbierto.set(true);
  }

  cerrarForm(): void {
    this.formAbierto.set(false);
    this.editando.set(null);
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
    const peticion = {
      nombre: valores.nombre,
      descripcion: valores.descripcion || null,
      activo: valores.activo,
    };

    this.guardando.set(true);
    const peticion$ = editando
      ? this.permisosApi.actualizar(editando.id, peticion)
      : this.permisosApi.crear(peticion);

    peticion$.pipe(finalize(() => this.guardando.set(false))).subscribe({
      next: (permiso) => {
        this.notificaciones.exito(
          'Guardado',
          `Permiso ${permiso.nombre} ${editando ? 'actualizado' : 'creado'}.`,
        );
        this.cerrarForm();
        this.cargar();
      },
      error: (err: unknown) => this.notificaciones.error('No se pudo guardar', mensajeDeError(err)),
    });
  }

  toggleActivo(permiso: Permiso): void {
    this.permisosApi.cambiarActivo(permiso.id, !permiso.activo).subscribe({
      next: (actualizado) => {
        this.listado.reemplazar(actualizado);
        this.notificaciones.info(
          actualizado.activo ? 'Permiso activado' : 'Permiso desactivado',
          actualizado.nombre,
        );
      },
      error: (err: unknown) =>
        this.notificaciones.error('No se pudo actualizar', mensajeDeError(err)),
    });
  }

  confirmarEliminar(permiso: Permiso): void {
    this.aEliminar.set(permiso);
    this.eliminarAbierto.set(true);
  }

  eliminar(): void {
    const permiso = this.aEliminar();
    if (!permiso || this.eliminando()) {
      return;
    }
    this.eliminando.set(true);
    this.permisosApi
      .eliminar(permiso.id)
      .pipe(finalize(() => this.eliminando.set(false)))
      .subscribe({
        next: () => {
          this.listado.quitar(permiso.id);
          this.notificaciones.exito('Eliminado', `Permiso ${permiso.nombre} eliminado.`);
          this.eliminarAbierto.set(false);
          this.aEliminar.set(null);
        },
        error: (err: unknown) =>
          this.notificaciones.error('No se pudo eliminar', mensajeDeError(err)),
      });
  }

  abrirRoles(permiso: Permiso): void {
    const solicitud = ++this.solicitudRoles;
    const vigente = () => solicitud === this.solicitudRoles;
    this.permisoRoles.set(permiso);
    this.rolesConPermiso.set([]);
    this.errorRoles.set(null);
    this.rolesAbierto.set(true);
    this.cargandoRoles.set(true);
    this.permisosApi
      .roles(permiso.id)
      .pipe(
        finalize(() => {
          if (vigente()) {
            this.cargandoRoles.set(false);
          }
        }),
      )
      .subscribe({
        next: (roles) => {
          if (vigente()) {
            this.rolesConPermiso.set(roles);
          }
        },
        error: (err: unknown) => {
          if (!vigente()) {
            return;
          }
          this.errorRoles.set(mensajeDeError(err));
          this.notificaciones.error('No se pudieron cargar los roles', mensajeDeError(err));
        },
      });
  }
}
