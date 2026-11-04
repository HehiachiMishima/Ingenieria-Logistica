/** Analítica del dashboard de Administración. */
export interface AnaliticaAdmin {
  kpis: {
    usuarios: number;
    usuariosActivos: number;
    usuariosSinRol: number;
    overridesGrant: number;
    overridesDeny: number;
  };
  coberturaPaneles: Conteo[];
}

export interface Conteo {
  etiqueta: string;
  valor: number;
}
