import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { EMPTY, catchError, finalize, forkJoin, switchMap } from 'rxjs';
import { mensajeDeError } from '../../../../../../core/errors/error.util';
import { Usuario } from '../../../../../../core/models';
import { Notificaciones } from '../../../../../../core/services/notificaciones';
import { EmptyState } from '../../../../../../shared/ui/empty-state/empty-state';
import { Modal } from '../../../../../../shared/ui/modal/modal';
import { Rol } from '../../../data/rol.model';
import { RolesApi } from '../../../data/roles.api';
import { UsuariosApi } from '../../../data/usuarios.api';
import { ListaAsignacion } from '../../../ui/lista-asignacion';

/**
 * Modal de roles de un usuario: activa o quita roles uno a uno. Se abre con
 * un usuario y, al cerrarse, avisa si hubo cambios para que la página
 * refresque sus columnas de roles y permisos.
 */
@Component({
  selector: 'app-roles-usuario',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EmptyState, ListaAsignacion, Modal],
  templateUrl: './roles-usuario.html',
  styles: `
    .loading {
      font-size: var(--fs-md);
    }
  `,
})
export class RolesUsuario {
  private readonly usuariosApi = inject(UsuariosApi);
  private readonly rolesApi = inject(RolesApi);
  private readonly notificaciones = inject(Notificaciones);

  /** Usuario cuyos roles se editan; null = modal cerrado. */
  readonly usuario = input<Usuario | null>(null);
  /** Emite al cerrar: true si se asignó o quitó algún rol. */
  readonly cerrar = output<boolean>();

  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);
  readonly catalogo = signal<Rol[]>([]);
  readonly asignados = signal<ReadonlySet<number>>(new Set());
  readonly cambiando = signal<number | null>(null);
  private huboCambios = false;

  constructor() {
    // Cada usuario cancela la carga del anterior: una respuesta lenta nunca
    // muestra (ni deja editar) los roles de otro usuario.
    toObservable(this.usuario)
      .pipe(
        switchMap((usuario) => {
          this.error.set(null);
          this.asignados.set(new Set());
          this.huboCambios = false;
          if (!usuario) {
            return EMPTY;
          }
          this.cargando.set(true);
          return forkJoin({
            catalogo: this.rolesApi.listar(),
            asignados: this.usuariosApi.roles(usuario.id),
          }).pipe(
            catchError((err: unknown) => {
              this.error.set(mensajeDeError(err));
              this.notificaciones.error('No se pudieron cargar los roles', mensajeDeError(err));
              return EMPTY;
            }),
            finalize(() => this.cargando.set(false)),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe(({ catalogo, asignados }) => {
        this.catalogo.set(catalogo);
        this.asignados.set(new Set(asignados.map((rol) => rol.id)));
      });
  }

  cerrarModal(): void {
    this.cerrar.emit(this.huboCambios);
  }

  alternar(rol: Rol): void {
    const usuario = this.usuario();
    if (!usuario || this.cambiando()) {
      return;
    }
    this.cambiando.set(rol.id);
    const asignado = this.asignados().has(rol.id);
    const peticion$ = asignado
      ? this.usuariosApi.quitarRol(usuario.id, rol.id)
      : this.usuariosApi.asignarRol(usuario.id, rol.id);

    peticion$.pipe(finalize(() => this.cambiando.set(null))).subscribe({
      next: () => {
        this.asignados.update((actuales) => {
          const copia = new Set(actuales);
          if (asignado) {
            copia.delete(rol.id);
          } else {
            copia.add(rol.id);
          }
          return copia;
        });
        this.huboCambios = true;
        this.notificaciones.info(asignado ? 'Rol quitado' : 'Rol asignado', rol.nombre);
      },
      error: (err: unknown) =>
        this.notificaciones.error('No se pudo actualizar el rol', mensajeDeError(err)),
    });
  }
}
