import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, forkJoin } from 'rxjs';
import { PERMISOS } from '../../../../../core/config/permisos';
import { mensajeDeError } from '../../../../../core/errors/error.util';
import { DirectorioUsuarios } from '../../../../../core/services/directorio-usuarios';
import { Notificaciones } from '../../../../../core/services/notificaciones';
import { PermisosApi } from '../../data/permisos.api';
import { RolesApi } from '../../data/roles.api';
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
  selector: 'app-admin-roles',
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
  templateUrl: './roles.html',
  styleUrl: './roles.scss',
})
export class Roles {
  private readonly fb = inject(FormBuilder);
  private readonly rolesApi = inject(RolesApi);
  private readonly permisosApi = inject(PermisosApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly directorio = inject(DirectorioUsuarios);

  readonly permisos = PERMISOS;

  readonly listado = new ListadoRegistros<Rol>({
    fuente: () => this.rolesApi.listar(),
    campos: (rol) => [rol.nombre, rol.descripcion],
  });

  readonly formAbierto = signal(false);
  readonly editando = signal<Rol | null>(null);
  readonly guardando = signal(false);

  readonly formulario = this.fb.nonNullable.group({
    nombre: ['', [Validators.required, Validators.maxLength(100)]],
    descripcion: [''],
    activo: [true],
  });

  readonly eliminarAbierto = signal(false);
  readonly aEliminar = signal<Rol | null>(null);
  readonly eliminando = signal(false);

  readonly permisosAbierto = signal(false);
  readonly rolPermisos = signal<Rol | null>(null);
  readonly cargandoPermisos = signal(false);
  readonly errorPermisos = signal<string | null>(null);
  readonly catalogoPermisos = signal<Permiso[]>([]);
  readonly permisosAsignados = signal<Set<number>>(new Set());
  readonly cambiandoPermiso = signal<number | null>(null);
  /** Descarta la respuesta de un modal abierto antes (otro rol). */
  private solicitudPermisos = 0;

  constructor() {
    this.directorio.cargar();
    this.cargar();
  }

  cargar(): void {
    this.listado.recargar();
  }

  exportar(): void {
    this.listado.exportar({
      archivo: 'roles',
      singular: 'rol',
      plural: 'roles',
      columnas: [
        { titulo: 'Rol', valor: (r) => r.nombre },
        { titulo: 'Descripción', valor: (r) => r.descripcion },
      ],
    });
  }

  campoInvalido(campo: 'nombre' | 'descripcion'): boolean {
    const control = this.formulario.controls[campo];
    return control.touched && control.invalid;
  }

  abrirCrear(): void {
    this.editando.set(null);
    this.formulario.reset({ nombre: '', descripcion: '', activo: true });
    this.formAbierto.set(true);
  }

  abrirEditar(rol: Rol): void {
    this.editando.set(rol);
    this.formulario.reset({
      nombre: rol.nombre,
      descripcion: rol.descripcion ?? '',
      activo: rol.activo,
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
      ? this.rolesApi.actualizar(editando.id, peticion)
      : this.rolesApi.crear(peticion);

    peticion$.pipe(finalize(() => this.guardando.set(false))).subscribe({
      next: (rol) => {
        this.notificaciones.exito(
          'Guardado',
          `Rol ${rol.nombre} ${editando ? 'actualizado' : 'creado'}.`,
        );
        this.cerrarForm();
        this.cargar();
      },
      error: (err: unknown) => this.notificaciones.error('No se pudo guardar', mensajeDeError(err)),
    });
  }

  toggleActivo(rol: Rol): void {
    this.rolesApi.cambiarActivo(rol.id, !rol.activo).subscribe({
      next: (actualizado) => {
        this.listado.reemplazar(actualizado);
        this.notificaciones.info(
          actualizado.activo ? 'Rol activado' : 'Rol desactivado',
          actualizado.nombre,
        );
      },
      error: (err: unknown) =>
        this.notificaciones.error('No se pudo actualizar', mensajeDeError(err)),
    });
  }

  confirmarEliminar(rol: Rol): void {
    this.aEliminar.set(rol);
    this.eliminarAbierto.set(true);
  }

  eliminar(): void {
    const rol = this.aEliminar();
    if (!rol || this.eliminando()) {
      return;
    }
    this.eliminando.set(true);
    this.rolesApi
      .eliminar(rol.id)
      .pipe(finalize(() => this.eliminando.set(false)))
      .subscribe({
        next: () => {
          this.listado.quitar(rol.id);
          this.notificaciones.exito('Eliminado', `Rol ${rol.nombre} eliminado.`);
          this.eliminarAbierto.set(false);
          this.aEliminar.set(null);
        },
        error: (err: unknown) =>
          this.notificaciones.error('No se pudo eliminar', mensajeDeError(err)),
      });
  }

  abrirPermisos(rol: Rol): void {
    const solicitud = ++this.solicitudPermisos;
    const vigente = () => solicitud === this.solicitudPermisos;
    this.rolPermisos.set(rol);
    this.permisosAsignados.set(new Set());
    this.errorPermisos.set(null);
    this.permisosAbierto.set(true);
    this.cargandoPermisos.set(true);
    forkJoin({
      catalogo: this.permisosApi.listar(),
      asignados: this.rolesApi.permisos(rol.id),
    })
      .pipe(
        finalize(() => {
          if (vigente()) {
            this.cargandoPermisos.set(false);
          }
        }),
      )
      .subscribe({
        next: ({ catalogo, asignados }) => {
          if (!vigente()) {
            return;
          }
          this.catalogoPermisos.set(catalogo);
          this.permisosAsignados.set(new Set(asignados.map((permiso) => permiso.id)));
        },
        error: (err: unknown) => {
          if (!vigente()) {
            return;
          }
          this.errorPermisos.set(mensajeDeError(err));
          this.notificaciones.error('No se pudieron cargar los permisos', mensajeDeError(err));
        },
      });
  }

  alternarPermiso(permiso: Permiso): void {
    const rol = this.rolPermisos();
    if (!rol || this.cambiandoPermiso()) {
      return;
    }
    this.cambiandoPermiso.set(permiso.id);
    const asignado = this.permisosAsignados().has(permiso.id);
    const peticion$ = asignado
      ? this.rolesApi.quitarPermiso(rol.id, permiso.id)
      : this.rolesApi.asignarPermiso(rol.id, permiso.id);

    peticion$.pipe(finalize(() => this.cambiandoPermiso.set(null))).subscribe({
      next: () => {
        this.permisosAsignados.update((set) => {
          const copia = new Set(set);
          if (asignado) {
            copia.delete(permiso.id);
          } else {
            copia.add(permiso.id);
          }
          return copia;
        });
        this.notificaciones.info(
          asignado ? 'Permiso quitado del rol' : 'Permiso asignado al rol',
          permiso.nombre,
        );
      },
      error: (err: unknown) =>
        this.notificaciones.error('No se pudo actualizar', mensajeDeError(err)),
    });
  }
}
