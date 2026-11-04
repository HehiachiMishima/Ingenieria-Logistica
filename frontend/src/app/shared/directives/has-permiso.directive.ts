import { Directive, TemplateRef, ViewContainerRef, effect, inject, input } from '@angular/core';
import { SesionStore } from '../../core/services/sesion';

type Modo = 'todos' | 'alguno';

@Directive({ selector: '[hasPermiso]' })
export class HasPermiso {
  private readonly sesion = inject(SesionStore);
  private readonly plantilla = inject<TemplateRef<unknown>>(TemplateRef);
  private readonly contenedor = inject(ViewContainerRef);

  readonly hasPermiso = input.required<string | string[]>();
  readonly hasPermisoModo = input<Modo>('todos');

  private vista: ReturnType<ViewContainerRef['createEmbeddedView']> | null = null;

  constructor() {
    effect(() => {
      const requeridos = this.normalizar(this.hasPermiso());
      const modo = this.hasPermisoModo();
      const actuales = this.sesion.permisos();

      const concedido =
        requeridos.length === 0
          ? true
          : modo === 'alguno'
            ? requeridos.some((permiso) => actuales.includes(permiso))
            : requeridos.every((permiso) => actuales.includes(permiso));

      if (concedido && !this.vista) {
        this.vista = this.contenedor.createEmbeddedView(this.plantilla);
      } else if (!concedido && this.vista) {
        this.contenedor.clear();
        this.vista = null;
      }
    });
  }

  private normalizar(valor: string | string[]): string[] {
    return Array.isArray(valor) ? valor : [valor];
  }
}
