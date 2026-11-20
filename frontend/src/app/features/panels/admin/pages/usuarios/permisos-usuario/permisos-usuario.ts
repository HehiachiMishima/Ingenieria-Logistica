import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { EMPTY, catchError, finalize, switchMap } from 'rxjs';
import { mensajeDeError } from '../../../../../../core/errors/error.util';
import { Usuario } from '../../../../../../core/models';
import { Notificaciones } from '../../../../../../core/services/notificaciones';
import { EmptyState } from '../../../../../../shared/ui/empty-state/empty-state';
import { Modal } from '../../../../../../shared/ui/modal/modal';
import { PermisoDelUsuario, UsuarioPermisosLote } from '../../../data/permiso.model';
import { UsuariosApi } from '../../../data/usuarios.api';
import { TableroExcepciones } from '../../../ui/tablero-excepciones/tablero-excepciones';

/**
 * Modal de permisos individuales de un usuario: lo que hereda de sus roles y sus excepciones
 * (conceder o denegar permisos sueltos) en un tablero que junta los cambios y los guarda en una
 * sola petición. Al cerrarse avisa si hubo cambios guardados para que la página refresque sus
 * columnas de roles y permisos.
 */
@Component({
  selector: 'app-permisos-usuario',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EmptyState, Modal, TableroExcepciones],
  templateUrl: './permisos-usuario.html',
  styleUrl: './permisos-usuario.scss',
})
export class PermisosUsuario {
  private readonly usuariosApi = inject(UsuariosApi);
  private readonly notificaciones = inject(Notificaciones);

  /** Usuario cuyos permisos se editan; null = modal cerrado. */
  readonly usuario = input<Usuario | null>(null);
  /** Emite al cerrar: true si se guardó algún cambio. */
  readonly cerrar = output<boolean>();

  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);
  readonly permisos = signal<PermisoDelUsuario[]>([]);
  readonly bloqueo = signal<string | null>(null);
  readonly guardando = signal(false);
  readonly confirmarDescarte = signal(false);
  readonly tablero = viewChild(TableroExcepciones);
  readonly cambios = computed(() => this.tablero()?.totalCambios() ?? 0);
  private huboCambios = false;

  constructor() {
    // Cada usuario cancela la carga del anterior: una respuesta lenta nunca
    // muestra (ni deja editar) los permisos de otro usuario.
    toObservable(this.usuario)
      .pipe(
        switchMap((usuario) => {
          this.error.set(null);
          this.permisos.set([]);
          this.bloqueo.set(null);
          this.confirmarDescarte.set(false);
          this.huboCambios = false;
          if (!usuario) {
            return EMPTY;
          }
          this.cargando.set(true);
          return this.usuariosApi.tableroPermisos(usuario.id).pipe(
            catchError((err: unknown) => {
              this.error.set(mensajeDeError(err));
              this.notificaciones.error('No se pudieron cargar los permisos', mensajeDeError(err));
              return EMPTY;
            }),
            finalize(() => this.cargando.set(false)),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((tablero) => {
        this.bloqueo.set(tablero.bloqueo);
        this.permisos.set(tablero.permisos);
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

  /** Guarda en una sola petición todas las excepciones movidas en el tablero. */
  guardar(): void {
    const usuario = this.usuario();
    const tablero = this.tablero();
    if (!usuario || !tablero || this.guardando() || tablero.totalCambios() === 0) {
      return;
    }
    this.guardando.set(true);
    this.confirmarDescarte.set(false);
    this.usuariosApi
      .actualizarPermisos(usuario.id, tablero.cambios())
      .pipe(finalize(() => this.guardando.set(false)))
      .subscribe({
        next: (resultado) => {
          if (this.usuario()?.id === usuario.id) {
            const vigentes = new Map(resultado.excepciones.map((e) => [e.permisoId, e.otorgado]));
            this.permisos.update((permisos) =>
              permisos.map((p) => ({ ...p, excepcion: vigentes.get(p.id) ?? null })),
            );
          }
          this.huboCambios = true;
          this.notificaciones.exito('Permisos individuales actualizados', resumenLote(usuario.usuario, resultado));
        },
        error: (err: unknown) => this.notificaciones.error('No se pudieron guardar los permisos', mensajeDeError(err)),
      });
  }
}

/** «@jperez · 2 concedidos, 1 denegado y 1 devuelto a su rol». */
function resumenLote(usuario: string, { concedidos, denegados, heredados }: UsuarioPermisosLote): string {
  const partes = [
    concedidos ? `${concedidos} ${concedidos === 1 ? 'concedido' : 'concedidos'}` : '',
    denegados ? `${denegados} ${denegados === 1 ? 'denegado' : 'denegados'}` : '',
    heredados ? `${heredados} ${heredados === 1 ? 'devuelto a su rol' : 'devueltos a su rol'}` : '',
  ].filter(Boolean);
  const texto =
    partes.length > 1 ? `${partes.slice(0, -1).join(', ')} y ${partes.at(-1)}` : (partes[0] ?? 'sin cambios (ya estaban aplicados)');
  return `${usuario} · ${texto}`;
}
