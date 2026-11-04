import { Pipe, PipeTransform, inject } from '@angular/core';
import { DirectorioUsuarios } from '../../core/services/directorio-usuarios';

/**
 * Etiqueta legible para las columnas creado_por / actualizado_por.
 * El esquema guarda el id del usuario actor; 0 = sistema/seeding.
 * Resuelve el nombre de usuario real (p. ej. "@hehiachi") usando el directorio.
 * Es impura para recalcular cuando el directorio termina de cargar.
 */
@Pipe({ name: 'actorLabel', pure: false })
export class ActorLabelPipe implements PipeTransform {
  private readonly directorio = inject(DirectorioUsuarios);

  transform(valor: number | null | undefined): string {
    return this.directorio.etiqueta(valor);
  }
}
