import { HttpClient, HttpParams } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiResponse } from '../models';

@Service()
export class Api {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiUrl.replace(/\/+$/, '');

  get<T>(ruta: string, params?: Record<string, unknown>): Observable<T> {
    return this.http
      .get<ApiResponse<T>>(this.url(ruta), { params: this.aParams(params) })
      .pipe(map(this.desempaquetar));
  }

  post<T>(ruta: string, cuerpo?: unknown): Observable<T> {
    return this.http.post<ApiResponse<T>>(this.url(ruta), cuerpo ?? {}).pipe(map(this.desempaquetar));
  }

  postForm<T>(ruta: string, formulario: FormData): Observable<T> {
    return this.http
      .post<ApiResponse<T>>(this.url(ruta), formulario)
      .pipe(map(this.desempaquetar));
  }

  put<T>(ruta: string, cuerpo?: unknown): Observable<T> {
    return this.http.put<ApiResponse<T>>(this.url(ruta), cuerpo ?? {}).pipe(map(this.desempaquetar));
  }

  patch<T>(ruta: string, cuerpo?: unknown): Observable<T> {
    return this.http.patch<ApiResponse<T>>(this.url(ruta), cuerpo ?? {}).pipe(map(this.desempaquetar));
  }

  delete<T>(ruta: string): Observable<T> {
    return this.http.delete<ApiResponse<T>>(this.url(ruta)).pipe(map(this.desempaquetar));
  }

  /** Archivo binario sin envoltorio (Excel generado por el servidor). */
  getArchivo(ruta: string): Observable<Blob> {
    return this.http.get(this.url(ruta), { responseType: 'blob' });
  }

  private readonly desempaquetar = <T>(respuesta: ApiResponse<T>): T => respuesta.datos;

  private url(ruta: string): string {
    return `${this.base}/${ruta.replace(/^\/+/, '')}`;
  }

  private aParams(params?: Record<string, unknown>): HttpParams {
    let httpParams = new HttpParams();
    if (!params) {
      return httpParams;
    }
    for (const [clave, valor] of Object.entries(params)) {
      if (valor === undefined || valor === null || valor === '') {
        continue;
      }
      httpParams = httpParams.set(clave, String(valor));
    }
    return httpParams;
  }
}
