package org.tuplugin.plataforma.api;

import org.bukkit.Location;
import java.util.Map;
import java.util.UUID;

/**
 * Plataforma API para permitir que otros plugins interactúen con el plugin de plataformas.
 *
 * Esta interfaz expone métodos para crear y obtener plataformas asociadas a un jugador.
 */
public interface PlatformAPI {

    /**
     * Crea una nueva plataforma para el jugador especificado.
     *
     * @param owner El UUID del jugador que será dueño de la plataforma.
     * @param pos1  La primera ubicación (punto de selección).
     * @param pos2  La segunda ubicación (punto de selección).
     * @param name  Un nombre único para identificar la plataforma.
     */
    void createPlatform(UUID owner, Location pos1, Location pos2, String name);

    /**
     * Obtiene la plataforma con el nombre especificado para el jugador.
     *
     * @param owner El UUID del jugador.
     * @param name  El nombre de la plataforma.
     * @return La instancia de la plataforma, o null si no se encuentra.
     */
    Object getPlatform(UUID owner, String name);

    /**
     * Devuelve todas las plataformas creadas por el jugador.
     *
     * @param owner El UUID del jugador.
     * @return Un mapa donde la clave es el nombre de la plataforma y el valor la instancia de la misma.
     */
    Map<String, Object> getPlatforms(UUID owner);
}
