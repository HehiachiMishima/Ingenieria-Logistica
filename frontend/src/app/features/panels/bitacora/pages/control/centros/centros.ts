import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { PERMISOS } from '../../../../../../core/config/permisos';
import { DirectorioUsuarios } from '../../../../../../core/services/directorio-usuarios';
import { Notificaciones } from '../../../../../../core/services/notificaciones';
import { EdicionCrud } from '../../../../../../core/utils/edicion-crud';
import { ListadoLocal } from '../../../../../../core/utils/listado-local';
import { ColumnasRedimensionables } from '../../../../../../shared/directives/columnas-redimensionables.directive';
import { HasPermiso } from '../../../../../../shared/directives/has-permiso.directive';
import { ActorLabelPipe } from '../../../../../../shared/pipes/actor-label.pipe';
import { EmptyState } from '../../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../../shared/ui/modal/modal';
import { Paginador } from '../../../../../../shared/ui/paginador/paginador';
import { Centro } from '../../../data/bitacora.model';
import { CentrosApi } from '../../../data/centros.api';
import { BitacoraCatalogos } from '../../../state/bitacora-catalogos';

@Component({
  selector: 'app-bitacora-centros',
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
  templateUrl: './centros.html',
  styleUrl: './centros.scss',
})
export class Centros {
  private readonly fb = inject(FormBuilder);
  private readonly centrosApi = inject(CentrosApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly catalogos = inject(BitacoraCatalogos);
  private readonly directorio = inject(DirectorioUsuarios);

  readonly permisos = PERMISOS;

  private readonly listado = new ListadoLocal<Centro>(
    () => this.centrosApi.listar(),
    (centro) => centro.centro,
  );
  readonly cargando = this.listado.cargando;
  readonly error = this.listado.error;
  readonly busqueda = this.listado.busqueda;
  readonly filtrados = this.listado.filtrados;
  readonly pagina = this.listado.pagina;
  readonly porPagina = this.listado.porPagina;
  readonly paginaActual = this.listado.paginaActual;
  readonly paginados = this.listado.paginados;

  private readonly edicion = new EdicionCrud<Centro>();
  readonly formAbierto = this.edicion.formAbierto;
  readonly editando = this.edicion.editando;
  readonly guardando = this.edicion.guardando;
  readonly eliminarAbierto = this.edicion.eliminarAbierto;
  readonly aEliminar = this.edicion.aEliminar;
  readonly eliminando = this.edicion.eliminando;

  readonly formulario = this.fb.nonNullable.group({
    centro: ['', [Validators.required, Validators.maxLength(50)]],
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

  campoInvalido(campo: 'centro'): boolean {
    const control = this.formulario.controls[campo];
    return control.touched && control.invalid;
  }

  abrirCrear(): void {
    this.formulario.reset({ centro: '' });
    this.edicion.abrir(null);
  }

  abrirEditar(centro: Centro): void {
    this.formulario.reset({ centro: centro.centro });
    this.edicion.abrir(centro);
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
    const peticion = { centro: valores.centro };
    const peticion$ = editando
      ? this.centrosApi.actualizar(editando.id, peticion)
      : this.centrosApi.crear(peticion);

    this.edicion.guardar(peticion$, (centro, editado) => {
      this.catalogos.invalidar();
      this.notificaciones.exito(
        'Guardado',
        `Centro ${centro.centro} ${editado ? 'actualizado' : 'creado'}.`,
      );
      this.cerrarForm();
      this.cargar();
    });
  }

  confirmarEliminar(centro: Centro): void {
    this.edicion.confirmarEliminar(centro);
  }

  eliminar(): void {
    this.edicion.eliminar(
      (centro) => this.centrosApi.eliminar(centro.id),
      (centro) => {
        this.listado.quitar(centro.id);
        this.catalogos.invalidar();
        this.notificaciones.exito('Eliminado', `Centro ${centro.centro} eliminado.`);
      },
    );
  }
}
