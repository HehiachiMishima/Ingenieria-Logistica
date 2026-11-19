import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { HttpContext } from '@angular/common/http';
import { PETICION_SILENCIOSA } from '../../../../../core/interceptors/error.interceptor';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, catchError, map, of, startWith, switchMap } from 'rxjs';
import { Api } from '../../../../../core/services/api';
import { PANEL_ADMIN } from '../../../../../core/config/paneles';

interface Evento {
  id: number;
  fecha: string;
  usuario?: string;
  accion?: string;
  entidad?: string;
  registro?: string;
  resultado?: string;
  metodo?: string;
  ruta?: string;
  estado?: number;
  duracion_ms?: number;
  correlacion: string;
}
interface Endpoint {
  metodo: string;
  ruta: string;
  peticiones: number;
  errores: number;
  porcentaje: number;
  p95: number;
}
interface Datos {
  filas: Evento[];
  siguiente: number | null;
  endpoints?: Endpoint[];
}

@Component({
  selector: 'app-observabilidad',
  imports: [DatePipe, FormsModule],
  templateUrl: './observabilidad.html',
  styleUrl: './observabilidad.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Observabilidad {
  private readonly api = inject(Api);
  readonly modo = inject(ActivatedRoute).snapshot.routeConfig!.path!;
  readonly modulo = PANEL_ADMIN.modulos.find(m => m.id === this.modo)!;
  readonly datos = signal<Datos>({ filas: [], siguiente: null });
  readonly cargando = signal(false);
  readonly error = signal(false);
  readonly detalle = signal<Endpoint | null>(null);
  readonly anteriores = signal<(number | null)[]>([]);
  private cursor: number | null = null;
  private readonly consultas = new Subject<void>();
  dias = this.modo === 'monitoreo' ? 1 : 7;
  usuario = '';
  registro = '';
  soloErrores = false;
  private filtros: Record<string, unknown> = { dias: this.dias };

  constructor() {
    this.consultas.pipe(startWith(undefined), switchMap(() => {
      this.cargando.set(true);
      this.error.set(false);
      const params = { ...this.filtros, antes: this.cursor };
      const endpoint = this.detalle();
      const context = new HttpContext().set(PETICION_SILENCIOSA, true);
      let consulta: Observable<Datos>;
      if (endpoint) {
        consulta = this.api.get<Datos>('/admin/monitoreo/peticiones', {
          ...params, ruta: endpoint.ruta, metodo: endpoint.metodo, errores: this.filtros['errores'],
        }, context);
      } else if (this.modo === 'monitoreo') {
        consulta = this.api.get<Endpoint[]>('/admin/monitoreo', params, context)
          .pipe(map(endpoints => ({ filas: [], siguiente: null, endpoints })));
      } else {
        consulta = this.api.get<Datos>('/admin/' + this.modo, params, context);
      }
      return consulta.pipe(catchError(() => {
        this.error.set(true);
        return of({ filas: [], siguiente: null });
      }));
    }), takeUntilDestroyed()).subscribe(datos => {
      this.datos.set(datos);
      this.cargando.set(false);
    });
  }

  buscar(): void {
    this.filtros = { dias: this.dias, usuario: this.usuario.trim(), registro: this.registro.trim(), errores: this.detalle() ? this.soloErrores : undefined };
    this.cursor = null;
    this.anteriores.set([]);
    this.consultas.next();
  }

  abrir(endpoint: Endpoint | null): void {
    this.detalle.set(endpoint);
    this.soloErrores = false;
    this.buscar();
  }

  paginar(avanzar: boolean): void {
    if (avanzar) {
      const siguiente = this.datos().siguiente;
      if (!siguiente) return;
      this.anteriores.update(v => [...v, this.cursor]);
      this.cursor = siguiente;
    } else {
      const historial = this.anteriores();
      if (!historial.length) return;
      this.cursor = historial.at(-1) ?? null;
      this.anteriores.set(historial.slice(0, -1));
    }
    this.consultas.next();
  }
}
