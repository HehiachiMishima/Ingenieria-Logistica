import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Pagina } from '../../../../core/models';
import { Api } from '../../../../core/services/api';
import {
  ConfiguracionSanta,
  ConfiguracionSantaRequest,
  RegistroSanta,
  SimulacionSanta,
  ValidacionExcel,
} from '../models/santa.model';

const BASE = '/simulacion-santa';

/** Único punto de HTTP del panel Simulación Santa Anita. */
@Service()
export class SantaApi {
  private readonly api = inject(Api);

  listar(
    pagina: number,
    tamano: number,
    texto: string,
    estados: string[],
  ): Observable<Pagina<RegistroSanta>> {
    return this.api.get<Pagina<RegistroSanta>>(`${BASE}/registros`, {
      pagina,
      tamano,
      texto: texto.trim(),
      estados,
    });
  }

  opciones(): Observable<RegistroSanta[]> {
    return this.api.get<RegistroSanta[]>(`${BASE}/registros/opciones`);
  }

  registro(id: number): Observable<RegistroSanta> {
    return this.api.get<RegistroSanta>(`${BASE}/registros/${id}`);
  }

  /** Lee el Excel y devuelve el resumen y los errores, sin guardar nada. */
  validar(archivo: File): Observable<ValidacionExcel> {
    const formulario = new FormData();
    formulario.append('archivo', archivo);
    return this.api.postForm<ValidacionExcel>(`${BASE}/registros/validar`, formulario);
  }

  /** Crea el registro con las filas válidas del Excel. */
  subir(archivo: File, nombre: string): Observable<RegistroSanta> {
    const formulario = new FormData();
    formulario.append('archivo', archivo);
    formulario.append('nombre', nombre.trim());
    return this.api.postForm<RegistroSanta>(`${BASE}/registros`, formulario);
  }

  renombrar(id: number, nombre: string): Observable<RegistroSanta> {
    return this.api.put<RegistroSanta>(`${BASE}/registros/${id}`, { nombre: nombre.trim() });
  }

  eliminar(id: number): Observable<void> {
    return this.api.delete<void>(`${BASE}/registros/${id}`);
  }

  configuracion(id: number): Observable<ConfiguracionSanta> {
    return this.api.get<ConfiguracionSanta>(`${BASE}/registros/${id}/configuracion`);
  }

  guardarConfiguracion(id: number, peticion: ConfiguracionSantaRequest): Observable<ConfiguracionSanta> {
    return this.api.put<ConfiguracionSanta>(`${BASE}/registros/${id}/configuracion`, peticion);
  }

  simulacion(id: number, envio: number): Observable<SimulacionSanta> {
    return this.api.get<SimulacionSanta>(`${BASE}/registros/${id}/simulacion`, { envio });
  }

  /** Simula con una configuración sin guardarla (vista previa mientras se edita). */
  previsualizar(
    id: number,
    envio: number,
    peticion: ConfiguracionSantaRequest,
  ): Observable<SimulacionSanta> {
    return this.api.post<SimulacionSanta>(
      `${BASE}/registros/${id}/simulacion/previsualizar?envio=${envio}`,
      peticion,
    );
  }

  fijarEnvio(id: number, envio: number): Observable<RegistroSanta> {
    return this.api.put<RegistroSanta>(`${BASE}/registros/${id}/envio`, { envio });
  }

  predeterminados(): Observable<ConfiguracionSanta> {
    return this.api.get<ConfiguracionSanta>(`${BASE}/predeterminados`);
  }

  /**
   * Bloquea operarios y productividad para todos los registros: se aplican a los
   * pendientes y los nuevos nacen con esta configuración completa.
   */
  bloquearPredeterminados(peticion: ConfiguracionSantaRequest): Observable<ConfiguracionSanta> {
    return this.api.put<ConfiguracionSanta>(`${BASE}/predeterminados/bloquear`, peticion);
  }

  /** Abre operarios y productividad para cambiarlos (los valores se conservan). */
  desbloquearPredeterminados(): Observable<ConfiguracionSanta> {
    return this.api.put<ConfiguracionSanta>(`${BASE}/predeterminados/desbloquear`, {});
  }
}
