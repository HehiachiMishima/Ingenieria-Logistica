package oracle.simulacionsanta.response;

import java.util.List;

/** Indicadores de la simulación en el envío mostrado. */
public record KpisSimulacion(
    int paletas,
    int ubicadas,
    int sinUbicar,
    int completas,
    int enCurso,
    int bultosTotal,
    int bultosRecibidos,
    List<Integer> bultosEnvios,
    /** operarios × bultosHora. */
    int capacidadHora,
    /** (bultosTotal − bultosRecibidos) / capacidadHora × 60, con 1 decimal. */
    double minutosRestantes) {}
