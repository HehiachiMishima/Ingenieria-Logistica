import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { mensajeDeError } from '../../../../../core/errors/error.util';
import { PERMISOS } from '../../../../../core/config/permisos';
import { DirectorioUsuarios } from '../../../../../core/services/directorio-usuarios';
import { Notificaciones } from '../../../../../core/services/notificaciones';
import { UsuariosApi } from '../../data/usuarios.api';
import { Usuario } from '../../../../../core/models';
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
import { iniciales } from '../../../../../core/utils/texto.util';
import { ListadoRegistros } from '../../state/listado-registros';
import { PermisosUsuario } from './permisos-usuario/permisos-usuario';
import { RolesUsuario } from './roles-usuario/roles-usuario';

interface ResumenUsuario {
  roles: string[];
  permisos: string[];
}

/** Valor del filtro de rol para «usuarios sin ningún rol». */
const SIN_ROL = '__sin_rol__';

@Component({
  selector: 'app-admin-usuarios',
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
    RolesUsuario,
    PermisosUsuario,
  ],
  templateUrl: './usuarios.html',
  styleUrl: './usuarios.scss',
})
export class Usuarios {
  private readonly fb = inject(FormBuilder);
  private readonly usuariosApi = inject(UsuariosApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly directorio = inject(DirectorioUsuarios);

  readonly permisos = PERMISOS;
  readonly sinRol = SIN_ROL;

  /** Filtro por rol asignado (nombre del rol o SIN_ROL). */
  readonly filtroRol = signal<string | null>(null);

  readonly listado = new ListadoRegistros<Usuario>({
    fuente: () => this.usuariosApi.listar(),
    campos: (usuario) => [usuario.usuario, usuario.nombre, usuario.apellido, usuario.email],
    alCargar: (lista) => {
      this.directorio.establecer(lista);
      this.cargarResumen(lista);
    },
    extra: {
      clave: 'rol',
      chip: () => {
        const rol = this.filtroRol();
        return rol === null
          ? null
          : {
              clave: 'rol',
              etiqueta: 'Rol',
              valor: rol === SIN_ROL ? 'Sin rol' : rol,
              icono: 'roles',
            };
      },
      condicion: () => {
        const rol = this.filtroRol();
        // Sin resumen de roles (cargando o con error) el filtro de rol no se
        // aplica: daría «todos sin rol» o «ninguno con el rol» por falta de datos.
        if (rol === null || !this.resumenDisponible()) {
          return null;
        }
        return rol === SIN_ROL
          ? (usuario) => this.rolesDe(usuario.id).length === 0
          : (usuario) => this.rolesDe(usuario.id).includes(rol);
      },
      limpiar: () => this.filtroRol.set(null),
    },
  });

  /** Resumen por usuario (roles asignados y permisos efectivos) para las columnas Rol/Permisos. */
  readonly cargandoResumen = signal(false);
  readonly errorResumen = signal(false);
  readonly resumen = signal<Map<number, ResumenUsuario>>(new Map());
  /** El filtro por rol solo es fiable con el resumen cargado sin error. */
  readonly resumenDisponible = computed(() => !this.cargandoResumen() && !this.errorResumen());
  /** Descarta la respuesta de un resumen pedido antes (p. ej. al recargar dos veces). */
  private solicitudResumen = 0;

  /** Roles presentes en el listado (desde el resumen agregado). */
  readonly opcionesRol = computed(() =>
    [...new Set([...this.resumen().values()].flatMap((r) => r.roles))].sort((a, b) =>
      a.localeCompare(b),
    ),
  );

  /** Filas de la página visible con sus datos derivados ya calculados. */
  readonly filas = computed(() => {
    const resumen = this.resumen();
    return this.listado.paginados().map((usuario) => {
      const datos = resumen.get(usuario.id);
      return {
        usuario,
        iniciales: iniciales(usuario),
        conResumen: datos !== undefined,
        roles: datos?.roles ?? [],
        permisos: datos?.permisos ?? [],
        permisosTitulo: datos?.permisos.join(', ') ?? '',
      };
    });
  });

  readonly formAbierto = signal(false);
  readonly editando = signal<Usuario | null>(null);
  readonly guardando = signal(false);

  readonly formulario = this.fb.nonNullable.group({
    usuario: ['', [Validators.required, Validators.maxLength(50)]],
    contrasena: [''],
    nombre: ['', [Validators.required, Validators.maxLength(100)]],
    apellido: ['', [Validators.required, Validators.maxLength(100)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(100)]],
    telefono: ['', [Validators.maxLength(20)]],
    activo: [true],
  });

  readonly eliminarAbierto = signal(false);
  readonly aEliminar = signal<Usuario | null>(null);
  readonly eliminando = signal(false);

  /** Usuario con el modal de roles / de permisos individuales abierto. */
  readonly usuarioRoles = signal<Usuario | null>(null);
  readonly usuarioPermisos = signal<Usuario | null>(null);

  constructor() {
    this.cargar();
  }

  cargar(): void {
    this.listado.recargar();
  }

  exportar(): void {
    const resumen = this.resumen();
    this.listado.exportar({
      archivo: 'usuarios',
      singular: 'usuario',
      plural: 'usuarios',
      columnas: [
        { titulo: 'Usuario', valor: (u) => u.usuario },
        { titulo: 'Nombre', valor: (u) => u.nombre },
        { titulo: 'Apellido', valor: (u) => u.apellido },
        { titulo: 'Email', valor: (u) => u.email },
        { titulo: 'Teléfono', valor: (u) => u.telefono },
        { titulo: 'Roles', valor: (u) => resumen.get(u.id)?.roles.join(', ') },
        { titulo: 'Permisos efectivos', valor: (u) => resumen.get(u.id)?.permisos.join(', ') },
      ],
    });
  }

  setRol(evento: Event): void {
    const valor = (evento.target as HTMLSelectElement).value;
    this.filtroRol.set(valor || null);
    this.listado.irAPrimeraPagina();
  }

  private cargarResumen(lista: Usuario[]): void {
    const solicitud = ++this.solicitudResumen;
    const vigente = () => solicitud === this.solicitudResumen;
    if (lista.length === 0) {
      this.cargandoResumen.set(false);
      this.resumen.set(new Map());
      return;
    }
    this.cargandoResumen.set(true);
    this.errorResumen.set(false);
    this.usuariosApi
      .resumen()
      .pipe(
        finalize(() => {
          if (vigente()) {
            this.cargandoResumen.set(false);
          }
        }),
      )
      .subscribe({
        next: (filas) => {
          if (!vigente()) {
            return;
          }
          const mapa = new Map<number, ResumenUsuario>();
          for (const usuario of lista) {
            mapa.set(usuario.id, { roles: [], permisos: [] });
          }
          for (const fila of filas) {
            mapa.set(fila.usuarioId, { roles: fila.roles, permisos: fila.permisos });
          }
          this.resumen.set(mapa);
          // Un rol renombrado o eliminado ya no aparece en las opciones: se
          // suelta el filtro en lugar de dejar la tabla vacía sin explicación.
          const rol = this.filtroRol();
          if (rol !== null && rol !== SIN_ROL && !this.opcionesRol().includes(rol)) {
            this.filtroRol.set(null);
          }
        },
        error: () => {
          if (!vigente()) {
            return;
          }
          this.resumen.set(new Map());
          this.errorResumen.set(true);
          this.filtroRol.set(null);
        },
      });
  }

  private rolesDe(id: number): string[] {
    return this.resumen().get(id)?.roles ?? [];
  }

  campoInvalido(
    campo: 'usuario' | 'contrasena' | 'nombre' | 'apellido' | 'email' | 'telefono',
  ): boolean {
    const control = this.formulario.controls[campo];
    return control.touched && control.invalid;
  }

  abrirCrear(): void {
    this.editando.set(null);
    this.formulario.reset({
      usuario: '',
      contrasena: '',
      nombre: '',
      apellido: '',
      email: '',
      telefono: '',
      activo: true,
    });
    this.formulario.controls.contrasena.setValidators([
      Validators.required,
      Validators.minLength(10),
      Validators.maxLength(72),
    ]);
    this.formulario.controls.contrasena.updateValueAndValidity();
    this.formAbierto.set(true);
  }

  abrirEditar(usuario: Usuario): void {
    this.editando.set(usuario);
    this.formulario.reset({
      usuario: usuario.usuario,
      contrasena: '',
      nombre: usuario.nombre,
      apellido: usuario.apellido,
      email: usuario.email,
      telefono: usuario.telefono ?? '',
      activo: usuario.activo,
    });
    this.formulario.controls.contrasena.setValidators([Validators.minLength(10), Validators.maxLength(72)]);
    this.formulario.controls.contrasena.updateValueAndValidity();
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
    this.guardando.set(true);

    const peticion = editando
      ? this.usuariosApi.actualizar(editando.id, {
          usuario: valores.usuario,
          nombre: valores.nombre,
          apellido: valores.apellido,
          email: valores.email,
          telefono: valores.telefono || null,
          activo: valores.activo,
          ...(valores.contrasena ? { contrasena: valores.contrasena } : {}),
        })
      : this.usuariosApi.crear({
          usuario: valores.usuario,
          contrasena: valores.contrasena,
          nombre: valores.nombre,
          apellido: valores.apellido,
          email: valores.email,
          telefono: valores.telefono || null,
          activo: valores.activo,
        });

    peticion.pipe(finalize(() => this.guardando.set(false))).subscribe({
      next: (usuario) => {
        this.notificaciones.exito(
          'Guardado',
          `Usuario ${usuario.usuario} ${editando ? 'actualizado' : 'creado'}.`,
        );
        this.cerrarForm();
        this.cargar();
      },
      error: (err: unknown) => this.notificaciones.error('No se pudo guardar', mensajeDeError(err)),
    });
  }

  alternarActivo(usuario: Usuario): void {
    this.usuariosApi.cambiarActivo(usuario.id, !usuario.activo).subscribe({
      next: (actualizado) => {
        this.listado.reemplazar(actualizado);
        this.notificaciones.info(
          usuario.activo ? 'Usuario desactivado' : 'Usuario activado',
          usuario.usuario,
        );
      },
      error: (err: unknown) => this.notificaciones.error('Error', mensajeDeError(err)),
    });
  }

  confirmarEliminar(usuario: Usuario): void {
    this.aEliminar.set(usuario);
    this.eliminarAbierto.set(true);
  }

  eliminar(): void {
    const usuario = this.aEliminar();
    if (!usuario || this.eliminando()) {
      return;
    }
    this.eliminando.set(true);
    this.usuariosApi
      .eliminar(usuario.id)
      .pipe(finalize(() => this.eliminando.set(false)))
      .subscribe({
        next: () => {
          this.listado.quitar(usuario.id);
          this.notificaciones.exito('Eliminado', `Usuario ${usuario.usuario} eliminado.`);
          this.eliminarAbierto.set(false);
          this.aEliminar.set(null);
        },
        error: (err: unknown) =>
          this.notificaciones.error('No se pudo eliminar', mensajeDeError(err)),
      });
  }

  /** Cierra el modal de roles o de permisos y refresca las columnas/filtro de rol si hubo cambios. */
  cerrarAsignaciones(huboCambios: boolean): void {
    this.usuarioRoles.set(null);
    this.usuarioPermisos.set(null);
    if (huboCambios) {
      this.cargarResumen(this.listado.items());
    }
  }
}
