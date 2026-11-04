import { Service, inject } from '@angular/core';
import { mensajeDeError } from '../../../../core/errors/error.util';
import { CacheCatalogos } from '../../../../core/utils/cache-catalogos';
import { AreasApi } from '../data/areas.api';
import { Area, Centro, Sistema } from '../data/bitacora.model';
import { CentrosApi } from '../data/centros.api';
import { SistemasApi } from '../data/sistemas.api';

/**
 * Caché de catálogos de Bitácora (centros, sistemas y áreas) compartida por
 * Incidencias y Dashboard. El módulo Control llama a `invalidar()` tras cada
 * alta, edición o baja para que el resto del panel vea el cambio.
 */
@Service()
export class BitacoraCatalogos extends CacheCatalogos<{
  centros: Centro[];
  sistemas: Sistema[];
  areas: Area[];
}> {
  private readonly centrosApi = inject(CentrosApi);
  private readonly sistemasApi = inject(SistemasApi);
  private readonly areasApi = inject(AreasApi);

  readonly centros = this.lista('centros');
  readonly sistemas = this.lista('sistemas');
  readonly areas = this.lista('areas');

  protected fuentes() {
    return {
      centros: this.centrosApi.listar(),
      sistemas: this.sistemasApi.listar(),
      areas: this.areasApi.listar(),
    };
  }

  protected avisoError(_clave: unknown, err: unknown): [string, string] {
    return ['No se pudieron cargar los catálogos', mensajeDeError(err)];
  }
}
