package oracle.malla.response;

import java.time.Instant;
import java.util.List;
import oracle.malla.request.CentroMallaRequest.Capacidad;
import oracle.malla.request.CentroMallaRequest.Dotacion;
import oracle.malla.request.CentroMallaRequest.DotacionReal;
import oracle.malla.request.CentroMallaRequest.Flujo;
import oracle.malla.request.CentroMallaRequest.Ola;
import oracle.malla.request.CentroMallaRequest.Turno;
import oracle.malla.request.CentroMallaRequest.Zona;

/** Malla completa de un centro, con la misma forma con la que se guarda. */
public record CentroMallaResponse(
        Long id,
        String codigo,
        String nombre,
        List<Turno> turnos,
        List<Flujo> flujos,
        List<Ola> olas,
        List<Zona> zonas,
        Capacidad capacidad,
        List<Dotacion> dotacion,
        DotacionReal dotacionReal,
        Instant actualizadoEn) {
}
