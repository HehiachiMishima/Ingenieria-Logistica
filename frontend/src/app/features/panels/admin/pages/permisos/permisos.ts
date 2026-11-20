import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { mensajeDeError } from '../../../../../core/errors/error.util';
import { PERMISOS } from '../../../../../core/config/permisos';
import { Notificaciones } from '../../../../../core/services/notificaciones';
import { DirectorioUsuarios } from '../../../../../core/services/directorio-usuarios';
import { PermisosApi } from '../../data/permisos.api';
import { Permiso, PermisoRolesLote, RolesPermisoTablero } from '../../data/permiso.model';
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
import {
  CabeceraPermiso,
  RolTablero,
  TableroRoles,
  TextosTableroRoles,
} from '../../ui/tablero-roles/tablero-roles';

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
    TableroRoles,
  ],
  templateUrl: './permisos.html',
  styleUrl: './permisos.scss',
})
export class Permisos {
  private readonly fb = inject(FormBuilder);
  private readonly permisosApi = inject(PermisosApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly directorio = inject(DirectorioUsuarios);
  private readonly sesion = inject(SesionStore);

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
  readonly tableroRoles = signal<RolesPermisoTablero | null>(null);
  readonly guardandoRoles = signal(false);
  readonly confirmarDescarte = signal(false);
  readonly tablero = viewChild(TableroRoles);
  readonly cambiosRoles = computed(() => this.tablero()?.totalCambios() ?? 0);
  /** Descarta la respuesta de un modal abierto antes (otro permiso). */
  private solicitudRoles = 0;

  readonly textosRoles: TextosTableroRoles = {
    destino: 'Con el permiso',
    dar: 'Dar el permiso',
    quitar: 'Quitar el permiso',
    vacioDestino: 'Ningún rol lo concede: suelta aquí los roles que deben tenerlo.',
    vacioCatalogo: 'Todos los roles ya tienen este permiso.',
    ariaCatalogo: 'Roles sin este permiso',
    ariaDestino: 'Roles con este permiso',
    movidosAlDestino: ['rol recibe el permiso', 'roles reciben el permiso'],
    movidosAlCatalogo: ['rol pierde el permiso', 'roles pierden el permiso'],
  };

  /** Sin ASIGNAR_PERMISOS se ve el reparto pero no se cambia (el backend también lo exige). */
  readonly bloqueoRoles = computed(() =>
    this.sesion.tienePermiso(PERMISOS.ASIGNAR_PERMISOS)
      ? null
      : 'Necesita el permiso ASIGNAR_PERMISOS para dar o quitar permisos a los roles.',
  );

  /**
   * Roles para el tablero con las reglas del backend por rol: al ADMINISTRADOR no se le quita;
   * no se da un permiso inactivo ni uno que quien edita no tiene. Un rol inactivo sí puede
   * recibirlo (queda listo para cuando se active).
   */
  readonly rolesDelTablero = computed<RolTablero[]>(() => {
    const tablero = this.tableroRoles();
    if (!tablero) {
      return [];
    }
    const motivoDar = !tablero.activo
      ? `«${tablero.nombre}» está inactivo: reactívelo antes de darlo a un rol.`
      : !tablero.otorgable
        ? `No puede dar «${tablero.nombre}»: usted no tiene ese permiso.`
        : null;
    return tablero.roles.map((rol) => ({
      ...rol,
      motivoDar,
      motivoQuitar: rol.protegido ? 'Al rol ADMINISTRADOR no se le quitan permisos: solo se le agregan.' : null,
    }));
  });

  readonly cabeceraRoles = computed<CabeceraPermiso | null>(() => {
    const tablero = this.tableroRoles();
    return tablero
      ? { nombre: tablero.nombre, descripcion: tablero.descripcion, activo: tablero.activo, usuarios: tablero.usuarios }
      : null;
  });

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
    this.tableroRoles.set(null);
    this.errorRoles.set(null);
    this.confirmarDescarte.set(false);
    this.rolesAbierto.set(true);
    this.cargandoRoles.set(true);
    this.permisosApi
      .tableroRoles(permiso.id)
      .pipe(
        finalize(() => {
          if (vigente()) {
            this.cargandoRoles.set(false);
          }
        }),
      )
      .subscribe({
        next: (tablero) => {
          if (vigente()) {
            this.tableroRoles.set(tablero);
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

  /** Cerrar con cambios sin guardar pide confirmación en el pie del diálogo. */
  cerrarRoles(): void {
    if (this.guardandoRoles()) {
      return;
    }
    if (this.cambiosRoles() > 0 && !this.confirmarDescarte()) {
      this.confirmarDescarte.set(true);
      return;
    }
    this.confirmarDescarte.set(false);
    this.rolesAbierto.set(false);
    this.solicitudRoles++;
  }

  descartarRoles(): void {
    this.tablero()?.descartar();
    this.confirmarDescarte.set(false);
  }

  /** Da y quita el permiso a todos los roles movidos en una sola petición. */
  guardarRoles(): void {
    const permiso = this.permisoRoles();
    const tablero = this.tablero();
    if (!permiso || !tablero || this.guardandoRoles() || tablero.totalCambios() === 0) {
      return;
    }
    const solicitud = this.solicitudRoles;
    this.guardandoRoles.set(true);
    this.confirmarDescarte.set(false);
    this.permisosApi
      .actualizarRoles(permiso.id, tablero.cambios())
      .pipe(finalize(() => this.guardandoRoles.set(false)))
      .subscribe({
        next: (resultado) => {
          if (solicitud === this.solicitudRoles) {
            const ahora = new Set(resultado.asignados);
            this.tableroRoles.update((actual) =>
              actual && {
                ...actual,
                usuarios: resultado.usuarios,
                roles: actual.roles.map((r) => ({ ...r, asignado: ahora.has(r.id) })),
              },
            );
          }
          this.notificaciones.exito('Roles del permiso actualizados', resumenLote(permiso.nombre, resultado));
        },
        error: (err: unknown) => this.notificaciones.error('No se pudieron guardar los roles', mensajeDeError(err)),
      });
  }
}

/** «VER_ORDENES_PICKING · 2 roles lo reciben y 1 lo pierde». */
function resumenLote(permiso: string, { agregados, quitados }: PermisoRolesLote): string {
  const partes = [
    agregados ? `${agregados} ${agregados === 1 ? 'rol lo recibe' : 'roles lo reciben'}` : '',
    quitados ? `${quitados} ${quitados === 1 ? 'lo pierde' : 'lo pierden'}` : '',
  ].filter(Boolean);
  return `${permiso} · ${partes.length ? partes.join(' y ') : 'sin cambios (ya estaban aplicados)'}`;
}
