import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { EMPTY, catchError, finalize, switchMap } from 'rxjs';
import { mensajeDeError } from '../../../../../../core/errors/error.util';
import { Usuario } from '../../../../../../core/models';
import { Notificaciones } from '../../../../../../core/services/notificaciones';
import { EmptyState } from '../../../../../../shared/ui/empty-state/empty-state';
import { Modal } from '../../../../../../shared/ui/modal/modal';
import { RolDelUsuario, UsuarioRolesLote } from '../../../data/rol.model';
import { UsuariosApi } from '../../../data/usuarios.api';
import { TableroRoles } from '../../../ui/tablero-roles/tablero-roles';

/**
 * Modal de roles de un usuario: tablero de fichas (catálogo ↔ usuario) que junta los cambios y
 * los guarda en una sola petición. Se abre con un usuario y, al cerrarse, avisa si hubo cambios
 * guardados para que la página refresque sus columnas de roles y permisos.
 */
@Component({
  selector: 'app-roles-usuario',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EmptyState, Modal, TableroRoles],
  templateUrl: './roles-usuario.html',
  styleUrl: './roles-usuario.scss',
})
export class RolesUsuario {
  private readonly usuariosApi = inject(UsuariosApi);
  private readonly notificaciones = inject(Notificaciones);

  /** Usuario cuyos roles se editan; null = modal cerrado. */
  readonly usuario = input<Usuario | null>(null);
  /** Emite al cerrar: true si se guardó algún cambio. */
  readonly cerrar = output<boolean>();

  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);
  readonly roles = signal<RolDelUsuario[]>([]);
  readonly bloqueo = signal<string | null>(null);
  readonly guardando = signal(false);
  readonly confirmarDescarte = signal(false);
  readonly tablero = viewChild(TableroRoles);
  readonly cambios = computed(() => this.tablero()?.totalCambios() ?? 0);
  private huboCambios = false;

  constructor() {
    // Cada usuario cancela la carga del anterior: una respuesta lenta nunca
    // muestra (ni deja editar) los roles de otro usuario.
    toObservable(this.usuario)
      .pipe(
        switchMap((usuario) => {
          this.error.set(null);
          this.roles.set([]);
          this.bloqueo.set(null);
          this.confirmarDescarte.set(false);
          this.huboCambios = false;
          if (!usuario) {
            return EMPTY;
          }
          this.cargando.set(true);
          return this.usuariosApi.tableroRoles(usuario.id).pipe(
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
      .subscribe((tablero) => {
        this.bloqueo.set(tablero.bloqueo);
        this.roles.set(tablero.roles);
      });
  }

  /** Cerrar con cambios sin guardar pide confirmación en el pie del diálogo. */
  cerrarModal(): void {
    if (this.guardando()) {
      return;
    }
    if (this.cambios() > 0 && !this.confirmarDescarte()) {
      this.confirmarDescarte.set(true);
      return;
    }
    this.confirmarDescarte.set(false);
    this.cerrar.emit(this.huboCambios);
  }

  descartar(): void {
    this.tablero()?.descartar();
    this.confirmarDescarte.set(false);
  }

  /** Guarda en una sola petición todo lo movido en el tablero. */
  guardar(): void {
    const usuario = this.usuario();
    const tablero = this.tablero();
    if (!usuario || !tablero || this.guardando() || tablero.totalCambios() === 0) {
      return;
    }
    this.guardando.set(true);
    this.confirmarDescarte.set(false);
    this.usuariosApi
      .actualizarRoles(usuario.id, tablero.cambios())
      .pipe(finalize(() => this.guardando.set(false)))
      .subscribe({
        next: (resultado) => {
          if (this.usuario()?.id === usuario.id) {
            const ahora = new Set(resultado.asignados);
            this.roles.update((roles) =>
              roles.map((r) => {
                const asignado = ahora.has(r.id);
                const usuarios = r.usuarios + (asignado === r.asignado ? 0 : asignado ? 1 : -1);
                return { ...r, asignado, usuarios };
              }),
            );
          }
          this.huboCambios = true;
          this.notificaciones.exito('Roles del usuario actualizados', resumenLote(usuario.usuario, resultado));
        },
        error: (err: unknown) => this.notificaciones.error('No se pudieron guardar los roles', mensajeDeError(err)),
      });
  }
}

/** «@jperez · 1 agregado y 1 quitado». */
function resumenLote(usuario: string, { agregados, quitados }: UsuarioRolesLote): string {
  const partes = [
    agregados ? `${agregados} ${agregados === 1 ? 'agregado' : 'agregados'}` : '',
    quitados ? `${quitados} ${quitados === 1 ? 'quitado' : 'quitados'}` : '',
  ].filter(Boolean);
  return `${usuario} · ${partes.length ? partes.join(' y ') : 'sin cambios (ya estaban aplicados)'}`;
}
