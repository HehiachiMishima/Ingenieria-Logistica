import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { PERMISOS } from '../../../../../core/config/permisos';
import { mensajeDeError } from '../../../../../core/errors/error.util';
import { DirectorioUsuarios } from '../../../../../core/services/directorio-usuarios';
import { Notificaciones } from '../../../../../core/services/notificaciones';
import { RolesApi } from '../../data/roles.api';
import { PermisoDelRol, RolPermisosLote } from '../../data/permiso.model';
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
import { SesionStore } from '../../../../../core/services/sesion';
import { TableroPermisos } from '../../ui/tablero-permisos/tablero-permisos';

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
    TableroPermisos,
  ],
  templateUrl: './roles.html',
  styleUrl: './roles.scss',
})
export class Roles {
  private readonly fb = inject(FormBuilder);
  private readonly rolesApi = inject(RolesApi);
  private readonly sesion = inject(SesionStore);
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
  readonly tableroPermisos = signal<PermisoDelRol[]>([]);
  readonly guardandoPermisos = signal(false);
  readonly confirmarDescarte = signal(false);
  readonly tablero = viewChild(TableroPermisos);
  readonly cambiosPermisos = computed(() => this.tablero()?.totalCambios() ?? 0);
  /** Permisos de quien edita: el tablero no deja otorgar otros (el backend también lo impide). */
  readonly otorgables = this.sesion.permisos;
  readonly rolProtegido = computed(() => this.rolPermisos()?.nombre.toUpperCase() === 'ADMINISTRADOR');
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
    this.tableroPermisos.set([]);
    this.errorPermisos.set(null);
    this.confirmarDescarte.set(false);
    this.permisosAbierto.set(true);
    this.cargandoPermisos.set(true);
    this.rolesApi
      .tableroPermisos(rol.id)
      .pipe(
        finalize(() => {
          if (vigente()) {
            this.cargandoPermisos.set(false);
          }
        }),
      )
      .subscribe({
        next: (permisos) => {
          if (vigente()) {
            this.tableroPermisos.set(permisos);
          }
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

  /** Cerrar con cambios sin guardar pide confirmación en el pie del diálogo. */
  cerrarPermisos(): void {
    if (this.guardandoPermisos()) {
      return;
    }
    if (this.cambiosPermisos() > 0 && !this.confirmarDescarte()) {
      this.confirmarDescarte.set(true);
      return;
    }
    this.confirmarDescarte.set(false);
    this.permisosAbierto.set(false);
    this.solicitudPermisos++;
  }

  descartarPermisos(): void {
    this.tablero()?.descartar();
    this.confirmarDescarte.set(false);
  }

  /** Guarda en una sola petición todo lo movido en el tablero. */
  guardarPermisos(): void {
    const rol = this.rolPermisos();
    const tablero = this.tablero();
    if (!rol || !tablero || this.guardandoPermisos() || tablero.totalCambios() === 0) {
      return;
    }
    const cambios = tablero.cambios();
    const solicitud = this.solicitudPermisos;
    this.guardandoPermisos.set(true);
    this.confirmarDescarte.set(false);
    this.rolesApi
      .actualizarPermisos(rol.id, cambios)
      .pipe(finalize(() => this.guardandoPermisos.set(false)))
      .subscribe({
        next: (resultado) => {
          if (solicitud === this.solicitudPermisos) {
            const ahora = new Set(resultado.asignados);
            this.tableroPermisos.update((permisos) =>
              permisos.map((p) => {
                const asignado = ahora.has(p.id);
                const roles = p.roles + (asignado === p.asignado ? 0 : asignado ? 1 : -1);
                return { ...p, asignado, roles };
              }),
            );
          }
          this.notificaciones.exito('Permisos del rol actualizados', resumenLote(rol.nombre, resultado));
        },
        error: (err: unknown) =>
          this.notificaciones.error('No se pudieron guardar los permisos', mensajeDeError(err)),
      });
  }
}

/** «Supervisor · 3 agregados y 1 quitado». */
function resumenLote(rol: string, { agregados, quitados }: RolPermisosLote): string {
  const partes = [
    agregados ? `${agregados} ${agregados === 1 ? 'agregado' : 'agregados'}` : '',
    quitados ? `${quitados} ${quitados === 1 ? 'quitado' : 'quitados'}` : '',
  ].filter(Boolean);
  return `${rol} · ${partes.length ? partes.join(' y ') : 'sin cambios (ya estaban aplicados)'}`;
}
