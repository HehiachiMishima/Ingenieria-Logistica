import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { EMPTY, catchError, finalize, forkJoin, switchMap } from 'rxjs';
import { PERMISOS } from '../../../../../../core/config/permisos';
import { esAccesoDenegado, mensajeDeError } from '../../../../../../core/errors/error.util';
import { Usuario } from '../../../../../../core/models';
import { Notificaciones } from '../../../../../../core/services/notificaciones';
import { SesionStore } from '../../../../../../core/services/sesion';
import { EmptyState } from '../../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../../shared/ui/modal/modal';
import { Permiso } from '../../../data/permiso.model';
import { PermisosApi } from '../../../data/permisos.api';
import { UsuariosApi } from '../../../data/usuarios.api';
import { filasPermisos, resumenPermisos } from './permisos-usuario.logica';

/**
 * Modal de permisos individuales de un usuario: hereda lo que conceden sus
 * roles y permite conceder o denegar permisos sueltos (overrides). Se abre
 * con un usuario y, al cerrarse, avisa si hubo cambios para que la página
 * refresque sus columnas de roles y permisos.
 */
@Component({
  selector: 'app-permisos-usuario',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EmptyState, Icon, Modal],
  templateUrl: './permisos-usuario.html',
  styles: `
    .loading {
      font-size: var(--fs-md);
    }
  `,
})
export class PermisosUsuario {
  private readonly usuariosApi = inject(UsuariosApi);
  private readonly permisosApi = inject(PermisosApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly sesion = inject(SesionStore);

  /** Usuario cuyos permisos se editan; null = modal cerrado. */
  readonly usuario = input<Usuario | null>(null);
  /** Emite al cerrar: true si se cambió algún override. */
  readonly cerrar = output<boolean>();

  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);
  readonly catalogo = signal<Permiso[]>([]);
  readonly busqueda = signal('');
  readonly cambiando = signal<number | null>(null);

  private readonly overrides = signal<ReadonlyMap<number, boolean>>(new Map());
  private readonly otorgaRol = signal<ReadonlySet<number>>(new Set());
  private huboCambios = false;

  readonly permiteOverride = computed(() =>
    this.sesion.permisos().includes(PERMISOS.ASIGNAR_PERMISOS),
  );

  readonly filas = computed(() =>
    filasPermisos(this.catalogo(), this.busqueda(), this.overrides(), this.otorgaRol()),
  );

  readonly resumen = computed(() =>
    resumenPermisos(this.catalogo(), this.overrides(), this.otorgaRol()),
  );

  constructor() {
    // Cada usuario cancela la carga del anterior: una respuesta lenta nunca
    // muestra (ni deja editar) los permisos de otro usuario.
    toObservable(this.usuario)
      .pipe(
        switchMap((usuario) => {
          this.reiniciar();
          if (!usuario) {
            return EMPTY;
          }
          this.cargando.set(true);
          return forkJoin({
            catalogo: this.permisosApi.listar(),
            overrides: this.usuariosApi.permisos(usuario.id),
            efectivos: this.usuariosApi.resolucionEfectivos(usuario.id),
          }).pipe(
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
      .subscribe(({ catalogo, overrides, efectivos }) => {
        this.catalogo.set(catalogo);
        this.overrides.set(new Map(overrides.map((item) => [item.permisoId, item.otorgado])));
        this.otorgaRol.set(
          new Set(
            efectivos
              .filter((fila) => fila.origen === 'ROL' && fila.otorgado)
              .map((fila) => fila.permisoId),
          ),
        );
      });
  }

  cerrarModal(): void {
    this.cerrar.emit(this.huboCambios);
  }

  conceder(permiso: Permiso): void {
    this.aplicarOverride(permiso, true);
  }

  denegar(permiso: Permiso): void {
    this.aplicarOverride(permiso, false);
  }

  heredar(permiso: Permiso): void {
    const usuario = this.usuario();
    if (!usuario || this.cambiando() !== null || !this.overrides().has(permiso.id)) {
      return;
    }
    this.cambiando.set(permiso.id);
    this.usuariosApi
      .quitarPermiso(usuario.id, permiso.id)
      .pipe(finalize(() => this.cambiando.set(null)))
      .subscribe({
        next: () => {
          this.overrides.update((mapa) => {
            const copia = new Map(mapa);
            copia.delete(permiso.id);
            return copia;
          });
          this.huboCambios = true;
          this.notificaciones.info('Permiso heredado del rol', permiso.nombre);
        },
        error: (err: unknown) => this.avisarError(err),
      });
  }

  private aplicarOverride(permiso: Permiso, otorgado: boolean): void {
    const usuario = this.usuario();
    if (!usuario || this.cambiando() !== null || this.overrides().get(permiso.id) === otorgado) {
      return;
    }
    this.cambiando.set(permiso.id);
    this.usuariosApi
      .otorgarPermiso(usuario.id, { permisoId: permiso.id, otorgado })
      .pipe(finalize(() => this.cambiando.set(null)))
      .subscribe({
        next: () => {
          this.overrides.update((mapa) => new Map(mapa).set(permiso.id, otorgado));
          this.huboCambios = true;
          this.notificaciones.info(
            otorgado ? 'Permiso concedido' : 'Permiso denegado',
            permiso.nombre,
          );
        },
        error: (err: unknown) => this.avisarError(err),
      });
  }

  /** El 403 ya lo avisa el interceptor; aquí solo el resto de errores. */
  private avisarError(err: unknown): void {
    if (!esAccesoDenegado(err)) {
      this.notificaciones.error('No se pudo actualizar', mensajeDeError(err));
    }
  }

  private reiniciar(): void {
    this.error.set(null);
    this.busqueda.set('');
    this.overrides.set(new Map());
    this.otorgaRol.set(new Set());
    this.huboCambios = false;
  }
}
